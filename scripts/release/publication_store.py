#!/usr/bin/env python3
"""Durable publication-store CAS operations, suitable for local or SSH stdin use."""

from __future__ import annotations

import argparse
import base64
import fcntl
import hashlib
import json
import os
import pathlib
import re
import stat
import subprocess
import sys
import tempfile
import time
from typing import Any
from urllib.parse import urlsplit


VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+-agentfleet\.[0-9]+$")
TOKEN = re.compile(r"^[a-f0-9]{32}$")
SHA256 = re.compile(r"^[a-f0-9]{64}$")
COMMIT = re.compile(r"^[a-f0-9]{40}$")
SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,191}$")
LEGACY_TARGET = re.compile(r"^releases/([0-9]+\.[0-9]+\.[0-9]+-agentfleet\.[0-9]+)$")
ACTIVATION_TARGET = re.compile(r"^activations/([a-f0-9]{32})$")
PROOF_NAME = "candidate-proof-v1.json"
MAX_MANIFEST = 32 * 1024
MAX_PROOF = 16 * 1024
MAX_APK = 300 * 1024 * 1024
LOCK_TIMEOUT = 30.0
LIVE_SEQUENCE_FLOOR = 1096
PRODUCTION_CERTIFICATE_SHA256 = "c5b2539c028ae1dc539ded3113cc3c735f60145bbfc5e4a8254056452db5e873"
RUNTIME_KEY_ID = "ef1aa26c21be89f9ac220e41ae28a865"
RUNTIME_KEY_SHA256 = "a3500746ab5f70c708741dd8f3c41b0dc66fabb7a47b43b726bceb6cf9108364"
RUNTIME_PUBLIC_KEY = b"""-----BEGIN PUBLIC KEY-----
MCowBQYDK2VwAyEAf3Vg2lizbyEw0Wwna3dKj+wvBgBQ+sHhD0niWKq+gYA=
-----END PUBLIC KEY-----
"""
RUNTIME_PAYLOAD_FIELDS = {
    "schemaVersion", "sequence", "version", "protocolVersion", "artifactUrl",
    "sha256", "size", "minAppVersionCode", "createdAt",
}


class StoreError(ValueError):
    pass


def canonical(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode() + b"\n"


def canonical_inline(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def _flags_directory() -> int:
    value = os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        value |= os.O_NOFOLLOW
    return value


def _flags_read() -> int:
    value = os.O_RDONLY | os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        value |= os.O_NOFOLLOW
    return value


def open_absolute_directory(path: str, *, create: bool) -> int:
    if not path.startswith("/") or "\n" in path or "\r" in path:
        raise StoreError("publication base must be an absolute single-line path")
    parts = path.split("/")[1:]
    if not parts or any(not part or part in (".", "..") or not re.fullmatch(r"[A-Za-z0-9._-]+", part) for part in parts):
        raise StoreError("publication base contains an unsafe path component")
    flags = _flags_directory()
    current = os.open("/", flags)
    try:
        for part in parts:
            try:
                child = os.open(part, flags, dir_fd=current)
            except FileNotFoundError:
                if not create:
                    raise
                os.mkdir(part, 0o755, dir_fd=current)
                os.fsync(current)
                child = os.open(part, flags, dir_fd=current)
            os.close(current)
            current = child
        metadata = os.fstat(current)
        if metadata.st_uid != os.geteuid() or metadata.st_mode & 0o022:
            raise StoreError("publication base must be user-owned and not group/world writable")
        return current
    except BaseException:
        os.close(current)
        raise


def open_or_create_directory(parent_fd: int, name: str, mode: int = 0o755) -> int:
    if not re.fullmatch(r"[A-Za-z0-9._-]+", name) or name in (".", ".."):
        raise StoreError("publication directory name is unsafe")
    try:
        descriptor = os.open(name, _flags_directory(), dir_fd=parent_fd)
    except FileNotFoundError:
        os.mkdir(name, mode, dir_fd=parent_fd)
        os.fsync(parent_fd)
        descriptor = os.open(name, _flags_directory(), dir_fd=parent_fd)
    metadata = os.fstat(descriptor)
    if metadata.st_uid != os.geteuid() or metadata.st_mode & 0o022:
        os.close(descriptor)
        raise StoreError("managed publication directory must be user-owned and not group/world writable")
    return descriptor


def acquire_lock(directory_fd: int, timeout: float = LOCK_TIMEOUT) -> None:
    deadline = time.monotonic() + timeout
    while True:
        try:
            fcntl.flock(directory_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return
        except BlockingIOError:
            if time.monotonic() >= deadline:
                raise StoreError("publication lock wait exceeded 30 seconds")
            time.sleep(0.05)


def read_regular(directory_fd: int, name: str, maximum: int, *, durable: bool = False) -> bytes:
    if not SAFE_NAME.fullmatch(name) or "/" in name:
        raise StoreError("publication file name is unsafe")
    fd = os.open(name, _flags_read(), dir_fd=directory_fd)
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode) or before.st_nlink < 1 or not 0 <= before.st_size <= maximum:
            raise StoreError(f"publication file is not a bounded regular file: {name}")
        chunks: list[bytes] = []
        observed = 0
        while observed <= maximum:
            chunk = os.read(fd, min(1024 * 1024, maximum + 1 - observed))
            if not chunk:
                break
            chunks.append(chunk)
            observed += len(chunk)
        if observed > maximum:
            raise StoreError(f"publication file exceeds its byte limit: {name}")
        if durable:
            os.fsync(fd)
        after = os.fstat(fd)
        if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (
            after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns
        ):
            raise StoreError(f"publication file changed while it was read: {name}")
        return b"".join(chunks)
    finally:
        os.close(fd)


def read_json_file(directory_fd: int, name: str, maximum: int) -> tuple[Any, bytes]:
    payload = read_regular(directory_fd, name, maximum)
    try:
        return json.loads(payload.decode("utf-8")), payload
    except (UnicodeError, json.JSONDecodeError) as error:
        raise StoreError(f"publication JSON is invalid: {name}") from error


def atomic_write(directory_fd: int, name: str, payload: bytes, mode: int = 0o600) -> None:
    temporary = f".{name}.{os.getpid()}.{time.monotonic_ns()}.tmp"
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    fd = os.open(temporary, flags, mode, dir_fd=directory_fd)
    try:
        view = memoryview(payload)
        while view:
            count = os.write(fd, view)
            if count <= 0:
                raise StoreError("short publication metadata write")
            view = view[count:]
        os.fsync(fd)
    finally:
        os.close(fd)
    try:
        os.replace(temporary, name, src_dir_fd=directory_fd, dst_dir_fd=directory_fd)
        os.fsync(directory_fd)
    except BaseException:
        try:
            os.unlink(temporary, dir_fd=directory_fd)
        except FileNotFoundError:
            pass
        raise


def _positive_int(value: Any, label: str, maximum: int = 2**63 - 1) -> int:
    if type(value) is not int or not 1 <= value <= maximum:
        raise StoreError(f"{label} is invalid")
    return value


def validate_proof(value: Any, payload: bytes) -> dict[str, Any]:
    fields = {
        "schemaVersion", "applicationId", "versionName", "versionCode",
        "certificateSha256", "manifestSha256", "files",
    }
    if not isinstance(value, dict) or set(value) != fields or value.get("schemaVersion") != 1:
        raise StoreError("candidate proof fields are invalid")
    if value["applicationId"] != "com.yaakovch.fleet" or not isinstance(value["versionName"], str) or not VERSION.fullmatch(value["versionName"]):
        raise StoreError("candidate proof identity is invalid")
    _positive_int(value["versionCode"], "candidate version code", 2**31 - 1)
    for key in ("certificateSha256", "manifestSha256"):
        if not isinstance(value[key], str) or not SHA256.fullmatch(value[key]):
            raise StoreError("candidate proof digest is invalid")
    if value["certificateSha256"] != PRODUCTION_CERTIFICATE_SHA256:
        raise StoreError("candidate proof certificate does not match the protected production signer")
    files = value["files"]
    if not isinstance(files, list) or len(files) != 4:
        raise StoreError("candidate proof file allowlist is invalid")
    names: list[str] = []
    for record in files:
        if not isinstance(record, dict) or set(record) != {"name", "sha256", "size"}:
            raise StoreError("candidate proof file record is invalid")
        name = record["name"]
        if not isinstance(name, str) or not SAFE_NAME.fullmatch(name):
            raise StoreError("candidate proof file name is invalid")
        if not isinstance(record["sha256"], str) or not SHA256.fullmatch(record["sha256"]):
            raise StoreError("candidate proof file digest is invalid")
        maximum = MAX_APK if name.endswith(".apk") else MAX_MANIFEST
        _positive_int(record["size"], "candidate proof file size", maximum)
        names.append(name)
    if names != sorted(names) or len(set(names)) != 4 or PROOF_NAME in names:
        raise StoreError("candidate proof allowlist ordering is invalid")
    if names.count("manifest.json") != 1 or names.count("SHA256SUMS") != 1 or sum(name.endswith(".apk") for name in names) != 2:
        raise StoreError("candidate proof must bind a manifest, checksums, and exactly two APKs")
    manifest = next(record for record in files if record["name"] == "manifest.json")
    if manifest["sha256"] != value["manifestSha256"] or payload != canonical(value):
        raise StoreError("candidate proof is non-canonical or has an invalid manifest binding")
    return value


def validate_staging(staging_fd: int, version: str) -> tuple[dict[str, Any], str]:
    proof_value, proof_payload = read_json_file(staging_fd, PROOF_NAME, MAX_PROOF)
    proof = validate_proof(proof_value, proof_payload)
    if proof["versionName"] != version:
        raise StoreError("staged proof version does not match the requested release")
    expected = {PROOF_NAME, *(record["name"] for record in proof["files"])}
    actual = set(os.listdir(staging_fd))
    if actual != expected:
        raise StoreError("staged release tree is not the exact verified allowlist")
    for record in proof["files"]:
        maximum = MAX_APK if record["name"].endswith(".apk") else MAX_MANIFEST
        payload = read_regular(staging_fd, record["name"], maximum, durable=True)
        if len(payload) != record["size"] or hashlib.sha256(payload).hexdigest() != record["sha256"]:
            raise StoreError(f"staged release no longer matches its proof: {record['name']}")
    read_regular(staging_fd, PROOF_NAME, MAX_PROOF, durable=True)
    os.fsync(staging_fd)
    return proof, hashlib.sha256(proof_payload).hexdigest()


def validate_manifest(payload: bytes) -> dict[str, Any]:
    try:
        value = json.loads(payload.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise StoreError("release manifest is invalid JSON") from error
    required = {"schemaVersion", "applicationId", "versionName", "versionCode", "artifacts"}
    if not isinstance(value, dict) or not required <= set(value) or value.get("schemaVersion") != 1 or value.get("applicationId") != "com.yaakovch.fleet":
        raise StoreError("release manifest identity is invalid")
    if not isinstance(value.get("versionName"), str) or not VERSION.fullmatch(value["versionName"]):
        raise StoreError("release manifest version is invalid")
    _positive_int(value.get("versionCode"), "release manifest version code", 2**31 - 1)
    artifacts = value.get("artifacts")
    if not isinstance(artifacts, list) or len(artifacts) != 2:
        raise StoreError("release manifest must contain exactly two APKs")
    return value


def release_descriptor(target: str, release_fd: int) -> dict[str, Any]:
    payload = read_regular(release_fd, "manifest.json", MAX_MANIFEST)
    manifest = validate_manifest(payload)
    artifacts: list[dict[str, Any]] = []
    seen: set[str] = set()
    for item in manifest["artifacts"]:
        if not isinstance(item, dict) or set(item) != {"abi", "apkUrl", "apkSha256", "size"}:
            raise StoreError("release APK descriptor is invalid")
        if item["abi"] not in {"arm64-v8a", "universal"} or item["abi"] in seen:
            raise StoreError("release APK ABI is invalid")
        seen.add(item["abi"])
        try:
            name = pathlib.PurePosixPath(urlsplit(item["apkUrl"]).path).name
        except (TypeError, ValueError) as error:
            raise StoreError("release APK URL is invalid") from error
        if not SAFE_NAME.fullmatch(name) or not name.endswith(".apk"):
            raise StoreError("release APK name is invalid")
        if not isinstance(item["apkSha256"], str) or not SHA256.fullmatch(item["apkSha256"]):
            raise StoreError("release APK digest is invalid")
        size = _positive_int(item["size"], "release APK size", MAX_APK)
        apk = read_regular(release_fd, name, MAX_APK)
        if len(apk) != size or hashlib.sha256(apk).hexdigest() != item["apkSha256"]:
            raise StoreError("release APK bytes do not match the manifest")
        artifacts.append({"abi": item["abi"], "name": name, "sha256": item["apkSha256"], "size": size})
    return {
        "target": target,
        "versionName": manifest["versionName"],
        "versionCode": manifest["versionCode"],
        "manifestSha256": hashlib.sha256(payload).hexdigest(),
        "manifestSize": len(payload),
        "artifacts": sorted(artifacts, key=lambda item: item["abi"]),
    }


def open_release_for_target(fleet_fd: int, releases_fd: int, activations_fd: int, target: str) -> int:
    legacy = LEGACY_TARGET.fullmatch(target)
    activation = ACTIVATION_TARGET.fullmatch(target)
    if legacy:
        version = legacy.group(1)
    elif activation:
        activation_name = activation.group(1)
        try:
            activation_value = os.readlink(activation_name, dir_fd=activations_fd)
        except OSError as error:
            raise StoreError("publication activation is missing or unsafe") from error
        match = re.fullmatch(r"\.\./releases/([0-9]+\.[0-9]+\.[0-9]+-agentfleet\.[0-9]+)", activation_value)
        if not match:
            raise StoreError("publication activation target is invalid")
        version = match.group(1)
    else:
        raise StoreError("publication target is invalid")
    try:
        return os.open(version, _flags_directory(), dir_fd=releases_fd)
    except OSError as error:
        raise StoreError("publication release target is missing or unsafe") from error


def current_target(fleet_fd: int, releases_fd: int, activations_fd: int) -> tuple[str, dict[str, Any] | None]:
    try:
        target = os.readlink("latest", dir_fd=fleet_fd)
    except FileNotFoundError:
        try:
            os.stat("latest", dir_fd=fleet_fd, follow_symlinks=False)
        except FileNotFoundError:
            return "-", None
        raise StoreError("latest is not a symbolic link")
    except OSError as error:
        raise StoreError("latest is not a readable symbolic link") from error
    release_fd = open_release_for_target(fleet_fd, releases_fd, activations_fd, target)
    try:
        return target, release_descriptor(target, release_fd)
    finally:
        os.close(release_fd)


def switch_latest(fleet_fd: int, target: str) -> None:
    if not (LEGACY_TARGET.fullmatch(target) or ACTIVATION_TARGET.fullmatch(target)):
        raise StoreError("refusing an invalid latest target")
    temporary = f".latest.{os.getpid()}.{time.monotonic_ns()}"
    os.symlink(target, temporary, dir_fd=fleet_fd)
    try:
        os.replace(temporary, "latest", src_dir_fd=fleet_fd, dst_dir_fd=fleet_fd)
        os.fsync(fleet_fd)
    except BaseException:
        try:
            os.unlink(temporary, dir_fd=fleet_fd)
        except FileNotFoundError:
            pass
        raise


def remove_latest(fleet_fd: int) -> None:
    os.unlink("latest", dir_fd=fleet_fd)
    os.fsync(fleet_fd)


def store_handles(base: str, *, create: bool = True) -> tuple[int, int, int, int]:
    base_fd = open_absolute_directory(base, create=create)
    try:
        fleet_fd = open_or_create_directory(base_fd, "fleet")
        releases_fd = open_or_create_directory(fleet_fd, "releases")
        activations_fd = open_or_create_directory(fleet_fd, "activations")
        return base_fd, fleet_fd, releases_fd, activations_fd
    except BaseException:
        os.close(base_fd)
        raise


def close_handles(handles: tuple[int, ...]) -> None:
    for descriptor in reversed(handles):
        os.close(descriptor)


def staging_name(version: str, token: str) -> str:
    if not VERSION.fullmatch(version) or not TOKEN.fullmatch(token):
        raise StoreError("publication version or token is invalid")
    return f".staging-{version}-{token}"


def command_prepare(base: str, version: str, token: str) -> None:
    handles = store_handles(base)
    _, _, releases_fd, _ = handles
    try:
        acquire_lock(handles[0])
        name = staging_name(version, token)
        try:
            fd = os.open(name, _flags_directory(), dir_fd=releases_fd)
        except FileNotFoundError:
            os.mkdir(name, 0o700, dir_fd=releases_fd)
            os.fsync(releases_fd)
            fd = os.open(name, _flags_directory(), dir_fd=releases_fd)
        try:
            if stat.S_IMODE(os.fstat(fd).st_mode) & 0o077:
                raise StoreError("staging directory permissions are unsafe")
        finally:
            os.close(fd)
    finally:
        close_handles(handles)


def _trees_match(left_fd: int, right_fd: int) -> bool:
    left_names = sorted(os.listdir(left_fd))
    right_names = sorted(os.listdir(right_fd))
    if left_names != right_names:
        return False
    for name in left_names:
        maximum = MAX_APK if name.endswith(".apk") else MAX_MANIFEST
        if read_regular(left_fd, name, maximum) != read_regular(right_fd, name, maximum):
            return False
    return True


def _remove_staging(releases_fd: int, name: str) -> None:
    fd = os.open(name, _flags_directory(), dir_fd=releases_fd)
    try:
        for entry in os.listdir(fd):
            metadata = os.stat(entry, dir_fd=fd, follow_symlinks=False)
            if not stat.S_ISREG(metadata.st_mode):
                raise StoreError("refusing to remove a non-regular staging entry")
            os.unlink(entry, dir_fd=fd)
        os.fsync(fd)
    finally:
        os.close(fd)
    os.rmdir(name, dir_fd=releases_fd)
    os.fsync(releases_fd)


def _decode_base64url(value: Any, label: str, maximum: int) -> bytes:
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", value) or len(value) > maximum * 2:
        raise StoreError(f"{label} is invalid")
    try:
        payload = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except (ValueError, base64.binascii.Error) as error:
        raise StoreError(f"{label} is invalid") from error
    encoded = base64.urlsafe_b64encode(payload).rstrip(b"=").decode()
    if not 1 <= len(payload) <= maximum or encoded != value:
        raise StoreError(f"{label} is not canonical base64url")
    return payload


def _canonical_https_url(value: Any) -> str:
    if not isinstance(value, str) or not 1 <= len(value) <= 2048 or any(
        character.isspace()
        or character == "\\"
        or character == "\ufeff"
        or ord(character) < 32
        or 0x7F <= ord(character) <= 0x9F
        for character in value
    ) or not value.isascii() or "%" in value:
        raise StoreError("runtime artifact URL is invalid")
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError as error:
        raise StoreError("runtime artifact URL is invalid") from error
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.hostname != parsed.hostname.lower()
        or parsed.username is not None
        or parsed.password is not None
        or port is not None
        or parsed.fragment
        or parsed.query
        or not parsed.path.startswith("/")
        or "/./" in parsed.path
        or "/../" in parsed.path
        or "//" in parsed.path
    ):
        raise StoreError("runtime artifact URL must be canonical standard-port HTTPS")
    return value


def verify_runtime_envelope(payload: bytes) -> int:
    try:
        envelope = json.loads(payload.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise StoreError("runtime update envelope is invalid JSON") from error
    if (
        not isinstance(envelope, dict)
        or set(envelope) != {"schemaVersion", "keyId", "payload", "signature"}
        or envelope.get("schemaVersion") != 1
        or envelope.get("keyId") != RUNTIME_KEY_ID
    ):
        raise StoreError("runtime update envelope identity is invalid")
    decoded = _decode_base64url(envelope["payload"], "runtime update payload", 64 * 1024)
    signature = _decode_base64url(envelope["signature"], "runtime update signature", 128)
    try:
        value = json.loads(decoded.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise StoreError("runtime update payload is invalid JSON") from error
    if not isinstance(value, dict) or set(value) != RUNTIME_PAYLOAD_FIELDS or value.get("schemaVersion") != 1:
        raise StoreError("runtime update payload fields are invalid")
    sequence = _positive_int(value.get("sequence"), "runtime update sequence")
    _positive_int(value.get("protocolVersion"), "runtime update protocol", 1024)
    size = _positive_int(value.get("size"), "runtime update artifact size", 32 * 1024 * 1024)
    minimum_app = _positive_int(value.get("minAppVersionCode"), "runtime update minimum app", 2**31 - 1)
    if sequence < minimum_app or size < 1:
        raise StoreError("runtime update sequence is below its minimum app version")
    if not isinstance(value.get("version"), str) or not re.fullmatch(
        r"[A-Za-z0-9][A-Za-z0-9._+-]{0,63}", value["version"]
    ):
        raise StoreError("runtime update version is invalid")
    _canonical_https_url(value.get("artifactUrl"))
    if not isinstance(value.get("sha256"), str) or not SHA256.fullmatch(value["sha256"]):
        raise StoreError("runtime update artifact digest is invalid")
    if not isinstance(value.get("createdAt"), str) or not re.fullmatch(
        r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", value["createdAt"]
    ):
        raise StoreError("runtime update timestamp is invalid")
    if canonical_inline(value) != decoded:
        raise StoreError("runtime update payload is not canonical JSON")
    if hashlib.sha256(RUNTIME_PUBLIC_KEY).hexdigest() != RUNTIME_KEY_SHA256:
        raise StoreError("publisher runtime public-key pin is internally inconsistent")
    try:
        with (
            tempfile.NamedTemporaryFile() as key_file,
            tempfile.NamedTemporaryFile() as payload_file,
            tempfile.NamedTemporaryFile() as signature_file,
        ):
            key_file.write(RUNTIME_PUBLIC_KEY)
            key_file.flush()
            payload_file.write(decoded)
            payload_file.flush()
            signature_file.write(signature)
            signature_file.flush()
            result = subprocess.run(
                [
                    "openssl", "pkeyutl", "-verify", "-rawin", "-pubin", "-inkey", key_file.name,
                    "-in", payload_file.name, "-sigfile", signature_file.name,
                ],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=20,
                check=False,
            )
    except (OSError, subprocess.SubprocessError) as error:
        raise StoreError("OpenSSL is unavailable for publisher-side runtime reproof") from error
    if result.returncode != 0:
        raise StoreError("publisher-side runtime signature reproof failed")
    return sequence


def _runtime_identity(base_fd: int) -> tuple[str, int]:
    runtime_fd = os.open("runtime", _flags_directory(), dir_fd=base_fd)
    try:
        metadata = os.fstat(runtime_fd)
        if metadata.st_uid != os.geteuid() or metadata.st_mode & 0o022:
            raise StoreError("runtime publication directory ownership or permissions are unsafe")
        payload = read_regular(runtime_fd, "runtime-manifest.json", 64 * 1024)
        return hashlib.sha256(payload).hexdigest(), verify_runtime_envelope(payload)
    finally:
        os.close(runtime_fd)


def require_runtime_identity(base_fd: int, expected_sha: str, expected_sequence: int) -> int:
    observed_sha, observed_sequence = _runtime_identity(base_fd)
    if observed_sha != expected_sha:
        raise StoreError("published runtime manifest changed after sequence reservation")
    if observed_sequence != expected_sequence:
        raise StoreError("controller runtime sequence does not match the publisher-verified signed envelope")
    return observed_sequence


def _validate_claim(value: Any) -> dict[str, Any]:
    fields = {
        "schemaVersion", "component", "sequence", "token", "version",
        "manifestSha256", "sourceCommit", "candidateProofSha256", "reservationSha256",
    }
    if not isinstance(value, dict) or set(value) != fields or value.get("schemaVersion") != 1:
        raise StoreError("sequence claim fields are invalid")
    if value["component"] != "android-app":
        raise StoreError("runtime sequence claims require a server-side signed-envelope publisher that is not enabled")
    _positive_int(value["sequence"], "sequence claim")
    if not isinstance(value["token"], str) or not TOKEN.fullmatch(value["token"]):
        raise StoreError("sequence claim token is invalid")
    if not isinstance(value["version"], str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,95}", value["version"]):
        raise StoreError("sequence claim version is invalid")
    for key in ("manifestSha256", "candidateProofSha256", "reservationSha256"):
        if not isinstance(value[key], str) or not SHA256.fullmatch(value[key]):
            raise StoreError("sequence claim digest is invalid")
    if not isinstance(value["sourceCommit"], str) or not COMMIT.fullmatch(value["sourceCommit"]):
        raise StoreError("sequence claim source commit is invalid")
    return value


def burn_claim(base_fd: int, claim: dict[str, Any], bootstrap_floor: int) -> None:
    claim = _validate_claim(claim)
    claims_fd = open_or_create_directory(base_fd, "sequence-claims", 0o755)
    try:
        name = f"{claim['sequence']}.json"
        claim_payload = canonical(claim)
        claim_sha = hashlib.sha256(claim_payload).hexdigest()
        try:
            existing = read_regular(claims_fd, name, 8 * 1024)
        except FileNotFoundError:
            flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC
            if hasattr(os, "O_NOFOLLOW"):
                flags |= os.O_NOFOLLOW
            fd = os.open(name, flags, 0o644, dir_fd=claims_fd)
            try:
                view = memoryview(claim_payload)
                while view:
                    count = os.write(fd, view)
                    if count <= 0:
                        raise StoreError("short shared-sequence claim write")
                    view = view[count:]
                os.fsync(fd)
            finally:
                os.close(fd)
            os.fsync(claims_fd)
        else:
            if existing != claim_payload:
                raise StoreError("shared sequence claim collision")
        high_fields = {"schemaVersion", "sequence", "component", "token", "claimSha256"}
        try:
            high_value, high_payload = read_json_file(base_fd, "sequence-high-water-v1.json", 8 * 1024)
        except FileNotFoundError:
            high_value = None
            high_payload = b""
        if high_value is not None:
            if not isinstance(high_value, dict) or set(high_value) != high_fields or high_value.get("schemaVersion") != 1:
                raise StoreError("shared sequence high-water record is invalid")
            _positive_int(high_value.get("sequence"), "shared sequence high-water")
            if high_value.get("component") not in {"android-app", "android-runtime"}:
                raise StoreError("shared sequence high-water component is invalid")
            if not isinstance(high_value.get("token"), str) or not TOKEN.fullmatch(high_value["token"]):
                raise StoreError("shared sequence high-water token is invalid")
            if not isinstance(high_value.get("claimSha256"), str) or not SHA256.fullmatch(high_value["claimSha256"]):
                raise StoreError("shared sequence high-water claim digest is invalid")
            if high_payload != canonical(high_value):
                raise StoreError("shared sequence high-water record is non-canonical")
            if high_value["token"] == claim["token"]:
                if high_value["sequence"] != claim["sequence"] or high_value["claimSha256"] != claim_sha:
                    raise StoreError("shared sequence recovery token does not match its claim")
                return
            if claim["sequence"] <= high_value["sequence"]:
                raise StoreError("shared app/runtime sequence has already been claimed")
        elif claim["sequence"] <= bootstrap_floor:
            raise StoreError("shared sequence claim is not above the verified bootstrap floor")
        high_value = {
            "schemaVersion": 1,
            "sequence": claim["sequence"],
            "component": claim["component"],
            "token": claim["token"],
            "claimSha256": claim_sha,
        }
        atomic_write(base_fd, "sequence-high-water-v1.json", canonical(high_value), 0o644)
    finally:
        os.close(claims_fd)


def _manifest_source(manifest: dict[str, Any]) -> str:
    value = manifest.get("gitCommit")
    if not isinstance(value, str) or not COMMIT.fullmatch(value):
        raise StoreError("candidate source commit is invalid")
    return value


def command_current(base: str) -> dict[str, Any] | None:
    handles = store_handles(base)
    try:
        acquire_lock(handles[0])
        _, descriptor = current_target(handles[1], handles[2], handles[3])
        return descriptor
    finally:
        close_handles(handles)


def command_publish(
    base: str,
    version: str,
    token: str,
    expected_previous: str,
    expected_app_sha: str,
    expected_runtime_sha: str,
    reservation_token: str,
    reservation_sha: str,
    expected_proof_sha: str,
    requested_sequence: int,
    runtime_sequence: int,
) -> dict[str, Any] | None:
    if not VERSION.fullmatch(version) or not TOKEN.fullmatch(token) or not TOKEN.fullmatch(reservation_token):
        raise StoreError("publication identity is invalid")
    if not (expected_previous == "-" or LEGACY_TARGET.fullmatch(expected_previous) or ACTIVATION_TARGET.fullmatch(expected_previous)):
        raise StoreError("expected previous target is invalid")
    for value in (expected_app_sha, expected_runtime_sha, reservation_sha, expected_proof_sha):
        if not SHA256.fullmatch(value):
            raise StoreError("publication reservation digest is invalid")
    _positive_int(requested_sequence, "requested shared sequence")
    _positive_int(runtime_sequence, "runtime shared sequence")
    handles = store_handles(base)
    base_fd, fleet_fd, releases_fd, activations_fd = handles
    stage = staging_name(version, token)
    new_target = f"activations/{token}"
    try:
        acquire_lock(base_fd)
        observed_target, previous = current_target(fleet_fd, releases_fd, activations_fd)
        if observed_target == new_target:
            observed_runtime_sequence = require_runtime_identity(
                base_fd, expected_runtime_sha, runtime_sequence
            )
            release_fd = open_release_for_target(fleet_fd, releases_fd, activations_fd, new_target)
            try:
                descriptor = release_descriptor(new_target, release_fd)
                proof_value, proof_payload = read_json_file(release_fd, PROOF_NAME, MAX_PROOF)
                proof = validate_proof(proof_value, proof_payload)
                manifest = validate_manifest(read_regular(release_fd, "manifest.json", MAX_MANIFEST))
            finally:
                os.close(release_fd)
            if (
                descriptor["versionName"] != version
                or descriptor["versionCode"] != requested_sequence
                or descriptor["manifestSha256"] != proof["manifestSha256"]
                or hashlib.sha256(proof_payload).hexdigest() != expected_proof_sha
            ):
                raise StoreError("active recovery candidate does not match its verified inputs")
            if expected_previous == "-":
                raise StoreError("cannot reconcile an activation without a previous shared-sequence baseline")
            previous_fd = open_release_for_target(fleet_fd, releases_fd, activations_fd, expected_previous)
            try:
                previous = release_descriptor(expected_previous, previous_fd)
            finally:
                os.close(previous_fd)
            if previous["manifestSha256"] != expected_app_sha:
                raise StoreError("recovery baseline does not match the sequence reservation")
            claim = {
                "schemaVersion": 1,
                "component": "android-app",
                "sequence": requested_sequence,
                "token": reservation_token,
                "version": version,
                "manifestSha256": proof["manifestSha256"],
                "sourceCommit": _manifest_source(manifest),
                "candidateProofSha256": expected_proof_sha,
                "reservationSha256": reservation_sha,
            }
            burn_claim(
                base_fd,
                claim,
                max(LIVE_SEQUENCE_FLOOR, previous["versionCode"], observed_runtime_sequence),
            )
            return previous
        if observed_target != expected_previous:
            raise StoreError("latest changed before publication; refusing the switch")
        if previous is None:
            raise StoreError("cannot bootstrap the shared sequence without a current app release")
        if previous["manifestSha256"] != expected_app_sha:
            raise StoreError("published app manifest changed after sequence reservation")
        observed_runtime_sequence = require_runtime_identity(
            base_fd, expected_runtime_sha, runtime_sequence
        )

        staging_fd = os.open(stage, _flags_directory(), dir_fd=releases_fd)
        try:
            proof, proof_sha = validate_staging(staging_fd, version)
            if proof_sha != expected_proof_sha or proof["versionCode"] != requested_sequence:
                raise StoreError("staged candidate does not match its verified publication inputs")
            manifest_payload = read_regular(staging_fd, "manifest.json", MAX_MANIFEST)
            manifest = validate_manifest(manifest_payload)
            if hashlib.sha256(manifest_payload).hexdigest() != proof["manifestSha256"]:
                raise StoreError("staged candidate manifest binding is invalid")
            source_commit = _manifest_source(manifest)
        finally:
            os.close(staging_fd)

        release_name = version
        try:
            existing_fd = os.open(release_name, _flags_directory(), dir_fd=releases_fd)
        except FileNotFoundError:
            os.rename(stage, release_name, src_dir_fd=releases_fd, dst_dir_fd=releases_fd)
            os.fsync(releases_fd)
        else:
            try:
                stage_fd = os.open(stage, _flags_directory(), dir_fd=releases_fd)
                try:
                    if not _trees_match(stage_fd, existing_fd):
                        raise StoreError("release version already exists with different bytes")
                finally:
                    os.close(stage_fd)
            finally:
                os.close(existing_fd)
            _remove_staging(releases_fd, stage)

        claim = {
            "schemaVersion": 1,
            "component": "android-app",
            "sequence": requested_sequence,
            "token": reservation_token,
            "version": version,
            "manifestSha256": proof["manifestSha256"],
            "sourceCommit": source_commit,
            "candidateProofSha256": proof_sha,
            "reservationSha256": reservation_sha,
        }
        burn_claim(
            base_fd,
            claim,
            max(LIVE_SEQUENCE_FLOOR, previous["versionCode"], observed_runtime_sequence),
        )

        observed_target, observed_previous = current_target(fleet_fd, releases_fd, activations_fd)
        if observed_target != expected_previous or observed_previous is None or observed_previous["manifestSha256"] != expected_app_sha:
            raise StoreError("published app changed immediately before activation")
        require_runtime_identity(base_fd, expected_runtime_sha, observed_runtime_sequence)

        activation_value = f"../releases/{version}"
        try:
            existing_activation = os.readlink(token, dir_fd=activations_fd)
        except FileNotFoundError:
            os.symlink(activation_value, token, dir_fd=activations_fd)
            os.fsync(activations_fd)
        else:
            if existing_activation != activation_value:
                raise StoreError("activation token collision")
        try:
            switch_latest(fleet_fd, new_target)
            post_target, post = current_target(fleet_fd, releases_fd, activations_fd)
            if post_target != new_target or post is None or post["manifestSha256"] != proof["manifestSha256"]:
                raise StoreError("candidate manifest changed during activation")
            require_runtime_identity(base_fd, expected_runtime_sha, observed_runtime_sequence)
        except BaseException:
            rollback_target, _ = current_target(fleet_fd, releases_fd, activations_fd)
            if rollback_target == new_target:
                if expected_previous == "-":
                    remove_latest(fleet_fd)
                else:
                    switch_latest(fleet_fd, expected_previous)
            elif rollback_target != expected_previous:
                raise StoreError("activation failed and its exact target could not be reconciled")
            raise
        return previous
    finally:
        close_handles(handles)


def command_rollback(base: str, new_target: str, previous_target: str) -> dict[str, Any] | None:
    if not ACTIVATION_TARGET.fullmatch(new_target) or not (
        previous_target == "-" or LEGACY_TARGET.fullmatch(previous_target) or ACTIVATION_TARGET.fullmatch(previous_target)
    ):
        raise StoreError("rollback target is invalid")
    handles = store_handles(base)
    try:
        acquire_lock(handles[0])
        current, descriptor = current_target(handles[1], handles[2], handles[3])
        if current == previous_target:
            return descriptor
        if current != new_target:
            raise StoreError("latest changed after publication; refusing rollback")
        if previous_target == "-":
            remove_latest(handles[1])
            return None
        switch_latest(handles[1], previous_target)
        restored, restored_descriptor = current_target(handles[1], handles[2], handles[3])
        if restored != previous_target or restored_descriptor is None:
            raise StoreError("previous publication was not durably restored")
        return restored_descriptor
    finally:
        close_handles(handles)


def command_cleanup(base: str, version: str, token: str) -> None:
    handles = store_handles(base)
    try:
        acquire_lock(handles[0])
        name = staging_name(version, token)
        try:
            os.stat(name, dir_fd=handles[2], follow_symlinks=False)
        except FileNotFoundError:
            return
        _remove_staging(handles[2], name)
    finally:
        close_handles(handles)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    subparsers = result.add_subparsers(dest="command", required=True)
    current = subparsers.add_parser("current")
    current.add_argument("base")
    prepare = subparsers.add_parser("prepare")
    prepare.add_argument("base")
    prepare.add_argument("version")
    prepare.add_argument("token")
    publish = subparsers.add_parser("publish")
    publish.add_argument("base")
    publish.add_argument("version")
    publish.add_argument("token")
    publish.add_argument("expected_previous")
    publish.add_argument("expected_app_sha")
    publish.add_argument("expected_runtime_sha")
    publish.add_argument("reservation_token")
    publish.add_argument("reservation_sha")
    publish.add_argument("expected_proof_sha")
    publish.add_argument("requested_sequence", type=int)
    publish.add_argument("runtime_sequence", type=int)
    rollback = subparsers.add_parser("rollback")
    rollback.add_argument("base")
    rollback.add_argument("new_target")
    rollback.add_argument("previous_target")
    cleanup = subparsers.add_parser("cleanup")
    cleanup.add_argument("base")
    cleanup.add_argument("version")
    cleanup.add_argument("token")
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if args.command == "current":
            value = command_current(args.base)
            print(canonical(value).decode(), end="")
        elif args.command == "prepare":
            command_prepare(args.base, args.version, args.token)
        elif args.command == "publish":
            value = command_publish(
                args.base, args.version, args.token, args.expected_previous,
                args.expected_app_sha, args.expected_runtime_sha, args.reservation_token,
                args.reservation_sha, args.expected_proof_sha, args.requested_sequence,
                args.runtime_sequence,
            )
            print(canonical(value).decode(), end="")
        elif args.command == "rollback":
            value = command_rollback(args.base, args.new_target, args.previous_target)
            print(canonical(value).decode(), end="")
        else:
            command_cleanup(args.base, args.version, args.token)
        return 0
    except (OSError, StoreError) as error:
        print(f"publication-store: {error}", file=sys.stderr)
        return 75 if args.command in {"publish", "rollback"} else 1


if __name__ == "__main__":
    raise SystemExit(main())
