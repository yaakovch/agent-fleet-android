#!/usr/bin/env python3
"""Publish one proved Android candidate with a durable recoverable transaction."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import shlex
import stat
import subprocess
import sys
import tempfile
from typing import Any
from urllib.parse import urlsplit

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import publication_receipt
import publication_store
import release_candidate
import release_identity
from private_https import canonical_https_url, origin


REMOTE = re.compile(
    r"^(?:[A-Za-z0-9][A-Za-z0-9._-]{0,63}@)?"
    r"[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$"
)
SAFE_COMPONENT = re.compile(r"^[A-Za-z0-9._-]+$")
SSH_TIMEOUT = 45
SCP_TIMEOUT = 600


class PublishError(ValueError):
    pass


class RemoteUnavailable(PublishError):
    pass


def destination_parts(value: str) -> tuple[str, str]:
    remote, separator, base = value.partition(":")
    if not separator or not base.startswith("/") or "\n" in value or "\r" in value:
        raise PublishError("publication destination is invalid")
    components = base.split("/")[1:]
    if not components or any(not SAFE_COMPONENT.fullmatch(item) or item in (".", "..") for item in components):
        raise PublishError("publication destination path is invalid")
    if remote != "local" and (not REMOTE.fullmatch(remote) or remote.startswith("-")):
        raise PublishError("remote publication host is invalid")
    return remote, base


def destination_digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def validate_url_mapping(release_base: str, runtime_url: str) -> None:
    release = canonical_https_url(release_base.rstrip("/"))
    runtime = canonical_https_url(runtime_url)
    release_parts = urlsplit(release)
    if not release_parts.path.endswith("/fleet/latest"):
        raise PublishError("release base URL must end in /fleet/latest")
    prefix = release_parts.path[: -len("/fleet/latest")]
    expected_runtime_path = prefix + "/runtime/runtime-manifest.json"
    if origin(release) != origin(runtime) or urlsplit(runtime).path != expected_runtime_path:
        raise PublishError("release and runtime URLs do not map exactly to one publication base")


def ssh_options() -> list[str]:
    return [
        "-o", "BatchMode=yes",
        "-o", "StrictHostKeyChecking=yes",
        "-o", "ConnectTimeout=15",
        "-o", "ConnectionAttempts=1",
        "-o", "ServerAliveInterval=5",
        "-o", "ServerAliveCountMax=3",
    ]


def remote_command(remote: str, arguments: list[str], *, timeout: int = SSH_TIMEOUT) -> subprocess.CompletedProcess[bytes]:
    helper = pathlib.Path(publication_store.__file__).read_bytes()
    command_text = " ".join(shlex.quote(item) for item in ["python3", "-", *arguments])
    try:
        return subprocess.run(
            ["ssh", *ssh_options(), "--", remote, command_text],
            input=helper,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired as error:
        raise RemoteUnavailable("remote publication command timed out") from error
    except OSError as error:
        raise RemoteUnavailable("remote publication command could not start") from error


def probe_remote(remote: str) -> None:
    try:
        result = subprocess.run(
            ["ssh", *ssh_options(), "--", remote, "true"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            timeout=SSH_TIMEOUT,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RemoteUnavailable("primary publisher is unavailable before mutation") from error
    if result.returncode == 255:
        raise RemoteUnavailable("primary publisher is unavailable before mutation")
    if result.returncode != 0:
        raise PublishError("primary publisher rejected the pre-mutation probe")


def store_call(destination: str, arguments: list[str], *, timeout: int = SSH_TIMEOUT) -> bytes:
    remote, _ = destination_parts(destination)
    if remote == "local":
        try:
            result = subprocess.run(
                [sys.executable, str(pathlib.Path(publication_store.__file__)), *arguments],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=timeout,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            raise PublishError("local publication-store command failed to run") from error
    else:
        result = remote_command(remote, arguments, timeout=timeout)
    if result.returncode != 0:
        detail = " ".join(result.stderr.decode(errors="replace").split())[:500]
        if result.returncode == 255:
            raise RemoteUnavailable(detail or "remote publication response is unavailable")
        error = PublishError(detail or "publication-store operation failed")
        if result.returncode == 75:
            setattr(error, "reserved_status", True)
        raise error
    return result.stdout


def current_descriptor(destination: str) -> dict[str, Any] | None:
    _, base = destination_parts(destination)
    payload = store_call(destination, ["current", base])
    try:
        value = json.loads(payload)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise PublishError("publisher returned an invalid current-release descriptor") from error
    if value is not None:
        publication_receipt._previous_descriptor(value, value.get("target", ""))
    return value


def prepare_destination(destination: str, version: str, token: str) -> None:
    _, base = destination_parts(destination)
    output = store_call(destination, ["prepare", base, version, token])
    if output:
        raise PublishError("publisher returned unexpected staging output")


def cleanup_destination(destination: str, version: str, token: str) -> None:
    _, base = destination_parts(destination)
    try:
        store_call(destination, ["cleanup", base, version, token])
    except (PublishError, RemoteUnavailable):
        pass


def stable_snapshot(source: pathlib.Path, proof: dict[str, Any], proof_payload: bytes) -> tempfile.TemporaryDirectory[str]:
    temporary = tempfile.TemporaryDirectory(prefix="agent-fleet-publish-")
    destination = pathlib.Path(temporary.name)
    source_fd = release_candidate.open_directory(source)
    destination_fd = os.open(destination, os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC)
    try:
        records = [*proof["files"], {
            "name": release_candidate.PROOF_NAME,
            "sha256": hashlib.sha256(proof_payload).hexdigest(),
            "size": len(proof_payload),
        }]
        for record in records:
            maximum = release_candidate.MAX_APK_BYTES if record["name"].endswith(".apk") else release_candidate.MAX_JSON_BYTES
            payload = release_candidate.read_regular(source_fd, record["name"], maximum)
            if len(payload) != record["size"] or hashlib.sha256(payload).hexdigest() != record["sha256"]:
                raise PublishError(f"candidate changed while creating stable snapshot: {record['name']}")
            flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC
            if hasattr(os, "O_NOFOLLOW"):
                flags |= os.O_NOFOLLOW
            fd = os.open(record["name"], flags, 0o600, dir_fd=destination_fd)
            try:
                view = memoryview(payload)
                while view:
                    count = os.write(fd, view)
                    if count <= 0:
                        raise PublishError("short write while creating stable candidate snapshot")
                    view = view[count:]
                os.fsync(fd)
            finally:
                os.close(fd)
        os.fsync(destination_fd)
    except BaseException:
        temporary.cleanup()
        raise
    finally:
        os.close(source_fd)
        os.close(destination_fd)
    return temporary


def copy_local_snapshot(snapshot: pathlib.Path, base: str, version: str, token: str) -> None:
    base_fd, fleet_fd, releases_fd, _ = publication_store.store_handles(base)
    stage = publication_store.staging_name(version, token)
    try:
        stage_fd = os.open(stage, publication_store._flags_directory(), dir_fd=releases_fd)
        source_fd = os.open(snapshot, os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC)
        try:
            actual = set(os.listdir(stage_fd))
            expected = set(os.listdir(source_fd))
            if not actual <= expected:
                raise PublishError("staging directory contains unexpected files")
            for name in sorted(expected):
                maximum = release_candidate.MAX_APK_BYTES if name.endswith(".apk") else release_candidate.MAX_JSON_BYTES
                payload = release_candidate.read_regular(source_fd, name, maximum)
                flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_CLOEXEC
                if hasattr(os, "O_NOFOLLOW"):
                    flags |= os.O_NOFOLLOW
                fd = os.open(name, flags, 0o600, dir_fd=stage_fd)
                try:
                    if not stat.S_ISREG(os.fstat(fd).st_mode):
                        raise PublishError("staging destination is not a regular file")
                    os.fchmod(fd, 0o644)
                    view = memoryview(payload)
                    while view:
                        count = os.write(fd, view)
                        if count <= 0:
                            raise PublishError("short write while staging candidate")
                        view = view[count:]
                    os.fsync(fd)
                finally:
                    os.close(fd)
            os.fsync(stage_fd)
        finally:
            os.close(source_fd)
            os.close(stage_fd)
    finally:
        publication_store.close_handles((base_fd, fleet_fd, releases_fd, _))


def copy_remote_snapshot(snapshot: pathlib.Path, remote: str, base: str, version: str, token: str) -> None:
    stage = f"{base}/fleet/releases/{publication_store.staging_name(version, token)}"
    files = [str(path) for path in sorted(snapshot.iterdir())]
    try:
        result = subprocess.run(
            [
                "scp", "-B", *ssh_options(), "-p", "--", *files,
                f"{remote}:{stage}/",
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=SCP_TIMEOUT,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RemoteUnavailable("bounded remote candidate transfer did not complete") from error
    if result.returncode != 0:
        detail = " ".join(result.stderr.decode(errors="replace").split())[:500]
        raise PublishError(detail or "remote candidate transfer failed")


def stage_snapshot(destination: str, snapshot: pathlib.Path, version: str, token: str) -> None:
    remote, base = destination_parts(destination)
    prepare_destination(destination, version, token)
    if remote == "local":
        copy_local_snapshot(snapshot, base, version, token)
    else:
        copy_remote_snapshot(snapshot, remote, base, version, token)


def publish_destination(
    destination: str,
    receipt: dict[str, Any],
    reservation: dict[str, Any],
) -> dict[str, Any] | None:
    _, base = destination_parts(destination)
    output = store_call(destination, [
        "publish", base, receipt["versionName"], receipt["token"], receipt["previousTarget"],
        reservation["appManifestSha256"], reservation["runtimeManifestSha256"],
        reservation["token"], receipt["reservationSha256"], receipt["candidateProofSha256"],
        str(receipt["versionCode"]), str(reservation["runtimeSequence"]),
    ])
    try:
        return json.loads(output)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise PublishError("publisher returned an invalid publication result") from error


def stage_and_publish(
    destination: str,
    snapshot: pathlib.Path,
    receipt: dict[str, Any],
    reservation: dict[str, Any],
) -> dict[str, Any] | None:
    try:
        stage_snapshot(destination, snapshot, receipt["versionName"], receipt["token"])
        return publish_destination(destination, receipt, reservation)
    finally:
        cleanup_destination(destination, receipt["versionName"], receipt["token"])


def rollback_destination(destination: str, receipt: dict[str, Any]) -> dict[str, Any] | None:
    _, base = destination_parts(destination)
    output = store_call(destination, ["rollback", base, receipt["newTarget"], receipt["previousTarget"]])
    try:
        return json.loads(output)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise PublishError("publisher returned an invalid rollback result") from error


def select_destination(primary: str, fallback: str) -> tuple[str, str]:
    if primary:
        remote, _ = destination_parts(primary)
        if remote != "local":
            try:
                probe_remote(remote)
            except RemoteUnavailable as error:
                if fallback:
                    destination_parts(fallback)
                    raise PublishError(
                        "automatic fallback is unsafe because the two destinations do not prove "
                        "one shared sequence authority; select the fallback explicitly after recovery"
                    ) from error
                raise
        return "primary", primary
    if fallback:
        destination_parts(fallback)
        return "fallback", fallback
    raise PublishError("a publication destination is required")


def secure_sequence_reproof(
    repo: pathlib.Path,
    reservation_path: pathlib.Path,
    version_code: int,
    release_base: str,
    runtime_url: str,
    loopback: bool,
) -> tuple[dict[str, Any], bytes]:
    command = [
        sys.executable, str(repo / "scripts/release/check-release-sequence.py"),
        str(version_code), release_base.rstrip("/") + "/manifest.json", runtime_url,
        "--reservation", str(reservation_path),
    ]
    if loopback:
        command.append("--loopback-fallback")
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180, check=False)
    if result.returncode != 0:
        detail = " ".join(result.stderr.decode(errors="replace").split())[:500]
        raise PublishError(detail or "sequence reservation could not be reproved")
    module_path = repo / "scripts/release/check-release-sequence.py"
    import importlib.util
    specification = importlib.util.spec_from_file_location("agent_fleet_sequence", module_path)
    if specification is None or specification.loader is None:
        raise PublishError("sequence reservation verifier could not be loaded")
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module.load_reservation(reservation_path)


def require_production_certificate(repo: pathlib.Path, proof: dict[str, Any]) -> None:
    pin_path = repo / "app/release-signing-certificate-sha256.txt"
    payload = release_identity.secure_read(pin_path, 128, private=False)
    expected = proof.get("certificateSha256", "")
    if payload != (expected + "\n").encode("ascii", errors="strict"):
        raise PublishError("candidate proof certificate does not match the protected production signer")


def receipt_identity(
    publisher: str,
    destination: str,
    proof: dict[str, Any],
    proof_payload: bytes,
    reservation: dict[str, Any],
    reservation_payload: bytes,
    previous: dict[str, Any] | None,
) -> dict[str, Any]:
    token = os.urandom(16).hex()
    return {
        "schemaVersion": 2,
        "publisher": publisher,
        "destinationSha256": destination_digest(destination),
        "versionName": proof["versionName"],
        "versionCode": proof["versionCode"],
        "token": token,
        "previousTarget": previous["target"] if previous else "-",
        "newTarget": f"activations/{token}",
        "previousDescriptor": previous,
        "candidateProofSha256": hashlib.sha256(proof_payload).hexdigest(),
        "reservationSha256": hashlib.sha256(reservation_payload).hexdigest(),
        "reservationToken": reservation["token"],
        "state": "prepared",
    }


def reconcile_receipt(
    path: pathlib.Path,
    existing: dict[str, Any],
    publisher: str,
    destination: str,
    proof: dict[str, Any],
    proof_payload: bytes,
    reservation: dict[str, Any],
    reservation_payload: bytes,
) -> tuple[dict[str, Any], bool]:
    expected = {
        "publisher": publisher,
        "destinationSha256": destination_digest(destination),
        "versionName": proof["versionName"],
        "versionCode": proof["versionCode"],
        "candidateProofSha256": hashlib.sha256(proof_payload).hexdigest(),
        "reservationSha256": hashlib.sha256(reservation_payload).hexdigest(),
        "reservationToken": reservation["token"],
    }
    if any(existing[key] != value for key, value in expected.items()):
        raise PublishError("existing publication receipt belongs to different verified inputs")
    current = current_descriptor(destination)
    current_target = current["target"] if current else "-"
    if existing["state"] == "switched":
        if current_target != existing["newTarget"]:
            raise PublishError("switched publication receipt no longer matches the active target")
        return existing, True
    if existing["state"] == "prepared":
        if current_target == existing["newTarget"]:
            existing["state"] = "switched"
            publication_receipt.write(path, existing)
            return existing, True
        if current_target != existing["previousTarget"]:
            raise PublishError("prepared publication cannot reconcile the active target")
        return existing, False
    if current_target != existing["previousTarget"]:
        raise PublishError("rolled-back publication no longer matches its recorded previous target")
    replacement = receipt_identity(
        publisher, destination, proof, proof_payload, reservation, reservation_payload,
        existing["previousDescriptor"],
    )
    publication_receipt.write(path, replacement)
    return replacement, False


def resolve_receipt_path(argument: str | None, version: str) -> pathlib.Path:
    return pathlib.Path(argument).absolute() if argument else publication_receipt.default_path(version)


def command_rollback(path: pathlib.Path) -> int:
    receipt, _ = publication_receipt.read(path)
    primary = os.environ.get("AGENT_FLEET_PUBLISH_PRIMARY", "")
    fallback = os.environ.get("AGENT_FLEET_PUBLISH_FALLBACK", "")
    destination = primary if receipt["publisher"] == "primary" else fallback
    if not destination or destination_digest(destination) != receipt["destinationSha256"]:
        raise PublishError("publication destination no longer matches the receipt")
    if receipt["state"] == "rolled_back":
        return 0
    if receipt["state"] != "switched":
        current = current_descriptor(destination)
        target = current["target"] if current else "-"
        if target != receipt["newTarget"]:
            raise PublishError("prepared receipt is not rollback-eligible at the current target")
    restored = rollback_destination(destination, receipt)
    expected = receipt["previousDescriptor"]
    if restored != expected:
        raise PublishError("publisher did not restore the exact previous release descriptor")
    receipt["state"] = "rolled_back"
    publication_receipt.write(path, receipt)
    print("publication rollback completed")
    return 0


def main() -> int:
    if len(sys.argv) >= 2 and sys.argv[1] == "--rollback":
        if len(sys.argv) != 3:
            print("usage: publish-release.sh --rollback TRANSACTION_FILE", file=sys.stderr)
            return 2
        try:
            return command_rollback(pathlib.Path(sys.argv[2]).absolute())
        except (OSError, PublishError, publication_receipt.ReceiptError) as error:
            print(f"publish-release: {error}", file=sys.stderr)
            return 75

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("release_directory", type=pathlib.Path)
    parser.add_argument("transaction_file", nargs="?")
    parser.add_argument("--sequence-reservation", type=pathlib.Path, required=True)
    parser.add_argument("--release-base-url", default=os.environ.get("AGENT_FLEET_RELEASE_BASE_URL", ""))
    parser.add_argument("--runtime-manifest-url", default=os.environ.get("AGENT_FLEET_RUNTIME_MANIFEST_URL", ""))
    parser.add_argument("--loopback-fallback", action="store_true")
    args = parser.parse_args()
    try:
        source = args.release_directory.absolute()
        proof, proof_payload = release_candidate.load_proof(source)
        validate_url_mapping(args.release_base_url, args.runtime_manifest_url)
        repo = pathlib.Path(__file__).resolve().parents[2]
        require_production_certificate(repo, proof)
        reservation, reservation_payload = secure_sequence_reproof(
            repo, args.sequence_reservation.absolute(), proof["versionCode"],
            args.release_base_url, args.runtime_manifest_url, args.loopback_fallback,
        )
        primary = os.environ.get("AGENT_FLEET_PUBLISH_PRIMARY", "")
        fallback = os.environ.get("AGENT_FLEET_PUBLISH_FALLBACK", "")
        publisher, destination = select_destination(primary, fallback)
        remote, _ = destination_parts(destination)
        if remote == "local" and not args.loopback_fallback:
            raise PublishError("local publication requires the explicit certificate-checked loopback mode")
        receipt_path = resolve_receipt_path(args.transaction_file, proof["versionName"])
        snapshot = stable_snapshot(source, proof, proof_payload)
        try:
            try:
                existing, _ = publication_receipt.read(receipt_path)
            except FileNotFoundError:
                previous = current_descriptor(destination)
                receipt = receipt_identity(
                    publisher, destination, proof, proof_payload, reservation,
                    reservation_payload, previous,
                )
                publication_receipt.write(receipt_path, receipt)
                done = False
            else:
                receipt, done = reconcile_receipt(
                    receipt_path, existing, publisher, destination, proof, proof_payload,
                    reservation, reservation_payload,
                )
            if done:
                print(f"published {proof['versionName']} to {publisher} (reconciled)")
                return 0
            try:
                observed_previous = stage_and_publish(
                    destination, pathlib.Path(snapshot.name), receipt, reservation,
                )
                if observed_previous != receipt["previousDescriptor"]:
                    raise PublishError("publisher returned a mismatched previous release descriptor")
            except (PublishError, RemoteUnavailable) as error:
                try:
                    current = current_descriptor(destination)
                    current_target = current["target"] if current else "-"
                    if current_target == receipt["newTarget"]:
                        rollback_destination(destination, receipt)
                    elif current_target != receipt["previousTarget"]:
                        raise PublishError("uncertain publication target could not be reconciled")
                    receipt["state"] = "rolled_back"
                    publication_receipt.write(receipt_path, receipt)
                except BaseException as rollback_error:
                    print(f"publish-release: publication failed and rollback is uncertain: {rollback_error}", file=sys.stderr)
                    return 75
                print(f"publish-release: {error}", file=sys.stderr)
                return 75 if getattr(error, "reserved_status", False) or isinstance(error, RemoteUnavailable) else 1
            receipt["state"] = "switched"
            try:
                publication_receipt.write(receipt_path, receipt)
            except BaseException as error:
                try:
                    rollback_destination(destination, receipt)
                    receipt["state"] = "rolled_back"
                    publication_receipt.write(receipt_path, receipt)
                except BaseException as rollback_error:
                    print(f"publish-release: receipt write failed and rollback is uncertain: {rollback_error}", file=sys.stderr)
                    return 75
                print(f"publish-release: receipt write failed; previous target restored: {error}", file=sys.stderr)
                return 75
            print(f"published {proof['versionName']} to {publisher}")
            return 0
        finally:
            snapshot.cleanup()
    except (OSError, ValueError, PublishError, publication_receipt.ReceiptError, release_candidate.CandidateError) as error:
        print(f"publish-release: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
