#!/usr/bin/env python3
"""Fetch a locked Agent Fleet Termux package set with full verification."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
import urllib.request
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("lock", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    lock = json.loads(args.lock.read_text(encoding="utf-8"))
    if lock.get("schemaVersion") != 1 or lock.get("architecture") != "aarch64":
        raise SystemExit("unsupported package lock")
    args.output.mkdir(parents=True, exist_ok=True)
    expected_names = set()
    for item in lock["packages"]:
        destination = args.output / item["file"]
        expected_names.add(item["file"])
        if destination.is_file() and destination.stat().st_size == item["size"] and sha256(destination) == item["sha256"]:
            continue
        descriptor, temporary_name = tempfile.mkstemp(prefix=f".{destination.name}.", dir=args.output)
        os.close(descriptor)
        temporary = Path(temporary_name)
        try:
            request = urllib.request.Request(item["url"], headers={"User-Agent": "AgentFleetRuntimeFetch/1"})
            with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as output:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
            if temporary.stat().st_size != item["size"] or sha256(temporary) != item["sha256"]:
                raise SystemExit(f"package verification failed: {item['name']}")
            os.replace(temporary, destination)
        finally:
            temporary.unlink(missing_ok=True)
    for path in args.output.iterdir():
        if path.is_file() and path.name not in expected_names:
            path.unlink()
    print(json.dumps({"packages": len(expected_names), "output": str(args.output)}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
