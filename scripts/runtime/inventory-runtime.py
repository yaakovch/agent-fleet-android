#!/usr/bin/env python3
"""Emit a deterministic, metadata-only Android runtime ownership inventory."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


ROOT_OWNERS = {
    "bash": ("shell_runtime", "interactive shell and runtime entrypoints"),
    "ca-certificates": ("transport", "HTTPS and SSH-adjacent trust bootstrap"),
    "coreutils": ("shell_runtime", "canonical runtime scripts"),
    "curl": ("update_channel", "bounded runtime and repository downloads"),
    "findutils": ("runtime_activation", "bundle verification and cleanup"),
    "git": ("repository_workflow", "repository discovery and workspace commands"),
    "grep": ("shell_runtime", "canonical runtime scripts"),
    "gzip": ("runtime_activation", "package and backup archives"),
    "openssh": ("transport", "control, PTY, and transfer SSH engine"),
    "procps": ("diagnostics", "bounded process inspection and cleanup checks"),
    "python": ("client_runtime", "bridge, contracts, scheduler, and update tools"),
    "sed": ("shell_runtime", "canonical runtime scripts"),
    "tar": ("runtime_activation", "verified runtime and migration archives"),
    "termux-tools": ("platform_adapter", "fixed-prefix Termux environment"),
}

SERVICES = {
    ".app.TermuxService": {
        "process": "application",
        "owners": ["local_pty", "workspace_terminal", "runtime_action"],
        "decision": "retain",
    },
    ".app.fleet.LocalSuggestionService": {
        "process": "local_llm",
        "owners": ["optional_local_suggestions"],
        "decision": "retain",
    },
}


def source_hits(root: Path, token: str) -> int:
    total = 0
    for path in sorted((root / "app" / "src").rglob("*")):
        if path.is_file() and path.suffix in {".kt", ".java", ".xml"}:
            try:
                total += path.read_text(encoding="utf-8").count(token)
            except UnicodeError:
                pass
    return total


def inventory(root: Path) -> dict:
    lock_path = root / "app" / "src" / "main" / "agent-fleet" / "termux-packages-aarch64.json"
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    roots = lock.get("rootPackages")
    packages = lock.get("packages")
    if not isinstance(roots, list) or set(roots) != set(ROOT_OWNERS):
        raise ValueError("runtime root-package ownership is incomplete")
    if not isinstance(packages, list) or len({item.get("name") for item in packages}) != len(packages):
        raise ValueError("runtime package lock is invalid")
    payload = sum(item.get("size", -1) for item in packages)
    if payload != lock.get("totalSize") or payload < 1:
        raise ValueError("runtime package payload size is invalid")

    manifest = (root / "app" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8")
    services = []
    for name, ownership in SERVICES.items():
        if f'android:name="{name}"' not in manifest:
            raise ValueError(f"owned Android service is missing: {name}")
        hit_token = name.rsplit(".", 1)[-1]
        hits = source_hits(root, hit_token)
        if hits < 2:
            raise ValueError(f"Android service has no source ownership evidence: {name}")
        services.append({"name": name, **ownership, "sourceReferenceCount": hits})

    return {
        "schemaVersion": 1,
        "architecture": lock.get("architecture"),
        "packageCount": len(packages),
        "packagePayloadSize": payload,
        "rootPackages": [
            {"name": name, "owner": ROOT_OWNERS[name][0], "reason": ROOT_OWNERS[name][1]}
            for name in sorted(roots)
        ],
        "services": services,
        "removalDecision": {
            "packagesRemoved": [],
            "processesRemoved": [],
            "reasonCode": "NO_UNNECESSARY_OWNER_PROVEN",
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--pretty", action="store_true")
    args = parser.parse_args()
    try:
        value = inventory(args.root.resolve())
    except (OSError, ValueError, json.JSONDecodeError) as error:
        raise SystemExit(f"inventory-runtime: {error}")
    print(json.dumps(value, indent=2 if args.pretty else None, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
