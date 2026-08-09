#!/usr/bin/env python3
"""Verify the complete debug APK set produced by the canonical quality gate."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path, PurePosixPath


APPLICATION_ID = "com.yaakovch.fleet"
ABIS = ("arm64-v8a", "x86_64")
VERSION_NAME = re.compile(r"\d+\.\d+\.\d+-agentfleet\.\d+(?:\+[0-9A-Za-z.-]+)?")


class ApkVerificationError(ValueError):
    """Raised when debug outputs are incomplete or internally inconsistent."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ApkVerificationError(message)


def properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        require(bool(separator and key and value), f"invalid version property: {raw_line}")
        require(key not in values, f"duplicate version property: {key}")
        values[key] = value
    return values


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def expected_version(root: Path) -> tuple[str, int, str]:
    source = properties(root / "app" / "version.properties")
    name = os.environ.get("TERMUX_APP_VERSION_NAME", source.get("VERSION_NAME", ""))
    code_text = os.environ.get("TERMUX_APP_VERSION_CODE", source.get("VERSION_CODE", ""))
    tag = os.environ.get("TERMUX_APK_VERSION_TAG", "") or "debug"
    require(VERSION_NAME.fullmatch(name) is not None, "debug APK versionName is invalid")
    require(code_text.isdigit() and int(code_text) > 0, "debug APK versionCode is invalid")
    require(re.fullmatch(r"[0-9A-Za-z.+_-]+", tag) is not None, "debug APK filename tag is invalid")
    return name, int(code_text), tag


def expected_elements(tag: str) -> dict[str, tuple[str, tuple[tuple[str, str], ...]]]:
    return {
        "universal": (f"agent-fleet_{tag}_universal.apk", ()),
        "arm64-v8a": (
            f"agent-fleet_{tag}_arm64-v8a.apk",
            (("ABI", "arm64-v8a"),),
        ),
        "x86_64": (
            f"agent-fleet_{tag}_x86_64.apk",
            (("ABI", "x86_64"),),
        ),
    }


def parse_outputs(output_dir: Path, version_name: str, version_code: int, tag: str) -> dict[str, Path]:
    metadata_path = output_dir / "output-metadata.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    require(metadata.get("version") == 3, "unsupported APK output metadata version")
    require(metadata.get("applicationId") == APPLICATION_ID, "APK output metadata has the wrong application ID")
    require(metadata.get("variantName") == "debug", "APK output metadata is not for the debug variant")
    elements = metadata.get("elements")
    require(isinstance(elements, list) and len(elements) == 3, "debug output must contain exactly three APK records")
    expected = expected_elements(tag)
    found: dict[str, Path] = {}
    for element in elements:
        require(isinstance(element, dict), "APK output element is invalid")
        filters = element.get("filters")
        require(isinstance(filters, list), "APK output filters are invalid")
        normalized_filters = tuple(
            (item.get("filterType"), item.get("value"))
            for item in filters
            if isinstance(item, dict)
        )
        if not normalized_filters:
            abi = "universal"
        elif len(normalized_filters) == 1 and normalized_filters[0][0] == "ABI":
            abi = normalized_filters[0][1]
        else:
            raise ApkVerificationError(f"unexpected APK filters: {normalized_filters}")
        require(abi in expected and abi not in found, f"unexpected or duplicate APK ABI: {abi}")
        expected_file, expected_filters = expected[abi]
        require(normalized_filters == expected_filters, f"APK filter mismatch for {abi}")
        require(element.get("outputFile") == expected_file, f"APK filename mismatch for {abi}")
        require(element.get("versionName") == version_name, f"APK versionName mismatch for {abi}")
        require(element.get("versionCode") == version_code, f"APK versionCode mismatch for {abi}")
        found[abi] = output_dir / expected_file
    require(set(found) == set(expected), "debug APK ABI set is incomplete")
    return found


def validate_archive(path: Path, abi: str) -> dict[str, object]:
    require(path.is_file() and 1 <= path.stat().st_size <= 768 * 1024 * 1024, f"APK size is unsafe: {path.name}")
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        require(len(names) == len(set(names)), f"APK has duplicate entries: {path.name}")
        for name in names:
            parsed = PurePosixPath(name)
            require(
                name and not name.startswith("/") and "\\" not in name and ".." not in parsed.parts,
                f"APK has an unsafe entry: {path.name}",
            )
        require(archive.testzip() is None, f"APK CRC verification failed: {path.name}")
        required = {
            "AndroidManifest.xml",
            "classes.dex",
            "assets/agent-fleet/embedded-runtime-v1.json",
        }
        require(required <= set(names), f"APK is missing required application/runtime entries: {path.name}")
        packaged_abis = {
            parts[1]
            for name in names
            if len(parts := name.split("/")) >= 3 and parts[0] == "lib"
        }
        if abi == "universal":
            require(set(ABIS) <= packaged_abis, "universal APK is missing a supported ABI")
        else:
            require(packaged_abis == {abi}, f"{abi} split contains an unexpected native ABI")
    return {
        "file": path.name,
        "sha256": file_sha256(path),
        "size": path.stat().st_size,
    }


def locate_build_tools(sdk: Path) -> tuple[Path, Path]:
    def version_key(path: Path) -> tuple[tuple[int, object], ...]:
        return tuple(
            (1, int(value)) if value.isdigit() else (0, value)
            for value in re.split(r"[.-]", path.name)
        )

    candidates = sorted(
        (path for path in (sdk / "build-tools").iterdir() if path.is_dir()),
        key=version_key,
    )
    for directory in reversed(candidates):
        apksigner = directory / "apksigner"
        aapt2 = directory / "aapt2"
        if apksigner.is_file() and aapt2.is_file():
            return apksigner, aapt2
    raise ApkVerificationError(f"Android APK verification tools were not found under {sdk}")


def verify_android_identity(
    apks: dict[str, Path],
    version_name: str,
    version_code: int,
    sdk: Path,
) -> None:
    apksigner, aapt2 = locate_build_tools(sdk)
    expected_package = (
        f"package: name='{APPLICATION_ID}' versionCode='{version_code}' versionName='{version_name}'"
    )
    for abi, apk in apks.items():
        signed = subprocess.run(
            [str(apksigner), "verify", "--verbose", str(apk)],
            check=False,
            text=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )
        require(signed.returncode == 0, f"APK signature verification failed for {abi}")
        badging = subprocess.run(
            [str(aapt2), "dump", "badging", str(apk)],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        require(badging.returncode == 0, f"APK manifest inspection failed for {abi}")
        require(
            any(line.startswith(expected_package) for line in badging.stdout.splitlines()),
            f"APK manifest identity/version mismatch for {abi}",
        )


def verify(root: Path, *, sdk_tools: bool = True) -> dict[str, object]:
    version_name, version_code, tag = expected_version(root)
    output_dir = root / "app" / "build" / "outputs" / "apk" / "debug"
    apks = parse_outputs(output_dir, version_name, version_code, tag)
    artifacts = {abi: validate_archive(path, abi) for abi, path in sorted(apks.items())}
    if sdk_tools:
        sdk_value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        require(bool(sdk_value), "ANDROID_HOME or ANDROID_SDK_ROOT is required for APK verification")
        verify_android_identity(apks, version_name, version_code, Path(sdk_value).expanduser().resolve())
    return {
        "applicationId": APPLICATION_ID,
        "versionName": version_name,
        "versionCode": version_code,
        "artifacts": artifacts,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help="Android repository root",
    )
    args = parser.parse_args()
    print(json.dumps(verify(args.root.resolve()), sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, json.JSONDecodeError, zipfile.BadZipFile, ApkVerificationError) as error:
        raise SystemExit(f"verify-debug-apks: {error}")
