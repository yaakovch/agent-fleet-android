#!/usr/bin/env python3
"""Verify every cross-repository and package input embedded in the APK."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import tarfile
from pathlib import Path
from urllib.parse import urlsplit


APPLICATION_ID = "com.yaakovch.fleet"
PREFIX = "/data/data/com.yaakovch.fleet/files/usr"
PACKAGE_REPOSITORY = "https://github.com/yaakovch/agent-fleet-termux-packages"
UPSTREAM_COMMIT = "c7ca367ba4271dd58dee1bdc220899dda7dc4a71"
ROOT_PACKAGES = [
    "bash", "ca-certificates", "coreutils", "curl", "findutils", "git", "grep", "gzip",
    "openssh", "procps", "python", "sed", "tar", "termux-tools",
]
PIN_FIELDS = {
    "schemaVersion", "applicationId", "prefix", "architecture", "upstreamCommit", "forkCommit",
    "releaseTag", "url", "file", "sha256", "size", "bootstrapFile", "bootstrapSha256",
    "bootstrapSize", "packageLockSha256", "packageLockSize", "sbomSha256", "sbomSize",
    "packageCount", "packagePayloadSize",
}
PIN_CLOSURE_FIELDS = {
    "runtimeRootsSha256", "closureManifestFile", "closureManifestSha256",
    "closureManifestSize", "excludedPackageCount",
}
LOCK_FIELDS = {
    "schemaVersion", "applicationId", "prefix", "architecture", "repository", "bundleUrl",
    "upstreamCommit", "forkCommit", "rootPackages", "totalSize", "packages",
}
LOCK_CLOSURE_FIELDS = {
    "runtimeRootsSha256", "closureManifestFile", "closureManifestSha256", "closureManifestSize",
}
PACKAGE_FIELDS = {
    "name", "version", "architecture", "file", "sha256", "size", "sourcePackage",
    "recipe", "homepage", "license", "description",
}
PACKAGE_CLOSURE_FIELDS = {
    "essential", "preDepends", "depends", "provides", "resolvedDependencies",
}
DEBIAN_DEPENDENCY = re.compile(
    r"^([a-z0-9][a-z0-9+.-]{0,127})(?::([a-z0-9_-]+))?"
    r"(?: \((<<|<=|=|>=|>>) ([A-Za-z0-9.+:~_-]{1,128})\))?"
    r"(?: \[([A-Za-z0-9_! -]{1,256})\])?"
    r"(?: <([A-Za-z0-9_!+.-]{1,128})>)?$"
)
DEBIAN_ARCHITECTURE = re.compile(r"^!?[a-z0-9][a-z0-9_-]{0,63}$")
TERMINAL_REPLY_SAFETY_MINIMUMS = {
    "clientRuntime": 61,
    "hostRuntime": 55,
    "providerAdapters": 28,
}
TERMINAL_REPLY_SAFETY_FILES = {
    "lib/tmux_safety.py",
    "lib/tmux_state.sh",
    "scripts/wtmux-tmux-safety",
}


def validate_terminal_reply_safe_runtime(components: object, names: object) -> dict:
    if not isinstance(components, dict) or any(
        not isinstance(components.get(name), dict)
        or type(components[name].get("sequence")) is not int
        or components[name]["sequence"] < minimum
        for name, minimum in TERMINAL_REPLY_SAFETY_MINIMUMS.items()
    ):
        raise ValueError("embedded runtime predates managed terminal-reply safety")
    if not TERMINAL_REPLY_SAFETY_FILES <= set(names):
        raise ValueError("embedded runtime omits managed terminal-reply safety")
    return components


def validate_connectable_registry_record(value: object) -> dict:
    if (
        not isinstance(value, dict)
        or value.get("schemaVersion") != 2
        or not isinstance(value.get("roles"), list)
    ):
        raise ValueError("embedded machine registry records must use identity schema v2")
    if "host" not in value["roles"]:
        return value
    endpoints = value.get("endpoints")
    if not isinstance(endpoints, list):
        raise ValueError(
            f"embedded host registry record has no endpoints: {value.get('id', 'unknown')}"
        )
    expected_network = {
        "tailscale": "tailnet",
        "ssh": "direct",
    }.get(value.get("transport"))
    connectable = any(
        expected_network is not None
        and isinstance(endpoint, dict)
        and endpoint.get("identityState") == "verified"
        and endpoint.get("network") == expected_network
        and (expected_network != "tailnet" or bool(endpoint.get("tailscaleNodeId")))
        and (
            (
                expected_network == "tailnet"
                and endpoint.get("sshEngine") == "tailscale-cli"
            )
            or (
                endpoint.get("sshEngine") == "openssh"
                and bool(endpoint.get("sshHostKeySha256"))
            )
        )
        for endpoint in endpoints
    )
    if not connectable:
        raise ValueError(
            f"embedded host registry record has no verified transport: {value.get('id', 'unknown')}"
        )
    return value


def compare_debian_versions(left: str, right: str) -> int:
    def split(value: str) -> tuple[str, str, str]:
        if not value or len(value) > 128 or any(character.isspace() for character in value):
            raise ValueError("invalid Debian package version")
        if ":" in value:
            epoch, remainder = value.split(":", 1)
        else:
            epoch, remainder = "0", value
        if not epoch.isdigit() or not remainder:
            raise ValueError("invalid Debian package version")
        if "-" in remainder:
            upstream, revision = remainder.rsplit("-", 1)
        else:
            upstream, revision = remainder, "0"
        if not upstream or not revision:
            raise ValueError("invalid Debian package version")
        return epoch, upstream, revision

    def compare_numeric(left_digits: str, right_digits: str) -> int:
        normalized_left = left_digits.lstrip("0") or "0"
        normalized_right = right_digits.lstrip("0") or "0"
        if len(normalized_left) != len(normalized_right):
            return -1 if len(normalized_left) < len(normalized_right) else 1
        return (normalized_left > normalized_right) - (normalized_left < normalized_right)

    def order(character: str | None) -> int:
        if character == "~":
            return -1
        if character is None:
            return 0
        if character.isalpha() and character.isascii():
            return ord(character)
        return ord(character) + 256

    def compare_part(left_part: str, right_part: str) -> int:
        left_index = right_index = 0
        while left_index < len(left_part) or right_index < len(right_part):
            while (
                (left_index < len(left_part) and not left_part[left_index].isdigit())
                or (right_index < len(right_part) and not right_part[right_index].isdigit())
            ):
                left_character = left_part[left_index] if left_index < len(left_part) else None
                right_character = right_part[right_index] if right_index < len(right_part) else None
                comparison = (order(left_character) > order(right_character)) - (
                    order(left_character) < order(right_character)
                )
                if comparison:
                    return comparison
                if left_index < len(left_part):
                    left_index += 1
                if right_index < len(right_part):
                    right_index += 1
            while left_index < len(left_part) and left_part[left_index] == "0":
                left_index += 1
            while right_index < len(right_part) and right_part[right_index] == "0":
                right_index += 1
            left_start, right_start = left_index, right_index
            while left_index < len(left_part) and left_part[left_index].isdigit():
                left_index += 1
            while right_index < len(right_part) and right_part[right_index].isdigit():
                right_index += 1
            left_digits = left_part[left_start:left_index]
            right_digits = right_part[right_start:right_index]
            if len(left_digits) != len(right_digits):
                return -1 if len(left_digits) < len(right_digits) else 1
            if left_digits != right_digits:
                return -1 if left_digits < right_digits else 1
        return 0

    left_epoch, left_upstream, left_revision = split(left)
    right_epoch, right_upstream, right_revision = split(right)
    return (
        compare_numeric(left_epoch, right_epoch)
        or compare_part(left_upstream, right_upstream)
        or compare_part(left_revision, right_revision)
    )


def debian_version_satisfies(actual: str, operator: str, expected: str) -> bool:
    if not operator:
        return True
    comparison = compare_debian_versions(actual, expected)
    return {
        "<<": comparison < 0,
        "<=": comparison <= 0,
        "=": comparison == 0,
        ">=": comparison >= 0,
        ">>": comparison > 0,
    }.get(operator, False)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def checked_file(root: Path, value: dict, maximum: int) -> Path:
    if set(value) != {"file", "sha256", "size"}:
        raise ValueError("embedded file descriptor fields are invalid")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,159}", value["file"]):
        raise ValueError("embedded file name is invalid")
    path = root / value["file"]
    if not path.is_file() or path.is_symlink() or not (1 <= path.stat().st_size <= maximum):
        raise ValueError(f"embedded file is missing or unsafe: {value['file']}")
    if path.stat().st_size != value["size"] or sha256(path) != value["sha256"]:
        raise ValueError(f"embedded file does not match its descriptor: {value['file']}")
    return path


def public_der(path: Path) -> bytes:
    text = path.read_text(encoding="ascii")
    if not text.startswith("-----BEGIN PUBLIC KEY-----") or not text.strip().endswith("-----END PUBLIC KEY-----"):
        raise ValueError("trusted runtime key is not a public PEM key")
    payload = text.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
    return base64.b64decode("".join(payload.split()), validate=True)


def has_complete_extension(value: dict, base: set[str], extension: set[str], label: str) -> bool:
    fields = set(value)
    if fields == base:
        return False
    if fields == base | extension:
        return True
    present = sorted(fields & extension)
    missing = sorted(extension - fields)
    extra = sorted(fields - base - extension)
    raise ValueError(
        f"{label} fields are incomplete: present={present}, missing={missing}, extra={extra}"
    )


def bounded_metadata(value: object, maximum: int, *, allow_empty: bool = False) -> bool:
    return (
        isinstance(value, str)
        and (allow_empty or bool(value))
        and len(value) <= maximum
        and not any(ord(character) < 32 or ord(character) == 127 for character in value)
    )


def canonical_json(value: object) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode()


def spdx_package_id(name: str) -> str:
    safe_name = re.sub(r"[^A-Za-z0-9.-]", "-", name)
    if safe_name != name:
        safe_name = f"{safe_name}-{hashlib.sha256(name.encode()).hexdigest()[:8]}"
    return "SPDXRef-Package-" + safe_name


LICENSE_ALIASES = {
    "AGPL-3.0": "AGPL-3.0-only",
    "Artistic-License-2.0": "Artistic-2.0",
    "BSD 2-Clause": "BSD-2-Clause",
    "BSD 3-Clause": "BSD-3-Clause",
    "GPL-1.0": "GPL-1.0-only",
    "GPL-2.0": "GPL-2.0-only",
    "GPL-3.0": "GPL-3.0-only",
    "LGPL-2.0": "LGPL-2.0-only",
    "LGPL-2.1": "LGPL-2.1-only",
    "LGPL-3.0": "LGPL-3.0-only",
    "Mozilla-1.1": "MPL-1.1",
    "PythonPL": "Python-2.0",
    "ZLIB": "Zlib",
}
LICENSE_REFERENCES = {
    "BSD": "LicenseRef-Termux-BSD-Generic",
    "Public Domain": "LicenseRef-Termux-Public-Domain",
    "custom": "LicenseRef-Termux-Custom",
}
KNOWN_SPDX_LICENSES = {
    "0BSD", "Apache-1.1", "Apache-2.0", "Artistic-1.0", "Artistic-2.0",
    "BSD-2-Clause", "BSD-3-Clause", "BSL-1.0", "CC0-1.0", "curl", "EPL-1.0",
    "EPL-2.0", "HPND", "ICU", "ISC", "MIT", "MIT-0", "MPL-1.1", "MPL-2.0",
    "NCSA", "OpenSSL", "Python-2.0", "Unlicense", "WTFPL", "Zlib",
}


def normalize_license(raw_value: str) -> tuple[str, dict[str, str]]:
    if not raw_value or raw_value == "NOASSERTION":
        return "NOASSERTION", {}
    expressions: list[str] = []
    extracted: dict[str, str] = {}
    for raw_component in (item.strip() for item in raw_value.split(",")):
        if not raw_component:
            continue
        expression = LICENSE_ALIASES.get(raw_component) or LICENSE_REFERENCES.get(raw_component)
        if expression is None and (
            raw_component in KNOWN_SPDX_LICENSES
            or re.fullmatch(r"(?:A?GPL|LGPL)-[123]\.[01]-(?:only|or-later)", raw_component)
        ):
            expression = raw_component
        if expression is None:
            slug = re.sub(r"[^A-Za-z0-9.-]+", "-", raw_component).strip(".-") or "Unknown"
            digest = hashlib.sha256(raw_component.encode()).hexdigest()[:8]
            expression = f"LicenseRef-Termux-{slug}-{digest}"
        if expression.startswith("LicenseRef-"):
            extracted[expression] = raw_component
        if expression not in expressions:
            expressions.append(expression)
    return (" AND ".join(expressions) if expressions else "NOASSERTION"), extracted


def expected_spdx(lock: dict) -> dict:
    packages = []
    relationships = []
    extracted_licenses: dict[str, str] = {}
    package_ids = {item["name"]: spdx_package_id(item["name"]) for item in lock["packages"]}
    for item in lock["packages"]:
        identifier = package_ids[item["name"]]
        expression, extracted = normalize_license(item["license"])
        for license_id, raw_value in extracted.items():
            if license_id in extracted_licenses and extracted_licenses[license_id] != raw_value:
                raise ValueError(f"conflicting SPDX license reference: {license_id}")
            extracted_licenses[license_id] = raw_value
        packages.append({
            "SPDXID": identifier,
            "name": item["name"],
            "versionInfo": item["version"],
            "downloadLocation": f"{lock['bundleUrl']}#packages/{item['file']}",
            "filesAnalyzed": False,
            "checksums": [{"algorithm": "SHA256", "checksumValue": item["sha256"]}],
            "licenseConcluded": "NOASSERTION",
            "licenseDeclared": expression,
            "copyrightText": "NOASSERTION",
            "sourceInfo": f"Termux recipe {item['recipe']}; upstream {item['homepage']}",
            "comment": f"Termux recipe license metadata: {item['license']}",
        })
        relationships.append({
            "spdxElementId": "SPDXRef-DOCUMENT",
            "relationshipType": "DESCRIBES",
            "relatedSpdxElement": identifier,
        })
        for dependency in item["resolvedDependencies"]:
            if dependency not in package_ids:
                raise ValueError(f"SPDX dependency is missing from lock: {dependency}")
            relationships.append({
                "spdxElementId": identifier,
                "relationshipType": "DEPENDS_ON",
                "relatedSpdxElement": package_ids[dependency],
            })
    lock_digest = hashlib.sha256(
        json.dumps(lock, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    document = {
        "spdxVersion": "SPDX-2.3",
        "dataLicense": "CC0-1.0",
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": f"Agent-Fleet-Termux-{lock['architecture']}",
        "documentNamespace": f"https://agent-fleet.local/sbom/{lock_digest}",
        "creationInfo": {
            "created": "1970-01-01T00:00:00Z",
            "creators": ["Tool: package-runtime.py"],
        },
        "documentComment": (
            f"Built from fork commit {lock['forkCommit']} based on upstream "
            f"commit {lock['upstreamCommit']}."
        ),
        "packages": packages,
        "relationships": relationships,
    }
    if extracted_licenses:
        document["hasExtractedLicensingInfos"] = [
            {
                "licenseId": license_id,
                "name": raw_value,
                "extractedText": f"Termux recipe license metadata: {raw_value}",
            }
            for license_id, raw_value in sorted(extracted_licenses.items())
        ]
    return document


def parsed_debian_dependency(
    value: object,
    architecture: str,
    *,
    provided: bool = False,
) -> tuple[str, bool, bool, str, str]:
    match = DEBIAN_DEPENDENCY.fullmatch(value) if isinstance(value, str) else None
    if match is None:
        raise ValueError("package dependency expression is invalid")
    qualifier = match.group(2) or ""
    operator = match.group(3) or ""
    version = match.group(4) or ""
    if bool(operator) != bool(version):
        raise ValueError("package dependency version restriction is invalid")
    architectures = (match.group(5) or "").split()
    profiles = match.group(6) or ""
    if any(DEBIAN_ARCHITECTURE.fullmatch(item) is None for item in architectures):
        raise ValueError("package dependency architecture is invalid")
    if provided:
        if qualifier or architectures or profiles or operator not in {"", "="}:
            raise ValueError("package Provides expression is invalid")
        return match.group(1), True, False, operator, version
    if profiles:
        raise ValueError("package dependency profiles are unsupported")
    if qualifier == "native":
        raise ValueError("package :native dependency qualifier is unsupported")
    if qualifier not in {"", "any", architecture}:
        raise ValueError("package cross-architecture dependency is unsupported")
    positives = {item for item in architectures if not item.startswith("!")}
    negatives = {item[1:] for item in architectures if item.startswith("!")}
    if positives and negatives:
        raise ValueError("package dependency mixes architecture restrictions")
    if any(
        item == "any" or item.startswith("any-") or item.endswith("-any")
        for item in positives | negatives
    ):
        raise ValueError("package dependency architecture wildcard is unsupported")
    return (
        match.group(1),
        architecture not in negatives and (not positives or architecture in positives),
        qualifier == "any",
        operator,
        version,
    )


def validate_dependency_closure(
    packages: list[dict],
    roots: list[str],
    architecture: str,
) -> None:
    by_name = {item["name"]: item for item in packages}
    providers: dict[str, list[tuple[str, str]]] = {}
    for item in packages:
        for provided in item["provides"]:
            name, _, _, _, version = parsed_debian_dependency(
                provided, architecture, provided=True
            )
            providers.setdefault(name, []).append((item["name"], version))
    for item in packages:
        groups = []
        for alternatives in item["preDepends"] + item["depends"]:
            group = [
                (name, requires_multi_arch_allowed, operator, version)
                for expression in alternatives
                for name, applicable, requires_multi_arch_allowed, operator, version in [
                    parsed_debian_dependency(expression, architecture)
                ]
                if applicable
            ]
            if group:
                groups.append(group)
        resolved = item["resolvedDependencies"]
        if any(dependency not in by_name for dependency in resolved):
            raise ValueError("resolved dependency is absent from package lock")

        def represented(
            dependency: str,
            group: list[tuple[str, bool, str, str]],
        ) -> bool:
            return any(
                (
                    not requires_multi_arch_allowed
                    or by_name[dependency].get("multiArch", "no") == "allowed"
                )
                and (
                    (
                        dependency == alternative
                        and debian_version_satisfies(
                            by_name[dependency]["version"], operator, expected_version
                        )
                    )
                    or any(
                        provider == dependency
                        and (
                            not operator
                            or bool(provided_version)
                            and debian_version_satisfies(
                                provided_version, operator, expected_version
                            )
                        )
                        for provider, provided_version in providers.get(alternative, ())
                    )
                )
                for alternative, requires_multi_arch_allowed, operator, expected_version in group
            )

        if any(not any(represented(dependency, group) for dependency in resolved) for group in groups):
            raise ValueError("dependency group has no resolved edge")
        if any(not any(represented(dependency, group) for group in groups) for dependency in resolved):
            raise ValueError("resolved edge is not declared")
    pending = sorted(set(roots) | {item["name"] for item in packages if item["essential"]})
    selected: set[str] = set()
    while pending:
        name = pending.pop()
        if name in selected:
            continue
        if name not in by_name:
            raise ValueError(f"runtime closure root is missing: {name}")
        selected.add(name)
        for dependency in by_name[name]["resolvedDependencies"]:
            if dependency not in selected:
                pending.append(dependency)
    if selected != set(by_name):
        raise ValueError("package lock is not the exact runtime closure")


def runtime_pin(path: Path, architecture: str) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    expected_file = f"agent-fleet-runtime-{architecture}.zip"
    extended = has_complete_extension(value, PIN_FIELDS, PIN_CLOSURE_FIELDS, "runtime pin")
    if (
        value.get("schemaVersion") != 1
        or value.get("applicationId") != APPLICATION_ID or value.get("prefix") != PREFIX
        or value.get("architecture") != architecture or value.get("upstreamCommit") != UPSTREAM_COMMIT
        or not re.fullmatch(r"[a-f0-9]{40}", value.get("forkCommit", ""))
        or not re.fullmatch(r"agent-fleet-runtime-[A-Za-z0-9._-]+", value.get("releaseTag", ""))
        or value.get("file") != expected_file or value.get("bootstrapFile") != f"bootstrap-{architecture}.zip"
        or value.get("url") != f"{PACKAGE_REPOSITORY}/releases/download/{value.get('releaseTag')}/{expected_file}"
        or any(not re.fullmatch(r"[a-f0-9]{64}", value.get(field, "")) for field in (
            "sha256", "bootstrapSha256", "packageLockSha256", "sbomSha256",
        ))
        or any(type(value.get(field)) is not int or value[field] < 1 for field in (
            "size", "bootstrapSize", "packageLockSize", "sbomSize", "packageCount", "packagePayloadSize",
        ))
    ):
        raise ValueError(f"runtime pin is invalid: {architecture}")
    if extended and (
        not re.fullmatch(r"[a-f0-9]{64}", value.get("runtimeRootsSha256", ""))
        or value.get("closureManifestFile") != "agent-fleet-runtime-closure-v1.json"
        or not re.fullmatch(r"[a-f0-9]{64}", value.get("closureManifestSha256", ""))
        or type(value.get("closureManifestSize")) is not int
        or not 1 <= value["closureManifestSize"] <= 2 * 1024 * 1024
        or type(value.get("excludedPackageCount")) is not int
        or value["excludedPackageCount"] < 0
    ):
        raise ValueError(f"runtime closure pin is invalid: {architecture}")
    return value


def verify(root: Path) -> dict:
    pins_root = root.parents[2] / "runtime-pins"
    pins = {
        architecture: runtime_pin(pins_root / f"agent-fleet-runtime-{architecture}.json", architecture)
        for architecture in ("aarch64", "x86_64")
    }
    if any(len({pin[field] for pin in pins.values()}) != 1 for field in ("releaseTag", "upstreamCommit", "forkCommit")):
        raise ValueError("runtime architecture pins do not share one source release")
    pin_extensions = {bool(PIN_CLOSURE_FIELDS <= set(pin)) for pin in pins.values()}
    if len(pin_extensions) != 1:
        raise ValueError("runtime architecture pins mix legacy and closure formats")

    descriptor_path = root / "embedded-runtime-v1.json"
    descriptor = json.loads(descriptor_path.read_text(encoding="utf-8"))
    if set(descriptor) != {
        "schemaVersion", "baselineVersion", "sourceRepository", "wtmuxCommit",
        "contractPackageVersion", "components", "protocolVersion", "supportedAbis",
        "runtime", "registry", "packageLock", "sbom", "trustedRuntimeKeys",
    } or descriptor["schemaVersion"] != 1:
        raise ValueError("embedded-runtime-v1 fields are invalid")
    if not re.fullmatch(r"git-[a-f0-9]{7}", descriptor["baselineVersion"]):
        raise ValueError("embedded baseline version is invalid")
    if not re.fullmatch(r"[a-f0-9]{40}", descriptor["wtmuxCommit"]):
        raise ValueError("embedded wtmux commit is invalid")
    if descriptor["baselineVersion"] != "git-" + descriptor["wtmuxCommit"][:7]:
        raise ValueError("embedded baseline version and wtmux commit disagree")
    if (
        descriptor["sourceRepository"] != "https://github.com/yaakovch/wtmux"
        or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,127}", descriptor["contractPackageVersion"])
        or set(descriptor["components"]) != {"clientRuntime", "hostRuntime", "providerAdapters", "contracts"}
    ):
        raise ValueError("embedded runtime component metadata is invalid")
    for name, component in descriptor["components"].items():
        if (
            set(component) != {"sequence", "version"} or type(component["sequence"]) is not int
            or component["sequence"] < 1
            or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,127}", component["version"])
        ):
            raise ValueError(f"embedded runtime component is invalid: {name}")
    if (
        any(descriptor["components"][name]["version"] != descriptor["baselineVersion"]
            for name in ("clientRuntime", "hostRuntime", "providerAdapters"))
        or descriptor["components"]["contracts"]["version"] != descriptor["contractPackageVersion"]
    ):
        raise ValueError("embedded runtime component versions disagree")
    if descriptor["protocolVersion"] != 2 or descriptor["supportedAbis"] != ["arm64-v8a"]:
        raise ValueError("embedded protocol or ABI declaration is unexpected")

    runtime_value = descriptor["runtime"]
    if set(runtime_value) != {
        "file", "sha256", "size", "formatVersion", "manifestSha256",
        "sbomSha256", "licenseSha256",
    }:
        raise ValueError("embedded wtmux runtime descriptor fields are invalid")
    if (
        runtime_value["formatVersion"] != 2
        or not re.fullmatch(r"[a-f0-9]{64}", runtime_value["manifestSha256"])
        or not re.fullmatch(r"[a-f0-9]{64}", runtime_value["sbomSha256"])
        or not re.fullmatch(r"[a-f0-9]{64}", runtime_value["licenseSha256"])
    ):
        raise ValueError("embedded wtmux runtime metadata is invalid")
    runtime = checked_file(
        root, {key: runtime_value[key] for key in ("file", "sha256", "size")},
        32 * 1024 * 1024,
    )
    with tarfile.open(runtime, "r:") as archive:
        members = archive.getmembers()
        if not members or any(not member.isfile() for member in members):
            raise ValueError("runtime archive contains a non-file member")
        names = [member.name for member in members]
        if len(names) != len(set(names)) or "runtime-manifest.json" not in names:
            raise ValueError("runtime archive members are invalid")
        manifest_handle = archive.extractfile("runtime-manifest.json")
        if manifest_handle is None:
            raise ValueError("runtime archive manifest is unreadable")
        manifest_payload = manifest_handle.read()
        if hashlib.sha256(manifest_payload).hexdigest() != runtime_value["manifestSha256"]:
            raise ValueError("embedded runtime manifest does not match its descriptor")
        manifest = json.loads(manifest_payload)
        if (
            set(manifest) != {"formatVersion", "version", "components", "source", "target", "files"}
            or manifest.get("formatVersion") != 2 or manifest.get("version") != descriptor["baselineVersion"]
            or manifest.get("components") != descriptor["components"]
            or manifest.get("source") != {
                "schemaVersion": 1,
                "repository": descriptor["sourceRepository"],
                "commit": descriptor["wtmuxCommit"],
                "license": "MIT",
                "contractPackageVersion": descriptor["contractPackageVersion"],
            }
            or manifest.get("target") != {
                "platform": "termux",
                "architecture": "universal",
                "prefix": "/data/data/com.yaakovch.fleet/files/home/.local/share/agent-fleet/wtmux",
            }
        ):
            raise ValueError("runtime archive version does not match embedded baseline")
        expected = {"runtime-manifest.json"}
        for item in manifest.get("files", []):
            expected.add(item["path"])
            member = archive.getmember(item["path"])
            payload = archive.extractfile(member).read()
            if len(payload) != item["size"] or hashlib.sha256(payload).hexdigest() != item["sha256"]:
                raise ValueError(f"runtime member verification failed: {item['path']}")
        if expected != set(names):
            raise ValueError("runtime archive contents do not match its manifest")
        validate_terminal_reply_safe_runtime(manifest["components"], names)
        entries = {item["path"]: item for item in manifest["files"]}
        if (
            entries.get("runtime.spdx.json", {}).get("sha256") != runtime_value["sbomSha256"]
            or entries.get("runtime-license.txt", {}).get("sha256") != runtime_value["licenseSha256"]
            or "fleet/contracts/schemas/release-set-v1.schema.json" not in entries
        ):
            raise ValueError("runtime archive SBOM, license, or contracts are missing")

    registry_value = descriptor["registry"]
    if set(registry_value) != {"file", "sha256", "size"}:
        raise ValueError("embedded registry descriptor fields are invalid")
    registry = checked_file(root, registry_value, 16 * 1024 * 1024)
    with tarfile.open(registry, "r:") as archive:
        members = archive.getmembers()
        names = [member.name for member in members]
        if (
            not members or len(names) != len(set(names))
            or any(not member.isfile() for member in members)
            or "registry-manifest.json" not in names
        ):
            raise ValueError("embedded registry archive members are invalid")
        manifest_handle = archive.extractfile("registry-manifest.json")
        if manifest_handle is None:
            raise ValueError("embedded registry manifest is unreadable")
        registry_manifest = json.load(manifest_handle)
        if (
            set(registry_manifest) != {"formatVersion", "schemaVersion", "records"}
            or registry_manifest["formatVersion"] != 1
            or registry_manifest["schemaVersion"] != 1
            or not isinstance(registry_manifest["records"], list)
            or not 1 <= len(registry_manifest["records"]) <= 256
        ):
            raise ValueError("embedded registry manifest is invalid")
        expected_registry_members = {"registry-manifest.json"}
        registry_ids = set()
        for item in registry_manifest["records"]:
            if (
                set(item) != {"id", "path", "sha256", "size"}
                or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", item["id"])
                or item["id"] in registry_ids
                or item["path"] != f"machines/{item['id']}.json"
                or not re.fullmatch(r"[a-f0-9]{64}", item["sha256"])
                or type(item["size"]) is not int
                or not 1 <= item["size"] <= 64 * 1024
            ):
                raise ValueError("embedded registry record is invalid")
            registry_ids.add(item["id"])
            expected_registry_members.add(item["path"])
            payload = archive.extractfile(item["path"]).read()
            if len(payload) != item["size"] or hashlib.sha256(payload).hexdigest() != item["sha256"]:
                raise ValueError(f"embedded registry member verification failed: {item['id']}")
            try:
                record = json.loads(payload)
            except (UnicodeError, json.JSONDecodeError) as error:
                raise ValueError(
                    f"embedded machine registry record is not valid JSON: {item['id']}"
                ) from error
            if record.get("id") != item["id"]:
                raise ValueError(
                    f"embedded machine registry record ID does not match its manifest: {item['id']}"
                )
            validate_connectable_registry_record(record)
        if set(names) != expected_registry_members:
            raise ValueError("embedded registry contents do not match its manifest")

    package_value = descriptor["packageLock"]
    if set(package_value) != {"file", "sha256", "size", "packages", "payloadSize"}:
        raise ValueError("package lock descriptor fields are invalid")
    package_lock = checked_file(root, {key: package_value[key] for key in ("file", "sha256", "size")}, 2 * 1024 * 1024)
    lock = json.loads(package_lock.read_text(encoding="utf-8"))
    packages = lock.get("packages", [])
    lock_extended = has_complete_extension(lock, LOCK_FIELDS, LOCK_CLOSURE_FIELDS, "package lock")
    if lock_extended != pin_extensions.pop():
        raise ValueError("runtime pins and embedded package lock use different closure formats")
    bundle_url = lock.get("bundleUrl", "")
    parsed_bundle = urlsplit(bundle_url)
    if (
        lock.get("schemaVersion") != 2
        or lock.get("applicationId") != APPLICATION_ID or lock.get("prefix") != PREFIX
        or lock.get("architecture") != "aarch64" or lock.get("repository") != PACKAGE_REPOSITORY
        or parsed_bundle.scheme != "https" or parsed_bundle.netloc != "github.com"
        or not bundle_url.startswith(PACKAGE_REPOSITORY + "/releases/download/agent-fleet-runtime-")
        or not bundle_url.endswith("/agent-fleet-runtime-aarch64.zip")
        or lock.get("upstreamCommit") != UPSTREAM_COMMIT
        or not re.fullmatch(r"[a-f0-9]{40}", lock.get("forkCommit", ""))
        or len(packages) != package_value["packages"] or sum(item["size"] for item in packages) != package_value["payloadSize"]
        or lock.get("totalSize") != package_value["payloadSize"]
    ):
        raise ValueError("package lock summary does not match embedded descriptor")
    arm64_pin = pins["aarch64"]
    if (
        lock["bundleUrl"] != arm64_pin["url"] or lock["upstreamCommit"] != arm64_pin["upstreamCommit"]
        or lock["forkCommit"] != arm64_pin["forkCommit"]
        or package_value["sha256"] != arm64_pin["packageLockSha256"]
        or package_value["size"] != arm64_pin["packageLockSize"]
        or package_value["packages"] != arm64_pin["packageCount"]
        or package_value["payloadSize"] != arm64_pin["packagePayloadSize"]
        or descriptor["sbom"]["sha256"] != arm64_pin["sbomSha256"]
        or descriptor["sbom"]["size"] != arm64_pin["sbomSize"]
    ):
        raise ValueError("embedded arm64 metadata does not match its immutable runtime pin")
    if lock_extended and (
        lock.get("runtimeRootsSha256") != arm64_pin.get("runtimeRootsSha256")
        or lock.get("closureManifestFile") != "agent-fleet-runtime-closure-v1.json"
        or lock.get("closureManifestFile") != arm64_pin.get("closureManifestFile")
        or lock.get("closureManifestSha256") != arm64_pin.get("closureManifestSha256")
        or lock.get("closureManifestSize") != arm64_pin.get("closureManifestSize")
        or not re.fullmatch(r"[a-f0-9]{64}", lock.get("runtimeRootsSha256", ""))
        or not re.fullmatch(r"[a-f0-9]{64}", lock.get("closureManifestSha256", ""))
        or type(lock.get("closureManifestSize")) is not int
        or not 1 <= lock["closureManifestSize"] <= 2 * 1024 * 1024
    ):
        raise ValueError("embedded closure metadata does not match its immutable runtime pin")
    root_packages = lock.get("rootPackages")
    if root_packages != ROOT_PACKAGES:
        raise ValueError("package lock roots are invalid")
    names = set()
    files = set()
    ordered_names = []
    for item in packages:
        item_fields = frozenset(item)
        if item_fields == PACKAGE_FIELDS:
            package_extended = False
        elif item_fields in {
            frozenset(PACKAGE_FIELDS | PACKAGE_CLOSURE_FIELDS),
            frozenset(PACKAGE_FIELDS | PACKAGE_CLOSURE_FIELDS | {"multiArch"}),
        }:
            package_extended = True
        else:
            raise ValueError("package lock record fields are incomplete")
        if package_extended != lock_extended:
            raise ValueError("package lock mixes legacy and closure package records")
        if item["name"] in names or item["file"] in files:
            raise ValueError("package lock contains a duplicate")
        names.add(item["name"])
        ordered_names.append(item["name"])
        files.add(item["file"])
        homepage = item.get("homepage")
        homepage_url = urlsplit(homepage) if isinstance(homepage, str) else None
        if (
            not re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", item["name"])
            or not bounded_metadata(item.get("version"), 128)
            or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+~-]*\.deb", item["file"])
            or not re.fullmatch(r"[a-f0-9]{64}", item["sha256"])
            or type(item.get("size")) is not int or not 1 <= item["size"] <= 64 * 1024 * 1024
            or item["architecture"] not in {"aarch64", "all"}
            or not re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", item["sourcePackage"])
            or not re.fullmatch(r"(?:NOASSERTION|(?:packages|root-packages|x11-packages)/[a-z0-9][a-z0-9+.-]*/build\.sh)", item["recipe"])
            or not isinstance(homepage, str) or len(homepage) > 2048
            or (homepage != "NOASSERTION" and (
                homepage_url.scheme not in {"http", "https"} or not homepage_url.hostname
                or homepage_url.username
            ))
            or not bounded_metadata(item.get("license"), 256)
            or not bounded_metadata(item.get("description"), 4096, allow_empty=True)
        ):
            raise ValueError(f"package lock record is invalid: {item.get('name', 'unknown')}")
        try:
            if compare_debian_versions(item["version"], item["version"]) != 0:
                raise ValueError("non-reflexive Debian package version")
        except ValueError as error:
            raise ValueError(
                f"package lock version is invalid: {item.get('name', 'unknown')}"
            ) from error
        if lock_extended:
            pre_dependencies = item["preDepends"]
            dependencies = item["depends"]
            if (
                type(item["essential"]) is not bool
                or item.get("multiArch", "no") not in {"no", "same", "foreign", "allowed"}
                or not isinstance(pre_dependencies, list)
                or not isinstance(dependencies, list)
                or not isinstance(item["provides"], list)
                or any(not isinstance(value, str) for value in item["provides"])
                or len(set(item["provides"])) != len(item["provides"])
                or not isinstance(item["resolvedDependencies"], list)
                or any(not isinstance(name, str) or not re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", name) for name in item["resolvedDependencies"])
                or len(set(item["resolvedDependencies"])) != len(item["resolvedDependencies"])
            ):
                raise ValueError(f"package dependency metadata is invalid: {item.get('name', 'unknown')}")
            dependency_groups = pre_dependencies + dependencies
            if any(
                not isinstance(group, list)
                or not group
                or len(group) > 32
                or any(not isinstance(expression, str) for expression in group)
                or len(set(group)) != len(group)
                for group in dependency_groups
            ):
                raise ValueError(f"package dependency alternatives are invalid: {item.get('name', 'unknown')}")
            try:
                for group in dependency_groups:
                    for expression in group:
                        parsed_debian_dependency(expression, lock["architecture"])
                for expression in item["provides"]:
                    parsed_debian_dependency(
                        expression, lock["architecture"], provided=True
                    )
            except ValueError as error:
                raise ValueError(
                    f"package dependency metadata is invalid: {item.get('name', 'unknown')}: {error}"
                ) from error
    if not set(root_packages) <= names:
        raise ValueError("package lock roots are missing from its closure")
    if lock_extended:
        if ordered_names != sorted(ordered_names):
            raise ValueError("closure package records are not deterministically ordered")
        validate_dependency_closure(packages, root_packages, lock["architecture"])

    sbom_path = checked_file(root, descriptor["sbom"], 2 * 1024 * 1024)
    sbom = json.loads(sbom_path.read_text(encoding="utf-8"))
    closure_path = None
    if lock_extended:
        if package_lock.read_bytes() != canonical_json(lock):
            raise ValueError("closure package lock is not canonical JSON")
        closure_value = {
            "file": lock["closureManifestFile"],
            "sha256": lock["closureManifestSha256"],
            "size": lock["closureManifestSize"],
        }
        closure_path = checked_file(root, closure_value, 2 * 1024 * 1024)
        closure = json.loads(closure_path.read_text(encoding="utf-8"))
        expected_selected = [
            {
                "name": item["name"],
                "file": item["file"],
                "sha256": item["sha256"],
                "size": item["size"],
                "resolvedDependencies": item["resolvedDependencies"],
            }
            for item in packages
        ]
        if (
            set(closure) != {
                "schemaVersion", "architecture", "runtimeRootsSha256", "rootPackages",
                "selectedPackages", "selectedPayloadSize", "excludedPackages", "ignoredFiles",
            }
            or closure.get("schemaVersion") != 1
            or closure.get("architecture") != "aarch64"
            or closure.get("runtimeRootsSha256") != lock["runtimeRootsSha256"]
            or closure.get("rootPackages") != root_packages
            or closure.get("selectedPackages") != expected_selected
            or closure.get("selectedPayloadSize") != lock["totalSize"]
            or not isinstance(closure.get("excludedPackages"), list)
            or not isinstance(closure.get("ignoredFiles"), list)
        ):
            raise ValueError("embedded closure manifest does not match the package lock")
        excluded = closure["excludedPackages"]
        ignored = closure["ignoredFiles"]
        if (
            any(not isinstance(name, str) or not re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", name) for name in excluded)
            or any(not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+~-]*\.deb", name) for name in ignored)
        ):
            raise ValueError("embedded closure exclusions are invalid")
        if (
            excluded != sorted(set(excluded))
            or set(excluded) & names
            or ignored != sorted(set(ignored))
            or len(excluded) + len(ignored) != arm64_pin["excludedPackageCount"]
            or closure_path.read_bytes() != canonical_json(closure)
        ):
            raise ValueError("embedded closure exclusions are invalid")
        if sbom != expected_spdx(lock) or sbom_path.read_bytes() != canonical_json(sbom):
            raise ValueError("embedded SPDX SBOM does not correspond exactly to the closure lock")
    else:
        if (
            set(sbom) != {
                "spdxVersion", "dataLicense", "SPDXID", "name", "documentNamespace",
                "creationInfo", "packages", "relationships",
            }
            or sbom.get("spdxVersion") != "SPDX-2.3" or sbom.get("dataLicense") != "CC0-1.0"
            or sbom.get("SPDXID") != "SPDXRef-DOCUMENT"
            or not isinstance(sbom.get("packages"), list) or len(sbom["packages"]) != len(packages)
        ):
            raise ValueError("embedded SPDX SBOM summary is invalid")
        spdx_packages = {}
        for item in sbom["packages"]:
            if not isinstance(item, dict):
                raise ValueError("embedded SPDX SBOM package record is invalid")
            checksums = item.get("checksums")
            if (
                not isinstance(item.get("name"), str) or item["name"] in spdx_packages
                or not isinstance(checksums, list) or len(checksums) != 1
                or checksums[0].get("algorithm") != "SHA256"
                or not re.fullmatch(r"[a-f0-9]{64}", checksums[0].get("checksumValue", ""))
            ):
                raise ValueError("embedded SPDX SBOM package record is invalid")
            spdx_packages[item["name"]] = (item.get("versionInfo"), checksums[0]["checksumValue"])
        if spdx_packages != {item["name"]: (item["version"], item["sha256"]) for item in packages}:
            raise ValueError("embedded SPDX SBOM does not match the package lock")
    keys = descriptor["trustedRuntimeKeys"]
    if not isinstance(keys, list) or not keys:
        raise ValueError("trusted runtime key list is empty")
    key_ids = set()
    for item in keys:
        if set(item) != {"keyId", "file", "sha256"} or item["keyId"] in key_ids:
            raise ValueError("trusted runtime key descriptor is invalid")
        key_ids.add(item["keyId"])
        path = root / item["file"]
        if not path.is_file() or sha256(path) != item["sha256"]:
            raise ValueError("trusted runtime key asset verification failed")
        if hashlib.sha256(public_der(path)).hexdigest()[:32] != item["keyId"]:
            raise ValueError("trusted runtime key ID does not match its public key")
    expected_source_files = {
        "embedded-runtime-v1.json", descriptor["runtime"]["file"], descriptor["registry"]["file"],
        descriptor["packageLock"]["file"],
        descriptor["sbom"]["file"], *(item["file"] for item in keys),
    }
    if closure_path is not None:
        expected_source_files.add(closure_path.name)
    actual_source_files = {path.name for path in root.iterdir() if path.is_file() and not path.is_symlink()}
    if actual_source_files != expected_source_files or any(path.is_dir() or path.is_symlink() for path in root.iterdir()):
        raise ValueError("embedded runtime source directory contains stale or unsafe inputs")
    return {
        "baselineVersion": descriptor["baselineVersion"],
        "wtmuxCommit": descriptor["wtmuxCommit"],
        "contractPackageVersion": descriptor["contractPackageVersion"],
        "components": descriptor["components"],
        "registryRelease": descriptor["registry"]["sha256"][:16],
        "registryRecords": len(registry_manifest["records"]),
        "packages": len(packages),
        "payloadSize": package_value["payloadSize"],
        "trustedKeyIds": sorted(key_ids),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.directory.resolve()), sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, json.JSONDecodeError, tarfile.TarError) as error:
        raise SystemExit(f"verify-embedded-runtime: {error}")
