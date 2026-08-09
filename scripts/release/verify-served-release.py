#!/usr/bin/env python3
"""Verify exact Android manifest/APK bytes through the approved HTTPS lane."""

from __future__ import annotations

import argparse
import hashlib
import os
import pathlib
import sys
import time
import urllib.error
from urllib.parse import urlsplit

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from private_https import canonical_https_url, fetch, read_bytes, require_child_url
import publication_receipt
import release_candidate


MAX_MANIFEST = 32 * 1024
MAX_APK = 300 * 1024 * 1024
TOTAL_APK = 600 * 1024 * 1024
TOTAL_DEADLINE_SECONDS = 600


class ServedError(ValueError):
    pass


def validate_base(value: str) -> str:
    result = canonical_https_url(value.rstrip("/"))
    if not urlsplit(result).path.endswith("/fleet/latest"):
        raise ServedError("served release base must end in /fleet/latest")
    return result


def stream_hash(
    url: str,
    *,
    expected_size: int,
    deadline: float,
    loopback_fallback: bool,
) -> str:
    if type(expected_size) is not int or not 1 <= expected_size <= MAX_APK:
        raise ServedError("served APK size is invalid")
    timeout = max(1, min(TOTAL_DEADLINE_SECONDS, int(deadline - time.monotonic() + 0.999)))
    if timeout <= 0:
        raise TimeoutError("served-release verification deadline expired")
    digest = hashlib.sha256()
    observed = 0
    with fetch(url, timeout=timeout, loopback_fallback=loopback_fallback, maximum=expected_size) as response:
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("served-release verification deadline expired")
            try:
                response.fp.raw._sock.settimeout(max(0.1, remaining))  # type: ignore[attr-defined]
            except AttributeError:
                pass
            chunk = response.read(min(1024 * 1024, expected_size + 1 - observed))
            if not chunk:
                break
            observed += len(chunk)
            if observed > expected_size:
                raise ServedError("served APK exceeds its declared size")
            digest.update(chunk)
    if observed != expected_size:
        raise ServedError("served APK size does not match its descriptor")
    return digest.hexdigest()


def manifest_artifacts(payload: bytes, base: str) -> tuple[dict, list[dict]]:
    manifest, artifacts = release_candidate.validate_manifest(payload)
    if sum(item["size"] for item in artifacts) > TOTAL_APK:
        raise ServedError("served APK total exceeds its release limit")
    expected_names: set[str] = set()
    for item in artifacts:
        require_child_url(base, item["apkUrl"])
        expected = base + "/" + item["name"]
        if item["apkUrl"] != expected or item["name"] in expected_names:
            raise ServedError("served APK URL is not the exact approved release resource")
        expected_names.add(item["name"])
    return manifest, artifacts


def verify_descriptor(
    descriptor: dict,
    base: str,
    *,
    loopback_fallback: bool,
    deadline: float,
) -> None:
    manifest_url = base + "/manifest.json"
    payload = read_bytes(
        manifest_url,
        maximum=MAX_MANIFEST,
        deadline=deadline,
        loopback_fallback=loopback_fallback,
    )
    if len(payload) != descriptor["manifestSize"] or hashlib.sha256(payload).hexdigest() != descriptor["manifestSha256"]:
        raise ServedError("restored served manifest does not match the recorded previous release")
    manifest, artifacts = manifest_artifacts(payload, base)
    if manifest["versionName"] != descriptor["versionName"] or manifest["versionCode"] != descriptor["versionCode"]:
        raise ServedError("restored served manifest identity is wrong")
    expected = sorted(descriptor["artifacts"], key=lambda item: item["abi"])
    observed = sorted(
        [
            {"abi": item["abi"], "name": item["name"], "sha256": item["apkSha256"], "size": item["size"]}
            for item in artifacts
        ],
        key=lambda item: item["abi"],
    )
    if observed != expected:
        raise ServedError("restored served APK descriptors do not match the previous release")
    for item in observed:
        if stream_hash(
            base + "/" + item["name"],
            expected_size=item["size"],
            deadline=deadline,
            loopback_fallback=loopback_fallback,
        ) != item["sha256"]:
            raise ServedError(f"restored served {item['abi']} APK checksum mismatch")


def verify_absent(base: str, *, loopback_fallback: bool, deadline: float) -> None:
    try:
        read_bytes(
            base + "/manifest.json",
            maximum=MAX_MANIFEST,
            deadline=deadline,
            loopback_fallback=loopback_fallback,
        )
    except urllib.error.HTTPError as error:
        if error.code in {404, 410}:
            return
        raise ServedError("absent previous lane returned an unexpected HTTPS status") from error
    raise ServedError("previously absent release lane still serves a manifest")


def verify_candidate(directory: pathlib.Path, base: str, loopback_fallback: bool) -> tuple[str, int]:
    proof, _ = release_candidate.load_proof(directory)
    directory_fd = release_candidate.open_directory(directory)
    try:
        local_manifest = release_candidate.read_regular(directory_fd, "manifest.json", MAX_MANIFEST)
    finally:
        os.close(directory_fd)
    manifest, artifacts = manifest_artifacts(local_manifest, base)
    if manifest["versionName"] != proof["versionName"] or manifest["versionCode"] != proof["versionCode"]:
        raise ServedError("local candidate proof identity changed")
    deadline = time.monotonic() + TOTAL_DEADLINE_SECONDS
    served = read_bytes(
        base + "/manifest.json",
        maximum=MAX_MANIFEST,
        deadline=deadline,
        loopback_fallback=loopback_fallback,
    )
    if served != local_manifest:
        raise ServedError("served manifest does not match the verified local manifest")
    for item in artifacts:
        digest = stream_hash(
            item["apkUrl"],
            expected_size=item["size"],
            deadline=deadline,
            loopback_fallback=loopback_fallback,
        )
        if digest != item["apkSha256"]:
            raise ServedError(f"served {item['abi']} APK checksum mismatch")
    return manifest["versionName"], len(artifacts)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--restored-from-receipt", type=pathlib.Path)
    parser.add_argument("paths", nargs="+")
    parser.add_argument("--loopback-fallback", action="store_true")
    args = parser.parse_args()
    try:
        if args.restored_from_receipt:
            if len(args.paths) != 1:
                parser.error("restored verification requires SERVED_BASE_URL")
            base = validate_base(args.paths[0])
            receipt, _ = publication_receipt.read(args.restored_from_receipt.absolute())
            if receipt["state"] != "rolled_back":
                raise ServedError("publication receipt is not in the rolled-back state")
            deadline = time.monotonic() + TOTAL_DEADLINE_SECONDS
            if receipt["previousDescriptor"] is None:
                verify_absent(base, loopback_fallback=args.loopback_fallback, deadline=deadline)
                print("verified restored absence of the previous release lane")
            else:
                verify_descriptor(
                    receipt["previousDescriptor"], base,
                    loopback_fallback=args.loopback_fallback, deadline=deadline,
                )
                print(f"verified restored served {receipt['previousDescriptor']['versionName']} manifest and 2 APKs")
        else:
            if len(args.paths) != 2:
                parser.error("candidate verification requires RELEASE_DIRECTORY SERVED_BASE_URL")
            version, count = verify_candidate(
                pathlib.Path(args.paths[0]).absolute(), validate_base(args.paths[1]),
                args.loopback_fallback,
            )
            print(f"verified served {version} manifest and {count} APKs")
        return 0
    except (OSError, ValueError, TimeoutError, publication_receipt.ReceiptError, release_candidate.CandidateError) as error:
        print(f"verify-served-release: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
