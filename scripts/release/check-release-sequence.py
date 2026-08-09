#!/usr/bin/env python3
"""Verify the signed shared sequence floor and create/reprove a bounded claim."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import pathlib
import re
import secrets
import stat
import subprocess
import sys
import tempfile
import time
from typing import Any

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from private_https import canonical_https_url, read_bytes
from release_candidate import validate_manifest


MAX_APP_MANIFEST = 32 * 1024
MAX_RUNTIME_MANIFEST = 64 * 1024
MAX_RESERVATION = 8 * 1024
LIVE_SEQUENCE_FLOOR = 1096
SHA256 = re.compile(r"^[a-f0-9]{64}$")
KEY_ID = re.compile(r"^[a-f0-9]{32}$")
TOKEN = re.compile(r"^[a-f0-9]{32}$")
PAYLOAD_FIELDS = {
    "schemaVersion", "sequence", "version", "protocolVersion", "artifactUrl",
    "sha256", "size", "minAppVersionCode", "createdAt",
}
RESERVATION_FIELDS = {
    "schemaVersion", "token", "versionCode", "publishedAppVersionCode",
    "runtimeSequence", "floor", "appManifestSha256", "runtimeManifestSha256",
    "runtimePayloadSha256", "runtimeKeyId",
}


class SequenceError(ValueError):
    pass


def canonical(value: object, *, newline: bool = False) -> bytes:
    payload = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    return payload + (b"\n" if newline else b"")


def _decode_base64url(value: Any, label: str, maximum: int) -> bytes:
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", value) or len(value) > maximum * 2:
        raise SequenceError(f"{label} is invalid")
    try:
        payload = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except (ValueError, base64.binascii.Error) as error:
        raise SequenceError(f"{label} is invalid") from error
    encoded = base64.urlsafe_b64encode(payload).rstrip(b"=").decode()
    if not 1 <= len(payload) <= maximum or encoded != value:
        raise SequenceError(f"{label} is not canonical base64url")
    return payload


def _positive_int(value: Any, label: str, maximum: int = 2**63 - 1) -> int:
    if type(value) is not int or not 1 <= value <= maximum:
        raise SequenceError(f"{label} is invalid")
    return value


def _read_local(path: pathlib.Path, maximum: int, *, require_private: bool = False) -> bytes:
    flags = os.O_RDONLY | os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    fd = os.open(path, flags)
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode) or not 1 <= before.st_size <= maximum:
            raise SequenceError(f"{path} is not a bounded regular file")
        if require_private and (before.st_uid != os.geteuid() or stat.S_IMODE(before.st_mode) != 0o600):
            raise SequenceError(f"{path} must be user-owned mode 0600")
        payload = os.read(fd, maximum + 1)
        if len(payload) > maximum or os.read(fd, 1):
            raise SequenceError(f"{path} exceeds its byte limit")
        after = os.fstat(fd)
        if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (
            after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns
        ):
            raise SequenceError(f"{path} changed while it was read")
        return payload
    finally:
        os.close(fd)


def read_location(location: str, maximum: int, loopback_fallback: bool = False) -> bytes:
    if location.startswith("https:"):
        canonical_https_url(location)
        return read_bytes(
            location,
            maximum=maximum,
            deadline=time.monotonic() + 60,
            loopback_fallback=loopback_fallback,
        )
    return _read_local(pathlib.Path(location), maximum)


def _json(payload: bytes, label: str) -> Any:
    try:
        return json.loads(payload.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise SequenceError(f"{label} is not valid JSON") from error


def _openssl(arguments: list[str], *, payload: bytes | None = None) -> bytes:
    try:
        result = subprocess.run(
            ["openssl", *arguments], input=payload, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, timeout=20, check=False,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise SequenceError("OpenSSL is unavailable for runtime signature verification") from error
    if result.returncode != 0:
        raise SequenceError("runtime update signature verification failed")
    return result.stdout


def _public_der(path: pathlib.Path) -> bytes:
    return _openssl(["pkey", "-pubin", "-in", str(path), "-outform", "DER"])


def _trusted_key(descriptor_path: pathlib.Path, identifier: str) -> pathlib.Path:
    descriptor_payload = _read_local(descriptor_path, 64 * 1024)
    descriptor = _json(descriptor_payload, "embedded runtime descriptor")
    if not isinstance(descriptor, dict) or not isinstance(descriptor.get("trustedRuntimeKeys"), list):
        raise SequenceError("embedded runtime trusted-key descriptor is invalid")
    matches = [item for item in descriptor["trustedRuntimeKeys"] if isinstance(item, dict) and item.get("keyId") == identifier]
    if len(matches) != 1 or set(matches[0]) != {"keyId", "file", "sha256"}:
        raise SequenceError("runtime update uses an unpinned signing key")
    record = matches[0]
    if not isinstance(record["file"], str) or not re.fullmatch(r"trusted-runtime-key-[a-f0-9]{32}\.pem", record["file"]):
        raise SequenceError("trusted runtime key file is invalid")
    if not isinstance(record["sha256"], str) or not SHA256.fullmatch(record["sha256"]):
        raise SequenceError("trusted runtime key digest is invalid")
    path = descriptor_path.parent / record["file"]
    payload = _read_local(path, 4096)
    if hashlib.sha256(payload).hexdigest() != record["sha256"]:
        raise SequenceError("trusted runtime key bytes do not match the embedded pin")
    if hashlib.sha256(_public_der(path)).hexdigest()[:32] != identifier:
        raise SequenceError("trusted runtime key ID does not match its public key")
    return path


def verify_runtime_envelope(payload: bytes, descriptor_path: pathlib.Path) -> tuple[int, str, str]:
    envelope = _json(payload, "runtime update manifest")
    if (
        not isinstance(envelope, dict)
        or set(envelope) != {"schemaVersion", "keyId", "payload", "signature"}
        or envelope.get("schemaVersion") != 1
    ):
        raise SequenceError("runtime update envelope fields are invalid")
    identifier = envelope["keyId"]
    if not isinstance(identifier, str) or not KEY_ID.fullmatch(identifier):
        raise SequenceError("runtime update key ID is invalid")
    decoded = _decode_base64url(envelope["payload"], "runtime update payload", MAX_RUNTIME_MANIFEST)
    signature = _decode_base64url(envelope["signature"], "runtime update signature", 128)
    value = _json(decoded, "runtime update payload")
    if not isinstance(value, dict) or set(value) != PAYLOAD_FIELDS or value.get("schemaVersion") != 1:
        raise SequenceError("runtime update payload fields are invalid")
    sequence = _positive_int(value["sequence"], "runtime update sequence")
    _positive_int(value["protocolVersion"], "runtime protocol", 1024)
    _positive_int(value["size"], "runtime artifact size", 32 * 1024 * 1024)
    minimum_app = _positive_int(value["minAppVersionCode"], "runtime minimum app version", 2**31 - 1)
    if sequence < minimum_app:
        raise SequenceError("runtime update sequence is below its minimum app version")
    if not isinstance(value["version"], str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,63}", value["version"]):
        raise SequenceError("runtime update version is invalid")
    canonical_https_url(value["artifactUrl"] if isinstance(value["artifactUrl"], str) else "")
    if not isinstance(value["sha256"], str) or not SHA256.fullmatch(value["sha256"]):
        raise SequenceError("runtime artifact digest is invalid")
    if not isinstance(value["createdAt"], str) or not re.fullmatch(
        r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", value["createdAt"]
    ):
        raise SequenceError("runtime update timestamp is invalid")
    if canonical(value) != decoded:
        raise SequenceError("runtime update payload is not canonical JSON")
    public_key = _trusted_key(descriptor_path, identifier)
    with tempfile.NamedTemporaryFile() as payload_file, tempfile.NamedTemporaryFile() as signature_file:
        payload_file.write(decoded)
        payload_file.flush()
        signature_file.write(signature)
        signature_file.flush()
        _openssl([
            "pkeyutl", "-verify", "-rawin", "-pubin", "-inkey", str(public_key),
            "-in", payload_file.name, "-sigfile", signature_file.name,
        ])
    return sequence, identifier, hashlib.sha256(decoded).hexdigest()


def _app_version(payload: bytes) -> int:
    manifest, _ = validate_manifest(payload)
    return _positive_int(manifest["versionCode"], "published app version code", 2**31 - 1)


def required_floor(app_manifest: dict[str, Any], runtime_manifest: dict[str, Any]) -> int:
    app_code = app_manifest.get("versionCode")
    runtime = runtime_manifest.get("sequence")
    return max(
        LIVE_SEQUENCE_FLOOR,
        _positive_int(app_code, "published app version code", 2**31 - 1),
        _positive_int(runtime, "runtime update sequence"),
    )


def make_reservation(version_code: int, app_payload: bytes, runtime_payload: bytes, descriptor: pathlib.Path) -> dict[str, Any]:
    app_code = _app_version(app_payload)
    runtime_sequence, key_id, runtime_payload_sha = verify_runtime_envelope(runtime_payload, descriptor)
    floor = max(LIVE_SEQUENCE_FLOOR, app_code, runtime_sequence)
    if version_code <= floor:
        raise SequenceError(f"version code {version_code} must be greater than published sequence {floor}")
    return {
        "schemaVersion": 1,
        "token": secrets.token_hex(16),
        "versionCode": version_code,
        "publishedAppVersionCode": app_code,
        "runtimeSequence": runtime_sequence,
        "floor": floor,
        "appManifestSha256": hashlib.sha256(app_payload).hexdigest(),
        "runtimeManifestSha256": hashlib.sha256(runtime_payload).hexdigest(),
        "runtimePayloadSha256": runtime_payload_sha,
        "runtimeKeyId": key_id,
    }


def validate_reservation(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != RESERVATION_FIELDS or value.get("schemaVersion") != 1:
        raise SequenceError("sequence reservation fields are invalid")
    if not isinstance(value["token"], str) or not TOKEN.fullmatch(value["token"]):
        raise SequenceError("sequence reservation token is invalid")
    for key in ("versionCode", "publishedAppVersionCode", "runtimeSequence", "floor"):
        _positive_int(value[key], f"sequence reservation {key}")
    if value["floor"] != max(
        LIVE_SEQUENCE_FLOOR, value["publishedAppVersionCode"], value["runtimeSequence"]
    ):
        raise SequenceError("sequence reservation floor is invalid")
    if value["versionCode"] <= value["floor"]:
        raise SequenceError("sequence reservation does not claim a newer version")
    for key in ("appManifestSha256", "runtimeManifestSha256", "runtimePayloadSha256"):
        if not isinstance(value[key], str) or not SHA256.fullmatch(value[key]):
            raise SequenceError(f"sequence reservation {key} is invalid")
    if not isinstance(value["runtimeKeyId"], str) or not KEY_ID.fullmatch(value["runtimeKeyId"]):
        raise SequenceError("sequence reservation runtime key is invalid")
    return value


def _secure_read(path: pathlib.Path, maximum: int) -> bytes:
    return _read_local(path, maximum, require_private=True)


def load_reservation(path: pathlib.Path) -> tuple[dict[str, Any], bytes]:
    payload = _secure_read(path, MAX_RESERVATION)
    value = validate_reservation(_json(payload, "sequence reservation"))
    if payload != canonical(value, newline=True):
        raise SequenceError("sequence reservation is not canonical JSON")
    return value, payload


def write_reservation(path: pathlib.Path, value: dict[str, Any]) -> None:
    payload = canonical(validate_reservation(value), newline=True)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    metadata = path.parent.stat(follow_symlinks=False)
    if metadata.st_uid != os.geteuid() or metadata.st_mode & 0o022:
        raise SequenceError("sequence reservation directory is unsafe")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    fd = os.open(path, flags, 0o600)
    try:
        os.fchmod(fd, 0o600)
        view = memoryview(payload)
        while view:
            count = os.write(fd, view)
            if count <= 0:
                raise SequenceError("short sequence reservation write")
            view = view[count:]
        os.fsync(fd)
    finally:
        os.close(fd)
    parent_fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC)
    try:
        os.fsync(parent_fd)
    finally:
        os.close(parent_fd)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version_code", type=int)
    parser.add_argument("app_manifest")
    parser.add_argument("runtime_manifest")
    parser.add_argument("--loopback-fallback", action="store_true")
    parser.add_argument("--reservation-out", type=pathlib.Path)
    parser.add_argument("--reservation", type=pathlib.Path)
    parser.add_argument(
        "--trusted-runtime-descriptor",
        type=pathlib.Path,
        default=pathlib.Path(__file__).resolve().parents[2] / "app/src/main/agent-fleet/embedded-runtime-v1.json",
    )
    args = parser.parse_args()
    if args.reservation_out and args.reservation:
        parser.error("--reservation-out and --reservation are mutually exclusive")
    try:
        app_payload = read_location(args.app_manifest, MAX_APP_MANIFEST, args.loopback_fallback)
        runtime_payload = read_location(args.runtime_manifest, MAX_RUNTIME_MANIFEST, args.loopback_fallback)
        observed = make_reservation(args.version_code, app_payload, runtime_payload, args.trusted_runtime_descriptor)
        if args.reservation:
            expected, _ = load_reservation(args.reservation)
            observed["token"] = expected["token"]
            if observed != expected:
                raise SequenceError("published app/runtime sequence changed after preflight")
            reservation = expected
        else:
            reservation = observed
            if args.reservation_out:
                write_reservation(args.reservation_out, reservation)
        print(
            f"release sequence {args.version_code} is newer than published sequence {reservation['floor']} "
            f"(reservation {reservation['token']})"
        )
        return 0
    except (OSError, SequenceError, ValueError) as error:
        print(f"check-release-sequence: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
