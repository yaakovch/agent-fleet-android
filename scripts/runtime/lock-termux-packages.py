#!/usr/bin/env python3
"""Resolve and lock the offline Agent Fleet Termux package closure."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import urllib.error
import urllib.request
from collections import deque
from pathlib import Path
from typing import Any


DEFAULT_BASE = "https://packages.termux.dev/apt/termux-main"
DEFAULT_ROOTS = (
    "bash", "ca-certificates", "coreutils", "curl", "findutils", "fzf", "git",
    "grep", "gzip", "openssh", "procps", "python", "sed", "tar", "termux-tools", "tmux",
)
PACKAGE_RE = re.compile(r"^[a-z0-9][a-z0-9+.-]*$")


class LockError(ValueError):
    pass


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "AgentFleetRuntimeLock/1"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.read()
    except (OSError, urllib.error.URLError) as error:
        raise LockError(f"could not fetch {url}: {error}") from error


def paragraphs(payload: str) -> list[dict[str, str]]:
    result: list[dict[str, str]] = []
    for block in payload.split("\n\n"):
        fields: dict[str, str] = {}
        current = ""
        for line in block.splitlines():
            if line.startswith((" ", "\t")) and current:
                fields[current] += " " + line.strip()
                continue
            if ": " not in line:
                continue
            current, value = line.split(": ", 1)
            fields[current] = value
        if fields.get("Package"):
            result.append(fields)
    return result


def dependency_name(value: str) -> str:
    cleaned = re.sub(r"\([^)]*\)", "", value)
    cleaned = re.sub(r"\[[^]]*\]", "", cleaned)
    cleaned = re.sub(r"<[^>]*>", "", cleaned).strip()
    name = cleaned.split(":", 1)[0].strip()
    return name if PACKAGE_RE.fullmatch(name) else ""


def dependency_groups(value: str) -> list[list[str]]:
    groups = []
    for group in value.split(","):
        alternatives = [name for part in group.split("|") if (name := dependency_name(part))]
        if alternatives:
            groups.append(alternatives)
    return groups


def recipe_metadata(source: str) -> tuple[str, str]:
    recipe = f"https://raw.githubusercontent.com/termux/termux-packages/master/packages/{source}/build.sh"
    try:
        payload = fetch(recipe).decode("utf-8", errors="replace")
    except LockError:
        return "NOASSERTION", recipe
    match = re.search(r'^TERMUX_PKG_LICENSE=["\']?([^"\'\n]+)', payload, re.MULTILINE)
    return (match.group(1).strip() if match else "NOASSERTION"), recipe


def package_source(fields: dict[str, str]) -> str:
    return fields.get("Source", fields["Package"]).split(" ", 1)[0]


def resolve(index: list[dict[str, str]], roots: list[str]) -> list[dict[str, str]]:
    packages = {fields["Package"]: fields for fields in index}
    providers: dict[str, list[str]] = {}
    for name, fields in packages.items():
        for provided in dependency_groups(fields.get("Provides", "")):
            for virtual in provided:
                providers.setdefault(virtual, []).append(name)
    selected: set[str] = set()
    queue = deque(roots)
    while queue:
        requested = queue.popleft()
        if requested in selected:
            continue
        name = requested if requested in packages else next(iter(sorted(providers.get(requested, []))), "")
        if not name:
            raise LockError(f"package dependency is unavailable: {requested}")
        if name in selected:
            continue
        selected.add(name)
        fields = packages[name]
        for alternatives in dependency_groups(",".join(filter(None, (fields.get("Pre-Depends", ""), fields.get("Depends", ""))))):
            choice = next((candidate for candidate in alternatives if candidate in packages), "")
            if not choice:
                choice = next((candidate for candidate in alternatives if candidate in providers), "")
            if not choice:
                raise LockError(f"no available dependency alternative for {name}: {' | '.join(alternatives)}")
            queue.append(choice)
    return [packages[name] for name in sorted(selected)]


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")


def spdx(lock: dict[str, Any]) -> dict[str, Any]:
    digest = hashlib.sha256(json.dumps(lock, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    packages = []
    relationships = []
    for item in lock["packages"]:
        identifier = "SPDXRef-Package-" + re.sub(r"[^A-Za-z0-9.-]", "-", item["name"])
        declared = item["license"] if item["license"] not in ("", "custom") else "NOASSERTION"
        packages.append({
            "SPDXID": identifier,
            "name": item["name"],
            "versionInfo": item["version"],
            "downloadLocation": item["url"],
            "filesAnalyzed": False,
            "checksums": [{"algorithm": "SHA256", "checksumValue": item["sha256"]}],
            "licenseConcluded": "NOASSERTION",
            "licenseDeclared": declared,
            "copyrightText": "NOASSERTION",
            "sourceInfo": f"Termux recipe: {item['recipeUrl']}; upstream: {item['homepage']}",
        })
        relationships.append({
            "spdxElementId": "SPDXRef-DOCUMENT", "relationshipType": "DESCRIBES", "relatedSpdxElement": identifier,
        })
    return {
        "spdxVersion": "SPDX-2.3",
        "dataLicense": "CC0-1.0",
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": "Agent-Fleet-Termux-offline-runtime",
        "documentNamespace": f"https://agent-fleet.local/sbom/termux-aarch64-{digest}",
        "creationInfo": {"created": "1970-01-01T00:00:00Z", "creators": ["Tool: lock-termux-packages.py"]},
        "packages": packages,
        "relationships": relationships,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--architecture", default="aarch64")
    parser.add_argument("--base-url", default=DEFAULT_BASE)
    parser.add_argument("--root", action="append", dest="roots")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sbom", type=Path, required=True)
    args = parser.parse_args()
    if args.architecture != "aarch64":
        raise SystemExit("only the approved arm64 runtime payload is supported")
    roots = sorted(set(args.roots or DEFAULT_ROOTS))
    if any(not PACKAGE_RE.fullmatch(name) for name in roots):
        raise SystemExit("invalid root package")
    base = args.base_url.rstrip("/")
    index_url = f"{base}/dists/stable/main/binary-{args.architecture}/Packages"
    index_payload = fetch(index_url)
    resolved = resolve(paragraphs(index_payload.decode("utf-8")), roots)
    entries = []
    for fields in resolved:
        required = ("Package", "Version", "Architecture", "Filename", "Size", "SHA256")
        if any(name not in fields for name in required):
            raise LockError(f"package index fields are incomplete for {fields.get('Package', 'unknown')}")
        source = package_source(fields)
        license_name, recipe = recipe_metadata(source)
        entries.append({
            "name": fields["Package"],
            "version": fields["Version"],
            "architecture": fields["Architecture"],
            # Debian epochs use ':' in archive names, which is not a valid
            # Windows build-host file name. The URL remains exact; only the
            # APK-local asset name is normalized.
            "file": Path(fields["Filename"]).name.replace(":", "_"),
            "url": f"{base}/{fields['Filename']}",
            "sha256": fields["SHA256"].lower(),
            "size": int(fields["Size"]),
            "description": fields.get("Description", ""),
            "homepage": fields.get("Homepage", "NOASSERTION"),
            "sourcePackage": source,
            "recipeUrl": recipe,
            "license": license_name,
        })
    lock = {
        "schemaVersion": 1,
        "architecture": args.architecture,
        "repository": base,
        "indexUrl": index_url,
        "indexSha256": hashlib.sha256(index_payload).hexdigest(),
        "rootPackages": roots,
        "totalSize": sum(item["size"] for item in entries),
        "packages": entries,
    }
    write_json(args.output, lock)
    write_json(args.sbom, spdx(lock))
    print(json.dumps({"packages": len(entries), "size": lock["totalSize"], "output": str(args.output)}, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except LockError as error:
        raise SystemExit(f"lock-termux-packages: {error}")
