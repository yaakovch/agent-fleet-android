#!/usr/bin/env python3
"""Create and consume a bounded proof for an already verified APK release."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import secrets
import stat
import sys
from typing import Any
from urllib.parse import urlsplit


PROOF_NAME = "candidate-proof-v1.json"
MAX_JSON_BYTES = 32 * 1024
MAX_APK_BYTES = 300 * 1024 * 1024
MAX_PROOF_BYTES = 16 * 1024
VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+-agentfleet\.[0-9]+$")
SHA256 = re.compile(r"^[a-f0-9]{64}$")
SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,191}$")
PROOF_FIELDS = {
    "schemaVersion",
    "applicationId",
    "versionName",
    "versionCode",
    "certificateSha256",
    "manifestSha256",
    "files",
}


class CandidateError(ValueError):
    pass


def canonical(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode() + b"\n"


def _read_fd(fd: int, maximum: int) -> bytes:
    before = os.fstat(fd)
    if not stat.S_ISREG(before.st_mode) or before.st_nlink < 1 or not 0 <= before.st_size <= maximum:
        raise CandidateError("candidate file is not a bounded regular file")
    chunks: list[bytes] = []
    remaining = maximum + 1
    while remaining:
        chunk = os.read(fd, min(1024 * 1024, remaining))
        if not chunk:
            break
        chunks.append(chunk)
        remaining -= len(chunk)
    payload = b"".join(chunks)
    after = os.fstat(fd)
    identity = (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns)
    if len(payload) > maximum or identity != (
        after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns
    ):
        raise CandidateError("candidate file changed while it was read")
    return payload


def read_regular(directory_fd: int, name: str, maximum: int) -> bytes:
    if not SAFE_NAME.fullmatch(name) or "/" in name:
        raise CandidateError("candidate file name is invalid")
    flags = os.O_RDONLY | os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        fd = os.open(name, flags, dir_fd=directory_fd)
    except OSError as error:
        raise CandidateError(f"candidate file is unavailable: {name}") from error
    try:
        return _read_fd(fd, maximum)
    finally:
        os.close(fd)


def open_directory(path: pathlib.Path) -> int:
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        return os.open(path, flags)
    except OSError as error:
        raise CandidateError("candidate directory is missing, unsafe, or a symlink") from error


def _positive_integer(value: Any, label: str, maximum: int = 2**63 - 1) -> int:
    if type(value) is not int or not 1 <= value <= maximum:
        raise CandidateError(f"{label} is invalid")
    return value


def _canonical_https_url(value: Any) -> str:
    if not isinstance(value, str) or not 1 <= len(value) <= 2048 or any(
        character.isspace()
        or character == "\\"
        or character == "\ufeff"
        or ord(character) < 32
        or 0x7F <= ord(character) <= 0x9F
        for character in value
    ) or not value.isascii() or "%" in value:
        raise CandidateError("APK URL is invalid")
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError as error:
        raise CandidateError("APK URL is invalid") from error
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.hostname != parsed.hostname.lower()
        or parsed.username is not None
        or parsed.password is not None
        or port not in (None, 443)
        or parsed.fragment
        or parsed.query
        or not parsed.path.startswith("/")
        or "/./" in parsed.path
        or "/../" in parsed.path
        or "//" in parsed.path
        or (port == 443)
    ):
        raise CandidateError("APK URL must be canonical standard-port HTTPS")
    return value


def validate_manifest(payload: bytes) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    if not 1 <= len(payload) <= MAX_JSON_BYTES:
        raise CandidateError("release manifest is empty or too large")
    try:
        manifest = json.loads(payload.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise CandidateError("release manifest is not valid JSON") from error
    fields = {
        "schemaVersion", "applicationId", "versionCode", "versionName", "apkUrl",
        "apkSha256", "certificateSha256", "size", "createdAt", "gitCommit", "artifacts",
    }
    if not isinstance(manifest, dict) or set(manifest) != fields:
        raise CandidateError("release manifest fields are invalid")
    if manifest["schemaVersion"] != 1 or manifest["applicationId"] != "com.yaakovch.fleet":
        raise CandidateError("release manifest identity is invalid")
    if not isinstance(manifest["versionName"], str) or not VERSION.fullmatch(manifest["versionName"]):
        raise CandidateError("release version is invalid")
    _positive_integer(manifest["versionCode"], "release version code", 2**31 - 1)
    if not isinstance(manifest["certificateSha256"], str) or not SHA256.fullmatch(manifest["certificateSha256"]):
        raise CandidateError("release certificate digest is invalid")
    if not isinstance(manifest["gitCommit"], str) or not re.fullmatch(r"[a-f0-9]{40}", manifest["gitCommit"]):
        raise CandidateError("release source commit is invalid")
    if not isinstance(manifest["createdAt"], str) or not re.fullmatch(
        r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?Z",
        manifest["createdAt"],
    ):
        raise CandidateError("release creation time is invalid")
    artifacts = manifest["artifacts"]
    if not isinstance(artifacts, list) or len(artifacts) != 2:
        raise CandidateError("release must describe exactly two APKs")
    result: list[dict[str, Any]] = []
    names: set[str] = set()
    for item in artifacts:
        if not isinstance(item, dict) or set(item) != {"abi", "apkUrl", "apkSha256", "size"}:
            raise CandidateError("APK descriptor fields are invalid")
        if item["abi"] not in {"arm64-v8a", "universal"}:
            raise CandidateError("APK ABI is invalid")
        url = _canonical_https_url(item["apkUrl"])
        name = pathlib.PurePosixPath(urlsplit(url).path).name
        if not SAFE_NAME.fullmatch(name) or not name.endswith(".apk") or name in names:
            raise CandidateError("APK file name is invalid or duplicated")
        names.add(name)
        if not isinstance(item["apkSha256"], str) or not SHA256.fullmatch(item["apkSha256"]):
            raise CandidateError("APK digest is invalid")
        _positive_integer(item["size"], "APK size", MAX_APK_BYTES)
        result.append({**item, "name": name})
    if {item["abi"] for item in result} != {"arm64-v8a", "universal"}:
        raise CandidateError("release must describe arm64 and universal APKs")
    primary = next(item for item in result if item["abi"] == "arm64-v8a")
    if (manifest["apkUrl"], manifest["apkSha256"], manifest["size"]) != (
        primary["apkUrl"], primary["apkSha256"], primary["size"]
    ):
        raise CandidateError("primary release fields do not select the arm64 APK")
    origins = {(urlsplit(item["apkUrl"]).scheme, urlsplit(item["apkUrl"]).netloc) for item in result}
    parents = {str(pathlib.PurePosixPath(urlsplit(item["apkUrl"]).path).parent) for item in result}
    if len(origins) != 1 or len(parents) != 1:
        raise CandidateError("APK URLs must share one exact HTTPS release directory")
    return manifest, result


def _hash_record(directory_fd: int, name: str, expected_size: int | None = None) -> dict[str, Any]:
    maximum = MAX_APK_BYTES if name.endswith(".apk") else MAX_JSON_BYTES
    payload = read_regular(directory_fd, name, maximum)
    if expected_size is not None and len(payload) != expected_size:
        raise CandidateError(f"candidate size mismatch: {name}")
    return {"name": name, "sha256": hashlib.sha256(payload).hexdigest(), "size": len(payload)}


def create_proof(directory: pathlib.Path, expected_certificate: str) -> dict[str, Any]:
    if not SHA256.fullmatch(expected_certificate):
        raise CandidateError("pinned certificate fingerprint is invalid")
    directory_fd = open_directory(directory)
    try:
        manifest_payload = read_regular(directory_fd, "manifest.json", MAX_JSON_BYTES)
        manifest, artifacts = validate_manifest(manifest_payload)
        if manifest["certificateSha256"] != expected_certificate:
            raise CandidateError("release manifest certificate does not match the pinned certificate")
        records = [_hash_record(directory_fd, "manifest.json")]
        for artifact in sorted(artifacts, key=lambda value: value["abi"]):
            record = _hash_record(directory_fd, artifact["name"], artifact["size"])
            if record["sha256"] != artifact["apkSha256"]:
                raise CandidateError(f"candidate digest mismatch: {artifact['name']}")
            records.append(record)
        sums = read_regular(directory_fd, "SHA256SUMS", MAX_JSON_BYTES)
        expected_lines = {
            f"{record['sha256']}  {record['name']}" for record in records
        }
        try:
            actual_lines = set(sums.decode("utf-8").splitlines())
        except UnicodeError as error:
            raise CandidateError("SHA256SUMS is not UTF-8") from error
        if actual_lines != expected_lines or len(actual_lines) != len(records):
            raise CandidateError("SHA256SUMS does not exactly describe the publishable files")
        records.append({"name": "SHA256SUMS", "sha256": hashlib.sha256(sums).hexdigest(), "size": len(sums)})
        proof = {
            "schemaVersion": 1,
            "applicationId": "com.yaakovch.fleet",
            "versionName": manifest["versionName"],
            "versionCode": manifest["versionCode"],
            "certificateSha256": expected_certificate,
            "manifestSha256": hashlib.sha256(manifest_payload).hexdigest(),
            "files": sorted(records, key=lambda value: value["name"]),
        }
        payload = canonical(proof)
        if len(payload) > MAX_PROOF_BYTES:
            raise CandidateError("candidate proof is too large")
        allowed = {record["name"] for record in records} | {
            PROOF_NAME, "apksigner-arm64.txt", "apksigner-universal.txt",
        }
        allowed |= {name + ".idsig" for name in allowed if name.endswith(".apk")}
        actual = set(os.listdir(directory_fd))
        required = {record["name"] for record in records}
        if not required <= actual or not actual <= allowed:
            raise CandidateError("candidate directory contains an unexpected entry")
        for name in actual - required - {PROOF_NAME}:
            maximum = MAX_APK_BYTES if name.endswith((".apk", ".idsig")) else MAX_JSON_BYTES
            read_regular(directory_fd, name, maximum)
        temporary = f".{PROOF_NAME}.{secrets.token_hex(8)}.tmp"
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        fd = os.open(temporary, flags, 0o600, dir_fd=directory_fd)
        try:
            os.fchmod(fd, 0o600)
            view = memoryview(payload)
            while view:
                count = os.write(fd, view)
                if count <= 0:
                    raise CandidateError("short candidate proof write")
                view = view[count:]
            os.fsync(fd)
        finally:
            os.close(fd)
        try:
            os.replace(temporary, PROOF_NAME, src_dir_fd=directory_fd, dst_dir_fd=directory_fd)
            os.fsync(directory_fd)
        except BaseException:
            try:
                os.unlink(temporary, dir_fd=directory_fd)
            except FileNotFoundError:
                pass
            raise
        return proof
    finally:
        os.close(directory_fd)


def validate_proof(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != PROOF_FIELDS:
        raise CandidateError("candidate proof fields are invalid")
    if value["schemaVersion"] != 1 or value["applicationId"] != "com.yaakovch.fleet":
        raise CandidateError("candidate proof identity is invalid")
    if not isinstance(value["versionName"], str) or not VERSION.fullmatch(value["versionName"]):
        raise CandidateError("candidate proof version is invalid")
    _positive_integer(value["versionCode"], "candidate proof version code", 2**31 - 1)
    for key in ("certificateSha256", "manifestSha256"):
        if not isinstance(value[key], str) or not SHA256.fullmatch(value[key]):
            raise CandidateError(f"candidate proof {key} is invalid")
    files = value["files"]
    if not isinstance(files, list) or len(files) != 4:
        raise CandidateError("candidate proof must allow exactly four files")
    names: list[str] = []
    for item in files:
        if not isinstance(item, dict) or set(item) != {"name", "sha256", "size"}:
            raise CandidateError("candidate proof file record is invalid")
        if not isinstance(item["name"], str) or not SAFE_NAME.fullmatch(item["name"]):
            raise CandidateError("candidate proof file name is invalid")
        if not isinstance(item["sha256"], str) or not SHA256.fullmatch(item["sha256"]):
            raise CandidateError("candidate proof file digest is invalid")
        maximum = MAX_APK_BYTES if item["name"].endswith(".apk") else MAX_JSON_BYTES
        _positive_integer(item["size"], "candidate proof file size", maximum)
        names.append(item["name"])
    if names != sorted(names) or len(set(names)) != len(names) or names.count("manifest.json") != 1 or names.count("SHA256SUMS") != 1:
        raise CandidateError("candidate proof file allowlist is invalid")
    if sum(name.endswith(".apk") for name in names) != 2:
        raise CandidateError("candidate proof must allow exactly two APKs")
    manifest_record = next(item for item in files if item["name"] == "manifest.json")
    if manifest_record["sha256"] != value["manifestSha256"]:
        raise CandidateError("candidate proof manifest binding is invalid")
    return value


def load_proof(directory: pathlib.Path) -> tuple[dict[str, Any], bytes]:
    directory_fd = open_directory(directory)
    try:
        payload = read_regular(directory_fd, PROOF_NAME, MAX_PROOF_BYTES)
        try:
            value = json.loads(payload.decode("utf-8"))
        except (UnicodeError, json.JSONDecodeError) as error:
            raise CandidateError("candidate proof is not valid JSON") from error
        proof = validate_proof(value)
        if payload != canonical(proof):
            raise CandidateError("candidate proof is not canonical JSON")
        for record in proof["files"]:
            observed = _hash_record(directory_fd, record["name"], record["size"])
            if observed != record:
                raise CandidateError(f"candidate no longer matches its proof: {record['name']}")
        manifest_payload = read_regular(directory_fd, "manifest.json", MAX_JSON_BYTES)
        manifest, _ = validate_manifest(manifest_payload)
        if (
            manifest["versionName"] != proof["versionName"]
            or manifest["versionCode"] != proof["versionCode"]
            or manifest["certificateSha256"] != proof["certificateSha256"]
        ):
            raise CandidateError("candidate proof does not match the manifest identity")
        allowed = {record["name"] for record in proof["files"]} | {PROOF_NAME}
        local_only = {"apksigner-arm64.txt", "apksigner-universal.txt"}
        local_only |= {name + ".idsig" for name in allowed if name.endswith(".apk")}
        actual = set(os.listdir(directory_fd))
        if not actual <= allowed | local_only or not allowed <= actual:
            raise CandidateError("candidate directory contains an unexpected entry")
        for name in actual:
            read_regular(directory_fd, name, MAX_APK_BYTES if name.endswith((".apk", ".idsig")) else MAX_JSON_BYTES)
        return proof, payload
    finally:
        os.close(directory_fd)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    prove = subparsers.add_parser("prove")
    prove.add_argument("directory", type=pathlib.Path)
    prove.add_argument("certificate_sha256")
    inspect = subparsers.add_parser("inspect")
    inspect.add_argument("directory", type=pathlib.Path)
    args = parser.parse_args()
    try:
        if args.command == "prove":
            value = create_proof(args.directory, args.certificate_sha256)
        else:
            value, _ = load_proof(args.directory)
        print(json.dumps(value, sort_keys=True))
        return 0
    except (CandidateError, OSError) as error:
        print(f"release-candidate: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
