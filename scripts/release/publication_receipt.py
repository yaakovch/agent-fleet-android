#!/usr/bin/env python3
"""Durable, descriptor-safe Android publication transaction receipts."""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import secrets
import stat
import sys
from typing import Any


MAX_RECEIPT_BYTES = 16 * 1024
VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+-agentfleet\.[0-9]+$")
SHA256 = re.compile(r"^[a-f0-9]{64}$")
TOKEN = re.compile(r"^[a-f0-9]{32}$")
SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,191}$")
FIELDS = {
    "schemaVersion",
    "publisher",
    "destinationSha256",
    "versionName",
    "versionCode",
    "token",
    "previousTarget",
    "newTarget",
    "previousDescriptor",
    "candidateProofSha256",
    "reservationSha256",
    "reservationToken",
    "state",
}


class ReceiptError(ValueError):
    pass


def canonical(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode() + b"\n"


def default_path(version: str) -> pathlib.Path:
    if not VERSION.fullmatch(version):
        raise ReceiptError("release version is invalid")
    state_root = os.environ.get("XDG_STATE_HOME")
    base = pathlib.Path(state_root) if state_root else pathlib.Path.home() / ".local" / "state"
    return base / "agent-fleet" / "android-releases" / f"{version}.json"


def _open_directory_tree(path: pathlib.Path, *, create: bool) -> int:
    absolute = path.absolute()
    if not absolute.is_absolute() or any(part in ("", ".", "..") for part in absolute.parts[1:]):
        raise ReceiptError("receipt directory path is invalid")
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    current = os.open("/", flags)
    try:
        for part in absolute.parts[1:]:
            try:
                next_fd = os.open(part, flags, dir_fd=current)
            except FileNotFoundError:
                if not create:
                    raise
                os.mkdir(part, 0o700, dir_fd=current)
                os.fsync(current)
                next_fd = os.open(part, flags, dir_fd=current)
            os.close(current)
            current = next_fd
        metadata = os.fstat(current)
        if metadata.st_uid != os.geteuid() or metadata.st_mode & 0o022:
            raise ReceiptError("receipt directory must be user-owned and not group/world writable")
        return current
    except BaseException:
        os.close(current)
        raise


def _positive_int(value: Any, label: str, maximum: int = 2**63 - 1) -> int:
    if type(value) is not int or not 1 <= value <= maximum:
        raise ReceiptError(f"receipt {label} is invalid")
    return value


def _target(value: Any, *, allow_absent: bool) -> str:
    if allow_absent and value == "-":
        return value
    if not isinstance(value, str):
        raise ReceiptError("receipt activation target is invalid")
    if re.fullmatch(r"releases/[0-9]+\.[0-9]+\.[0-9]+-agentfleet\.[0-9]+", value):
        return value
    if re.fullmatch(r"activations/[a-f0-9]{32}", value):
        return value
    raise ReceiptError("receipt activation target is invalid")


def _previous_descriptor(value: Any, target: str) -> dict[str, Any] | None:
    if target == "-":
        if value is not None:
            raise ReceiptError("receipt unexpectedly describes an absent previous release")
        return None
    fields = {"target", "versionName", "versionCode", "manifestSha256", "manifestSize", "artifacts"}
    if not isinstance(value, dict) or set(value) != fields or value["target"] != target:
        raise ReceiptError("receipt previous-release descriptor is invalid")
    if not isinstance(value["versionName"], str) or not VERSION.fullmatch(value["versionName"]):
        raise ReceiptError("receipt previous version is invalid")
    _positive_int(value["versionCode"], "previous version code", 2**31 - 1)
    if not isinstance(value["manifestSha256"], str) or not SHA256.fullmatch(value["manifestSha256"]):
        raise ReceiptError("receipt previous manifest digest is invalid")
    _positive_int(value["manifestSize"], "previous manifest size", 32 * 1024)
    artifacts = value["artifacts"]
    if not isinstance(artifacts, list) or len(artifacts) != 2:
        raise ReceiptError("receipt previous APK list is invalid")
    names: set[str] = set()
    abis: set[str] = set()
    for artifact in artifacts:
        if not isinstance(artifact, dict) or set(artifact) != {"abi", "name", "sha256", "size"}:
            raise ReceiptError("receipt previous APK descriptor is invalid")
        if artifact["abi"] not in {"arm64-v8a", "universal"}:
            raise ReceiptError("receipt previous APK ABI is invalid")
        if not isinstance(artifact["name"], str) or not SAFE_NAME.fullmatch(artifact["name"]) or not artifact["name"].endswith(".apk"):
            raise ReceiptError("receipt previous APK name is invalid")
        if artifact["name"] in names or artifact["abi"] in abis:
            raise ReceiptError("receipt previous APK descriptor is duplicated")
        names.add(artifact["name"])
        abis.add(artifact["abi"])
        if not isinstance(artifact["sha256"], str) or not SHA256.fullmatch(artifact["sha256"]):
            raise ReceiptError("receipt previous APK digest is invalid")
        _positive_int(artifact["size"], "previous APK size", 300 * 1024 * 1024)
    return value


def validate(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != FIELDS or value["schemaVersion"] != 2:
        raise ReceiptError("publication receipt fields are invalid")
    if value["publisher"] not in {"primary", "fallback"}:
        raise ReceiptError("publication receipt publisher is invalid")
    if not isinstance(value["destinationSha256"], str) or not SHA256.fullmatch(value["destinationSha256"]):
        raise ReceiptError("publication receipt destination digest is invalid")
    if not isinstance(value["versionName"], str) or not VERSION.fullmatch(value["versionName"]):
        raise ReceiptError("publication receipt version is invalid")
    _positive_int(value["versionCode"], "version code", 2**31 - 1)
    for key in ("token", "reservationToken"):
        if not isinstance(value[key], str) or not TOKEN.fullmatch(value[key]):
            raise ReceiptError(f"publication receipt {key} is invalid")
    for key in ("candidateProofSha256", "reservationSha256"):
        if not isinstance(value[key], str) or not SHA256.fullmatch(value[key]):
            raise ReceiptError(f"publication receipt {key} is invalid")
    previous = _target(value["previousTarget"], allow_absent=True)
    new = _target(value["newTarget"], allow_absent=False)
    if new != f"activations/{value['token']}":
        raise ReceiptError("publication receipt activation does not match its token")
    _previous_descriptor(value["previousDescriptor"], previous)
    if value["state"] not in {"prepared", "switched", "rolled_back"}:
        raise ReceiptError("publication receipt state is invalid")
    return value


def read(path: pathlib.Path) -> tuple[dict[str, Any], bytes]:
    parent_fd = _open_directory_tree(path.parent, create=False)
    flags = os.O_RDONLY | os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        fd = os.open(path.name, flags, dir_fd=parent_fd)
        try:
            before = os.fstat(fd)
            if (
                not stat.S_ISREG(before.st_mode)
                or before.st_uid != os.geteuid()
                or stat.S_IMODE(before.st_mode) != 0o600
                or not 1 <= before.st_size <= MAX_RECEIPT_BYTES
            ):
                raise ReceiptError("publication receipt must be a user-owned mode-0600 bounded regular file")
            payload = os.read(fd, MAX_RECEIPT_BYTES + 1)
            if os.read(fd, 1):
                raise ReceiptError("publication receipt is too large")
            after = os.fstat(fd)
            if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (
                after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns
            ):
                raise ReceiptError("publication receipt changed while it was read")
        finally:
            os.close(fd)
    finally:
        os.close(parent_fd)
    try:
        value = json.loads(payload.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ReceiptError("publication receipt is not valid JSON") from error
    receipt = validate(value)
    if payload != canonical(receipt):
        raise ReceiptError("publication receipt is not canonical JSON")
    return receipt, payload


def write(path: pathlib.Path, value: dict[str, Any]) -> None:
    receipt = validate(value)
    payload = canonical(receipt)
    if len(payload) > MAX_RECEIPT_BYTES:
        raise ReceiptError("publication receipt is too large")
    parent_fd = _open_directory_tree(path.parent, create=True)
    temporary = f".{path.name}.{secrets.token_hex(16)}.tmp"
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        try:
            os.stat(path.name, dir_fd=parent_fd, follow_symlinks=False)
        except FileNotFoundError:
            pass
        else:
            read(path)
        fd = os.open(temporary, flags, 0o600, dir_fd=parent_fd)
        try:
            os.fchmod(fd, 0o600)
            view = memoryview(payload)
            while view:
                written = os.write(fd, view)
                if written <= 0:
                    raise ReceiptError("short write while recording publication receipt")
                view = view[written:]
            os.fsync(fd)
        finally:
            os.close(fd)
        os.replace(temporary, path.name, src_dir_fd=parent_fd, dst_dir_fd=parent_fd)
        os.fsync(parent_fd)
    finally:
        try:
            os.unlink(temporary, dir_fd=parent_fd)
        except FileNotFoundError:
            pass
        os.close(parent_fd)


def redacted(value: dict[str, Any]) -> dict[str, Any]:
    receipt = validate(value)
    return {
        "schemaVersion": receipt["schemaVersion"],
        "publisher": receipt["publisher"],
        "versionName": receipt["versionName"],
        "versionCode": receipt["versionCode"],
        "previousTarget": receipt["previousTarget"],
        "newTarget": receipt["newTarget"],
        "state": receipt["state"],
        "destinationSha256": receipt["destinationSha256"],
        "candidateProofSha256": receipt["candidateProofSha256"],
        "reservationSha256": receipt["reservationSha256"],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    default = subparsers.add_parser("default-path")
    default.add_argument("version")
    inspect = subparsers.add_parser("inspect")
    inspect.add_argument("receipt", type=pathlib.Path)
    redact = subparsers.add_parser("redact")
    redact.add_argument("receipt", type=pathlib.Path)
    redact.add_argument("output", type=pathlib.Path)
    args = parser.parse_args()
    try:
        if args.command == "default-path":
            print(default_path(args.version))
        elif args.command == "inspect":
            print(json.dumps(read(args.receipt)[0], sort_keys=True))
        else:
            value = redacted(read(args.receipt)[0])
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_bytes(canonical(value))
        return 0
    except (OSError, ReceiptError) as error:
        print(f"publication-receipt: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
