#!/usr/bin/env python3
import argparse
import base64
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from private_https import fetch


def read_json(location: str, loopback_fallback: bool = False) -> dict:
    if location.startswith("https://"):
        with fetch(location, timeout=20, loopback_fallback=loopback_fallback) as response:
            return json.load(response)
    return json.loads(pathlib.Path(location).read_text(encoding="utf-8"))


def runtime_sequence(envelope: dict) -> int:
    payload = envelope.get("payload")
    if not isinstance(payload, str):
        raise ValueError("runtime envelope has no encoded payload")
    payload += "=" * (-len(payload) % 4)
    decoded = json.loads(base64.urlsafe_b64decode(payload))
    sequence = decoded.get("sequence")
    if not isinstance(sequence, int) or sequence < 1:
        raise ValueError("runtime payload has no valid sequence")
    return sequence


def required_floor(app_manifest: dict, runtime_manifest: dict) -> int:
    app_code = app_manifest.get("versionCode")
    if not isinstance(app_code, int) or app_code < 1:
        raise ValueError("app manifest has no valid versionCode")
    return max(app_code, runtime_sequence(runtime_manifest))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("version_code", type=int)
    parser.add_argument("app_manifest")
    parser.add_argument("runtime_manifest")
    parser.add_argument("--loopback-fallback", action="store_true")
    args = parser.parse_args()
    floor = required_floor(
        read_json(args.app_manifest, args.loopback_fallback),
        read_json(args.runtime_manifest, args.loopback_fallback),
    )
    if args.version_code <= floor:
        parser.error(f"version code {args.version_code} must be greater than published sequence {floor}")
    print(f"release sequence {args.version_code} is newer than published sequence {floor}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
