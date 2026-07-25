#!/usr/bin/env python3
import argparse
import hashlib
import json
import pathlib
from private_https import fetch


def stream_hash(url: str, loopback_fallback: bool) -> str:
    digest = hashlib.sha256()
    with fetch(url, timeout=60, loopback_fallback=loopback_fallback) as response:
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("release_directory")
    parser.add_argument("served_base_url")
    parser.add_argument("--loopback-fallback", action="store_true")
    args = parser.parse_args()
    directory = pathlib.Path(args.release_directory).resolve()
    local_bytes = (directory / "manifest.json").read_bytes()
    manifest_url = args.served_base_url.rstrip("/") + "/manifest.json"
    with fetch(manifest_url, timeout=60, loopback_fallback=args.loopback_fallback) as response:
        served_bytes = response.read()
    if served_bytes != local_bytes:
        raise SystemExit("served manifest does not match the verified local manifest")
    manifest = json.loads(local_bytes)
    artifacts = manifest.get("artifacts") or []
    for artifact in artifacts:
        actual = stream_hash(artifact["apkUrl"], args.loopback_fallback)
        if actual != artifact["apkSha256"]:
            raise SystemExit(f"served {artifact['abi']} APK checksum mismatch")
    print(f"verified served {manifest['versionName']} manifest and {len(artifacts)} APKs")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
