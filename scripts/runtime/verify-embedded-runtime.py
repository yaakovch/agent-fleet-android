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


APPLICATION_ID = "com.yaakovch.fleet"
PREFIX = "/data/data/com.yaakovch.fleet/files/usr"
PACKAGE_REPOSITORY = "https://github.com/yaakovch/agent-fleet-termux-packages"
UPSTREAM_COMMIT = "c7ca367ba4271dd58dee1bdc220899dda7dc4a71"
ROOT_PACKAGES = [
    "bash", "ca-certificates", "coreutils", "curl", "findutils", "git", "grep", "gzip",
    "openssh", "procps", "python", "sed", "tar", "termux-tools",
]
PIN_FIELDS = {
    "schemaVersion", "applicationId", "prefix", "architecture", "upstreamCommit", "forkCommit",
    "releaseTag", "url", "file", "sha256", "size", "bootstrapFile", "bootstrapSha256",
    "bootstrapSize", "packageLockSha256", "packageLockSize", "sbomSha256", "sbomSize",
    "packageCount", "packagePayloadSize",
}


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


def runtime_pin(path: Path, architecture: str) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    expected_file = f"agent-fleet-runtime-{architecture}.zip"
    if (
        set(value) != PIN_FIELDS or value.get("schemaVersion") != 1
        or value.get("applicationId") != APPLICATION_ID or value.get("prefix") != PREFIX
        or value.get("architecture") != architecture or value.get("upstreamCommit") != UPSTREAM_COMMIT
        or not re.fullmatch(r"[a-f0-9]{40}", value.get("forkCommit", ""))
        or not re.fullmatch(r"agent-fleet-runtime-[A-Za-z0-9._-]+", value.get("releaseTag", ""))
        or value.get("file") != expected_file or value.get("bootstrapFile") != f"bootstrap-{architecture}.zip"
        or value.get("url") != f"{PACKAGE_REPOSITORY}/releases/download/{value.get('releaseTag')}/{expected_file}"
        or any(not re.fullmatch(r"[a-f0-9]{64}", value.get(field, "")) for field in (
            "sha256", "bootstrapSha256", "packageLockSha256", "sbomSha256",
        ))
        or any(type(value.get(field)) is not int or value[field] < 1 for field in (
            "size", "bootstrapSize", "packageLockSize", "sbomSize", "packageCount", "packagePayloadSize",
        ))
    ):
        raise ValueError(f"runtime pin is invalid: {architecture}")
    return value


def verify(root: Path) -> dict:
    pins_root = root.parents[2] / "runtime-pins"
    pins = {
        architecture: runtime_pin(pins_root / f"agent-fleet-runtime-{architecture}.json", architecture)
        for architecture in ("aarch64", "x86_64")
    }
    if any(len({pin[field] for pin in pins.values()}) != 1 for field in ("releaseTag", "upstreamCommit", "forkCommit")):
        raise ValueError("runtime architecture pins do not share one source release")

    descriptor_path = root / "embedded-runtime-v1.json"
    descriptor = json.loads(descriptor_path.read_text(encoding="utf-8"))
    if set(descriptor) != {
        "schemaVersion", "baselineVersion", "sourceRepository", "wtmuxCommit",
        "contractPackageVersion", "components", "protocolVersion", "supportedAbis",
        "runtime", "registry", "packageLock", "sbom", "trustedRuntimeKeys",
    } or descriptor["schemaVersion"] != 1:
        raise ValueError("embedded-runtime-v1 fields are invalid")
    if not re.fullmatch(r"git-[a-f0-9]{7}", descriptor["baselineVersion"]):
        raise ValueError("embedded baseline version is invalid")
    if not re.fullmatch(r"[a-f0-9]{40}", descriptor["wtmuxCommit"]):
        raise ValueError("embedded wtmux commit is invalid")
    if descriptor["baselineVersion"] != "git-" + descriptor["wtmuxCommit"][:7]:
        raise ValueError("embedded baseline version and wtmux commit disagree")
    if (
        descriptor["sourceRepository"] != "https://github.com/yaakovch/wtmux"
        or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,127}", descriptor["contractPackageVersion"])
        or set(descriptor["components"]) != {"clientRuntime", "hostRuntime", "providerAdapters", "contracts"}
    ):
        raise ValueError("embedded runtime component metadata is invalid")
    for name, component in descriptor["components"].items():
        if (
            set(component) != {"sequence", "version"} or type(component["sequence"]) is not int
            or component["sequence"] < 1
            or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,127}", component["version"])
        ):
            raise ValueError(f"embedded runtime component is invalid: {name}")
    if (
        any(descriptor["components"][name]["version"] != descriptor["baselineVersion"]
            for name in ("clientRuntime", "hostRuntime", "providerAdapters"))
        or descriptor["components"]["contracts"]["version"] != descriptor["contractPackageVersion"]
    ):
        raise ValueError("embedded runtime component versions disagree")
    if descriptor["protocolVersion"] != 2 or descriptor["supportedAbis"] != ["arm64-v8a"]:
        raise ValueError("embedded protocol or ABI declaration is unexpected")

    runtime_value = descriptor["runtime"]
    if set(runtime_value) != {"file", "sha256", "size", "formatVersion", "sbomSha256", "licenseSha256"}:
        raise ValueError("embedded wtmux runtime descriptor fields are invalid")
    if (
        runtime_value["formatVersion"] != 2
        or not re.fullmatch(r"[a-f0-9]{64}", runtime_value["sbomSha256"])
        or not re.fullmatch(r"[a-f0-9]{64}", runtime_value["licenseSha256"])
    ):
        raise ValueError("embedded wtmux runtime metadata is invalid")
    runtime = checked_file(
        root, {key: runtime_value[key] for key in ("file", "sha256", "size")},
        32 * 1024 * 1024,
    )
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
        if (
            set(manifest) != {"formatVersion", "version", "components", "source", "target", "files"}
            or manifest.get("formatVersion") != 2 or manifest.get("version") != descriptor["baselineVersion"]
            or manifest.get("components") != descriptor["components"]
            or manifest.get("source") != {
                "schemaVersion": 1,
                "repository": descriptor["sourceRepository"],
                "commit": descriptor["wtmuxCommit"],
                "license": "NOASSERTION",
                "contractPackageVersion": descriptor["contractPackageVersion"],
            }
            or manifest.get("target") != {
                "platform": "termux",
                "architecture": "arm64",
                "prefix": "/data/data/com.yaakovch.fleet/files/home/.local/share/agent-fleet/wtmux",
            }
        ):
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
        entries = {item["path"]: item for item in manifest["files"]}
        if (
            entries.get("runtime.spdx.json", {}).get("sha256") != runtime_value["sbomSha256"]
            or entries.get("runtime-license.txt", {}).get("sha256") != runtime_value["licenseSha256"]
            or "fleet/contracts/schemas/release-set-v1.schema.json" not in entries
        ):
            raise ValueError("runtime archive SBOM, license, or contracts are missing")

    registry_value = descriptor["registry"]
    if set(registry_value) != {"file", "sha256", "size"}:
        raise ValueError("embedded registry descriptor fields are invalid")
    registry = checked_file(root, registry_value, 16 * 1024 * 1024)
    with tarfile.open(registry, "r:") as archive:
        members = archive.getmembers()
        names = [member.name for member in members]
        if (
            not members or len(names) != len(set(names))
            or any(not member.isfile() for member in members)
            or "registry-manifest.json" not in names
        ):
            raise ValueError("embedded registry archive members are invalid")
        manifest_handle = archive.extractfile("registry-manifest.json")
        if manifest_handle is None:
            raise ValueError("embedded registry manifest is unreadable")
        registry_manifest = json.load(manifest_handle)
        if (
            set(registry_manifest) != {"formatVersion", "schemaVersion", "records"}
            or registry_manifest["formatVersion"] != 1
            or registry_manifest["schemaVersion"] != 1
            or not isinstance(registry_manifest["records"], list)
            or not 1 <= len(registry_manifest["records"]) <= 256
        ):
            raise ValueError("embedded registry manifest is invalid")
        expected_registry_members = {"registry-manifest.json"}
        registry_ids = set()
        for item in registry_manifest["records"]:
            if (
                set(item) != {"id", "path", "sha256", "size"}
                or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", item["id"])
                or item["id"] in registry_ids
                or item["path"] != f"machines/{item['id']}.json"
                or not re.fullmatch(r"[a-f0-9]{64}", item["sha256"])
                or type(item["size"]) is not int
                or not 1 <= item["size"] <= 64 * 1024
            ):
                raise ValueError("embedded registry record is invalid")
            registry_ids.add(item["id"])
            expected_registry_members.add(item["path"])
            payload = archive.extractfile(item["path"]).read()
            if len(payload) != item["size"] or hashlib.sha256(payload).hexdigest() != item["sha256"]:
                raise ValueError(f"embedded registry member verification failed: {item['id']}")
        if set(names) != expected_registry_members:
            raise ValueError("embedded registry contents do not match its manifest")

    package_value = descriptor["packageLock"]
    if set(package_value) != {"file", "sha256", "size", "packages", "payloadSize"}:
        raise ValueError("package lock descriptor fields are invalid")
    package_lock = checked_file(root, {key: package_value[key] for key in ("file", "sha256", "size")}, 2 * 1024 * 1024)
    lock = json.loads(package_lock.read_text(encoding="utf-8"))
    packages = lock.get("packages", [])
    expected_lock_fields = {
        "schemaVersion", "applicationId", "prefix", "architecture", "repository", "bundleUrl",
        "upstreamCommit", "forkCommit", "rootPackages", "totalSize", "packages",
    }
    bundle_url = lock.get("bundleUrl", "")
    parsed_bundle = urlsplit(bundle_url)
    if (
        set(lock) != expected_lock_fields or lock.get("schemaVersion") != 2
        or lock.get("applicationId") != APPLICATION_ID or lock.get("prefix") != PREFIX
        or lock.get("architecture") != "aarch64" or lock.get("repository") != PACKAGE_REPOSITORY
        or parsed_bundle.scheme != "https" or parsed_bundle.netloc != "github.com"
        or not bundle_url.startswith(PACKAGE_REPOSITORY + "/releases/download/agent-fleet-runtime-")
        or not bundle_url.endswith("/agent-fleet-runtime-aarch64.zip")
        or lock.get("upstreamCommit") != UPSTREAM_COMMIT
        or not re.fullmatch(r"[a-f0-9]{40}", lock.get("forkCommit", ""))
        or len(packages) != package_value["packages"] or sum(item["size"] for item in packages) != package_value["payloadSize"]
        or lock.get("totalSize") != package_value["payloadSize"]
    ):
        raise ValueError("package lock summary does not match embedded descriptor")
    arm64_pin = pins["aarch64"]
    if (
        lock["bundleUrl"] != arm64_pin["url"] or lock["upstreamCommit"] != arm64_pin["upstreamCommit"]
        or lock["forkCommit"] != arm64_pin["forkCommit"]
        or package_value["sha256"] != arm64_pin["packageLockSha256"]
        or package_value["size"] != arm64_pin["packageLockSize"]
        or package_value["packages"] != arm64_pin["packageCount"]
        or package_value["payloadSize"] != arm64_pin["packagePayloadSize"]
        or descriptor["sbom"]["sha256"] != arm64_pin["sbomSha256"]
        or descriptor["sbom"]["size"] != arm64_pin["sbomSize"]
    ):
        raise ValueError("embedded arm64 metadata does not match its immutable runtime pin")
    root_packages = lock.get("rootPackages")
    if (
        root_packages != ROOT_PACKAGES
    ):
        raise ValueError("package lock roots are invalid")
    names = set()
    files = set()
    for item in packages:
        if set(item) != {
            "name", "version", "architecture", "file", "sha256", "size", "sourcePackage",
            "recipe", "homepage", "license", "description",
        }:
            raise ValueError("package lock record fields are invalid")
        if item["name"] in names or item["file"] in files:
            raise ValueError("package lock contains a duplicate")
        names.add(item["name"])
        files.add(item["file"])
        homepage = item.get("homepage")
        homepage_url = urlsplit(homepage) if isinstance(homepage, str) else None
        if (
            not re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", item["name"])
            or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+~-]*\.deb", item["file"])
            or not re.fullmatch(r"[a-f0-9]{64}", item["sha256"])
            or item["architecture"] not in {"aarch64", "all"}
            or not re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", item["sourcePackage"])
            or not re.fullmatch(r"(?:NOASSERTION|(?:packages|root-packages|x11-packages)/[a-z0-9][a-z0-9+.-]*/build\.sh)", item["recipe"])
            or not isinstance(homepage, str) or len(homepage) > 2048
            or (homepage != "NOASSERTION" and (
                homepage_url.scheme not in {"http", "https"} or not homepage_url.hostname
                or homepage_url.username
            ))
            or not isinstance(item["license"], str) or not (1 <= len(item["license"]) <= 256)
            or not isinstance(item["description"], str) or len(item["description"]) > 4096
        ):
            raise ValueError(f"package lock record is invalid: {item.get('name', 'unknown')}")
    if not set(root_packages) <= names:
        raise ValueError("package lock roots are missing from its closure")

    sbom_path = checked_file(root, descriptor["sbom"], 2 * 1024 * 1024)
    sbom = json.loads(sbom_path.read_text(encoding="utf-8"))
    if (
        set(sbom) != {
            "spdxVersion", "dataLicense", "SPDXID", "name", "documentNamespace",
            "creationInfo", "packages", "relationships",
        }
        or sbom.get("spdxVersion") != "SPDX-2.3" or sbom.get("dataLicense") != "CC0-1.0"
        or sbom.get("SPDXID") != "SPDXRef-DOCUMENT"
        or not isinstance(sbom.get("packages"), list) or len(sbom["packages"]) != len(packages)
    ):
        raise ValueError("embedded SPDX SBOM summary is invalid")
    spdx_packages = {}
    for item in sbom["packages"]:
        if not isinstance(item, dict):
            raise ValueError("embedded SPDX SBOM package record is invalid")
        checksums = item.get("checksums")
        if (
            not isinstance(item.get("name"), str) or item["name"] in spdx_packages
            or not isinstance(checksums, list) or len(checksums) != 1
            or checksums[0].get("algorithm") != "SHA256"
            or not re.fullmatch(r"[a-f0-9]{64}", checksums[0].get("checksumValue", ""))
        ):
            raise ValueError("embedded SPDX SBOM package record is invalid")
        spdx_packages[item["name"]] = (item.get("versionInfo"), checksums[0]["checksumValue"])
    if spdx_packages != {item["name"]: (item["version"], item["sha256"]) for item in packages}:
        raise ValueError("embedded SPDX SBOM does not match the package lock")
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
    expected_source_files = {
        "embedded-runtime-v1.json", descriptor["runtime"]["file"], descriptor["registry"]["file"],
        descriptor["packageLock"]["file"],
        descriptor["sbom"]["file"], *(item["file"] for item in keys),
    }
    actual_source_files = {path.name for path in root.iterdir() if path.is_file() and not path.is_symlink()}
    if actual_source_files != expected_source_files or any(path.is_dir() or path.is_symlink() for path in root.iterdir()):
        raise ValueError("embedded runtime source directory contains stale or unsafe inputs")
    return {
        "baselineVersion": descriptor["baselineVersion"],
        "wtmuxCommit": descriptor["wtmuxCommit"],
        "contractPackageVersion": descriptor["contractPackageVersion"],
        "components": descriptor["components"],
        "registryRelease": descriptor["registry"]["sha256"][:16],
        "registryRecords": len(registry_manifest["records"]),
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
