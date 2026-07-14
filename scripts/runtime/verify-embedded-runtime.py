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


def verify(root: Path) -> dict:
    descriptor_path = root / "embedded-runtime-v1.json"
    descriptor = json.loads(descriptor_path.read_text(encoding="utf-8"))
    if set(descriptor) != {
        "schemaVersion", "baselineVersion", "wtmuxCommit", "protocolVersion", "supportedAbis",
        "runtime", "packageLock", "sbom", "trustedRuntimeKeys",
    } or descriptor["schemaVersion"] != 1:
        raise ValueError("embedded-runtime-v1 fields are invalid")
    if not re.fullmatch(r"git-[a-f0-9]{7}", descriptor["baselineVersion"]):
        raise ValueError("embedded baseline version is invalid")
    if not re.fullmatch(r"[a-f0-9]{40}", descriptor["wtmuxCommit"]):
        raise ValueError("embedded wtmux commit is invalid")
    if descriptor["baselineVersion"] != "git-" + descriptor["wtmuxCommit"][:7]:
        raise ValueError("embedded baseline version and wtmux commit disagree")
    if descriptor["protocolVersion"] != 2 or descriptor["supportedAbis"] != ["arm64-v8a"]:
        raise ValueError("embedded protocol or ABI declaration is unexpected")

    runtime = checked_file(root, descriptor["runtime"], 32 * 1024 * 1024)
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
        manifest = json.load(manifest_handle)
        if manifest.get("formatVersion") != 1 or manifest.get("version") != descriptor["baselineVersion"]:
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

    package_value = descriptor["packageLock"]
    if set(package_value) != {"file", "sha256", "size", "packages", "payloadSize"}:
        raise ValueError("package lock descriptor fields are invalid")
    package_lock = checked_file(root, {key: package_value[key] for key in ("file", "sha256", "size")}, 2 * 1024 * 1024)
    lock = json.loads(package_lock.read_text(encoding="utf-8"))
    packages = lock.get("packages", [])
    if (
        lock.get("schemaVersion") != 1 or lock.get("architecture") != "aarch64"
        or len(packages) != package_value["packages"] or sum(item["size"] for item in packages) != package_value["payloadSize"]
        or lock.get("totalSize") != package_value["payloadSize"]
    ):
        raise ValueError("package lock summary does not match embedded descriptor")
    names = set()
    files = set()
    for item in packages:
        if item["name"] in names or item["file"] in files:
            raise ValueError("package lock contains a duplicate")
        names.add(item["name"])
        files.add(item["file"])
        if (
            not re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", item["name"])
            or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*\.deb", item["file"])
            or not re.fullmatch(r"[a-f0-9]{64}", item["sha256"])
            or urlsplit(item["url"]).scheme != "https"
        ):
            raise ValueError(f"package lock record is invalid: {item.get('name', 'unknown')}")

    checked_file(root, descriptor["sbom"], 2 * 1024 * 1024)
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
    return {
        "baselineVersion": descriptor["baselineVersion"],
        "wtmuxCommit": descriptor["wtmuxCommit"],
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
