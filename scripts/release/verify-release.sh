#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 1 ]] || { echo "usage: $0 RELEASE_DIRECTORY" >&2; exit 2; }
directory="$(cd "$1" && pwd)"
(cd "$directory" && sha256sum -c SHA256SUMS)
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$repo/scripts/release/release-common.sh"
agent_fleet_release_load_certificate_fingerprint "$repo"
java_home="$("$repo/scripts/debug/android-gradle.sh" --print-java-home)"
java_bin="$java_home/bin/java"

sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk" ]]; then
  for candidate in /mnt/c/Users/*/AppData/Local/AgentFleetAndroid/sdk; do
    if [[ -d "$candidate/build-tools" ]]; then sdk="$candidate"; break; fi
  done
fi
build_tools="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
apksigner_jar="$build_tools/lib/apksigner.jar"
aapt2="$build_tools/aapt2"
aapt2_platform="linux"
aapt2_directory="$directory"
windows_cmd=""
if [[ ! -x "$aapt2" && -f "${aapt2}.exe" ]]; then
    aapt2_platform="windows"
    aapt2="$(wslpath -w "${aapt2}.exe")"
    aapt2_directory="$(wslpath -w "$directory")"
    windows_cmd="/mnt/c/Windows/System32/cmd.exe"
    [[ -x /init && -x "$windows_cmd" ]] || { echo "Windows aapt2 runner is unavailable" >&2; exit 1; }
fi
[[ "$aapt2_platform" == "windows" || -x "$aapt2" ]] || { echo "missing aapt2 in $build_tools" >&2; exit 1; }

python3 - "$directory/manifest.json" "$directory" "$apksigner_jar" "$aapt2" "$aapt2_platform" "$aapt2_directory" "$windows_cmd" "$java_bin" "$repo" "$AGENT_FLEET_EXPECTED_CERTIFICATE_SHA256" <<'PY'
import base64, hashlib, importlib.util, io, json, pathlib, re, subprocess, sys, tarfile, zipfile
manifest_path, directory, apksigner, aapt2, aapt2_platform, aapt2_directory, windows_cmd, java_bin, repo, expected_certificate = sys.argv[1:]
manifest = json.loads(pathlib.Path(manifest_path).read_text(encoding="utf-8"))
required = {"schemaVersion", "applicationId", "versionCode", "versionName", "apkUrl", "apkSha256", "certificateSha256", "size"}
if not required <= manifest.keys() or manifest["schemaVersion"] != 1 or manifest["applicationId"] != "com.yaakovch.fleet":
    raise SystemExit("invalid release manifest")
if manifest["certificateSha256"] != expected_certificate:
    raise SystemExit("release manifest certificate does not match the pinned fingerprint")
artifacts = manifest.get("artifacts") or [{
    "abi": "primary", "apkUrl": manifest["apkUrl"], "apkSha256": manifest["apkSha256"], "size": manifest["size"],
}]
if not isinstance(artifacts, list) or {item.get("abi") for item in artifacts} != {"arm64-v8a", "universal"}:
    raise SystemExit("release must contain arm64 and universal APK artifacts")

verifier_spec = importlib.util.spec_from_file_location(
    "agent_fleet_runtime_verifier",
    pathlib.Path(repo) / "scripts/runtime/verify-embedded-runtime.py",
)
if verifier_spec is None or verifier_spec.loader is None:
    raise SystemExit("APK runtime verifier could not be loaded")
runtime_verifier = importlib.util.module_from_spec(verifier_spec)
verifier_spec.loader.exec_module(runtime_verifier)

def resolve_dependencies(records, roots, architecture):
    try:
        runtime_verifier.validate_dependency_closure(records, roots, architecture)
    except ValueError as error:
        raise SystemExit(f"APK dependency closure is invalid: {error}") from error
    return {item["name"]: item["resolvedDependencies"] for item in records}

def archive_runtime(apk):
    with zipfile.ZipFile(apk) as archive:
        prefix = "assets/agent-fleet/"
        descriptor = json.loads(archive.read(prefix + "embedded-runtime-v1.json"))
        if descriptor.get("schemaVersion") != 1 or descriptor.get("supportedAbis") != ["arm64-v8a"]:
            raise SystemExit("APK embedded runtime descriptor is invalid")
        if (
            descriptor.get("sourceRepository") != "https://github.com/yaakovch/wtmux"
            or descriptor.get("baselineVersion") != "git-" + descriptor.get("wtmuxCommit", "")[:7]
            or descriptor.get("runtime", {}).get("formatVersion") != 2
            or not re.fullmatch(
                r"[a-f0-9]{64}",
                descriptor.get("runtime", {}).get("manifestSha256", ""),
            )
            or set(descriptor.get("components", {})) != {
                "clientRuntime", "hostRuntime", "providerAdapters", "contracts",
            }
        ):
            raise SystemExit("APK embedded runtime provenance is invalid")
        runtime_payload = None
        for key in ("runtime", "registry", "packageLock", "sbom"):
            value = descriptor[key]
            payload = archive.read(prefix + value["file"])
            if len(payload) != value["size"] or hashlib.sha256(payload).hexdigest() != value["sha256"]:
                raise SystemExit(f"APK embedded {key} verification failed")
            if key == "runtime":
                runtime_payload = payload
        with tarfile.open(fileobj=io.BytesIO(runtime_payload), mode="r:") as runtime_archive:
            manifest_payload = runtime_archive.extractfile("runtime-manifest.json").read()
        if hashlib.sha256(manifest_payload).hexdigest() != descriptor["runtime"]["manifestSha256"]:
            raise SystemExit("APK runtime manifest binding is invalid")
        lock = json.loads(archive.read(prefix + descriptor["packageLock"]["file"]))
        legacy_lock_fields = {
            "schemaVersion", "applicationId", "prefix", "architecture", "repository", "bundleUrl",
            "upstreamCommit", "forkCommit", "rootPackages", "totalSize", "packages",
        }
        closure_lock_fields = {
            "runtimeRootsSha256", "closureManifestFile", "closureManifestSha256", "closureManifestSize",
        }
        if set(lock) == legacy_lock_fields:
            closure_format = False
        elif set(lock) == legacy_lock_fields | closure_lock_fields:
            closure_format = True
        else:
            raise SystemExit("APK package lock fields are invalid")
        if (lock.get("schemaVersion") != 2 or lock.get("applicationId") != "com.yaakovch.fleet" or
                lock.get("prefix") != "/data/data/com.yaakovch.fleet/files/usr" or
                lock.get("architecture") != "aarch64" or
                lock.get("repository") != "https://github.com/yaakovch/agent-fleet-termux-packages" or
                not lock.get("bundleUrl", "").startswith("https://github.com/yaakovch/agent-fleet-termux-packages/releases/download/agent-fleet-runtime-") or
                not lock.get("bundleUrl", "").endswith("/agent-fleet-runtime-aarch64.zip") or
                lock.get("upstreamCommit") != "c7ca367ba4271dd58dee1bdc220899dda7dc4a71" or
                not re.fullmatch(r"[a-f0-9]{40}", lock.get("forkCommit", "")) or
                lock.get("rootPackages") != [
                    "bash", "ca-certificates", "coreutils", "curl", "findutils", "git", "grep", "gzip",
                    "openssh", "procps", "python", "sed", "tar", "termux-tools",
                ] or
                len(lock["packages"]) != descriptor["packageLock"]["packages"] or
                sum(item["size"] for item in lock["packages"]) != descriptor["packageLock"]["payloadSize"]):
            raise SystemExit("APK package lock count mismatch")
        legacy_package_fields = {
            "name", "version", "architecture", "file", "sha256", "size", "sourcePackage",
            "recipe", "homepage", "license", "description",
        }
        closure_package_fields = {
            "essential", "preDepends", "depends", "provides", "resolvedDependencies",
        }
        package_names = [item.get("name") for item in lock["packages"]]
        package_name_set = set(package_names)
        package_files = [item.get("file") for item in lock["packages"]]
        if (
            len(package_name_set) != len(package_names)
            or len(set(package_files)) != len(package_files)
            or not set(lock["rootPackages"]) <= package_name_set
        ):
            raise SystemExit("APK package lock closure is invalid")
        for item in lock["packages"]:
            expected_fields = legacy_package_fields | (closure_package_fields if closure_format else set())
            if set(item) != expected_fields:
                raise SystemExit("APK package lock record fields are invalid")
            if closure_format and (
                type(item["essential"]) is not bool
                or not all(
                    isinstance(groups, list) and len(groups) <= 128
                    and all(
                        isinstance(group, list) and 1 <= len(group) <= 32
                        and all(isinstance(name, str) and re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", name) for name in group)
                        and len(set(group)) == len(group)
                        for group in groups
                    )
                    for groups in (item["preDepends"], item["depends"])
                )
                or not all(
                    isinstance(values, list) and len(values) <= 128
                    and all(isinstance(name, str) and re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", name) for name in values)
                    and len(set(values)) == len(values)
                    for values in (item["provides"], item["resolvedDependencies"])
                )
                or not set(item["resolvedDependencies"]) <= package_name_set
            ):
                raise SystemExit("APK package dependency attestation is invalid")
            payload = archive.read(prefix + "packages/" + item["file"])
            if len(payload) != item["size"] or hashlib.sha256(payload).hexdigest() != item["sha256"]:
                raise SystemExit(f"APK package verification failed: {item['name']}")
        closure_file = None
        if closure_format:
            resolved = resolve_dependencies(
                lock["packages"], lock["rootPackages"], lock["architecture"]
            )
            if (
                package_names != sorted(package_names)
                or any(item["resolvedDependencies"] != resolved[item["name"]] for item in lock["packages"])
                or lock.get("closureManifestFile") != "agent-fleet-runtime-closure-v1.json"
                or not re.fullmatch(r"[a-f0-9]{64}", lock.get("runtimeRootsSha256", ""))
                or not re.fullmatch(r"[a-f0-9]{64}", lock.get("closureManifestSha256", ""))
                or type(lock.get("closureManifestSize")) is not int
                or not 1 <= lock["closureManifestSize"] <= 2 * 1024 * 1024
            ):
                raise SystemExit("APK closure metadata is invalid")
            closure_file = lock["closureManifestFile"]
            closure_payload = archive.read(prefix + closure_file)
            if (
                len(closure_payload) != lock["closureManifestSize"]
                or hashlib.sha256(closure_payload).hexdigest() != lock["closureManifestSha256"]
            ):
                raise SystemExit("APK closure manifest verification failed")
            closure = json.loads(closure_payload)
            expected_selected = [
                {
                    "name": item["name"],
                    "file": item["file"],
                    "sha256": item["sha256"],
                    "size": item["size"],
                    "resolvedDependencies": item["resolvedDependencies"],
                }
                for item in lock["packages"]
            ]
            if (
                set(closure) != {
                    "schemaVersion", "architecture", "runtimeRootsSha256", "rootPackages",
                    "selectedPackages", "selectedPayloadSize", "excludedPackages", "ignoredFiles",
                }
                or closure.get("schemaVersion") != 1
                or closure.get("architecture") != "aarch64"
                or closure.get("runtimeRootsSha256") != lock["runtimeRootsSha256"]
                or closure.get("rootPackages") != lock["rootPackages"]
                or closure.get("selectedPackages") != expected_selected
                or closure.get("selectedPayloadSize") != lock["totalSize"]
                or not isinstance(closure.get("excludedPackages"), list)
                or not all(
                    isinstance(name, str) and re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", name)
                    for name in closure.get("excludedPackages", [])
                )
                or closure["excludedPackages"] != sorted(set(closure["excludedPackages"]))
                or package_name_set & set(closure["excludedPackages"])
                or not isinstance(closure.get("ignoredFiles"), list)
                or not all(
                    isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+~-]*\.deb", name)
                    for name in closure.get("ignoredFiles", [])
                )
                or closure["ignoredFiles"] != sorted(set(closure["ignoredFiles"]))
            ):
                raise SystemExit("APK closure manifest does not match the package lock")
        for key in descriptor["trustedRuntimeKeys"]:
            payload = archive.read(prefix + key["file"])
            if hashlib.sha256(payload).hexdigest() != key["sha256"]:
                raise SystemExit("APK trusted runtime key verification failed")
        expected_assets = {
            prefix + "embedded-runtime-v1.json", prefix + descriptor["runtime"]["file"],
            prefix + descriptor["registry"]["file"], prefix + descriptor["packageLock"]["file"],
            prefix + descriptor["sbom"]["file"],
            *((prefix + closure_file,) if closure_file else ()),
            *(prefix + key["file"] for key in descriptor["trustedRuntimeKeys"]),
            *(prefix + "packages/" + item["file"] for item in lock["packages"]),
        }
        actual_assets = {name for name in archive.namelist() if name.startswith(prefix) and not name.endswith("/")}
        if actual_assets != expected_assets:
            raise SystemExit("APK contains stale or unexpected embedded runtime assets")
        return descriptor

embedded = None
for item in artifacts:
    if set(item) != {"abi", "apkUrl", "apkSha256", "size"}:
        raise SystemExit("invalid APK artifact descriptor")
    apk = pathlib.Path(directory, pathlib.PurePosixPath(item["apkUrl"]).name)
    if apk.stat().st_size != item["size"] or hashlib.sha256(apk.read_bytes()).hexdigest() != item["apkSha256"]:
        raise SystemExit(f"{item['abi']} APK size or checksum mismatch")
    result = subprocess.run([java_bin, "-jar", apksigner, "verify", "--print-certs", str(apk)], check=True, text=True, capture_output=True)
    match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})", result.stdout)
    if not match or match.group(1).lower() != manifest["certificateSha256"]:
        raise SystemExit(f"{item['abi']} APK certificate mismatch")
    if aapt2_platform == "windows":
        apk_for_aapt = str(pathlib.PureWindowsPath(aapt2_directory, apk.name))
        aapt_command = ["/init", windows_cmd, "/d", "/c", aapt2, "dump", "badging", apk_for_aapt]
    else:
        aapt_command = [aapt2, "dump", "badging", str(apk)]
    badging = subprocess.run(aapt_command, check=True, text=True, capture_output=True).stdout
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.MULTILINE)
    if not package or package.groups() != (manifest["applicationId"], str(manifest["versionCode"]), manifest["versionName"]):
        raise SystemExit(f"{item['abi']} APK identity or version mismatch")
    value = archive_runtime(apk)
    if embedded is not None and embedded != value:
        raise SystemExit("APK artifacts contain different embedded runtimes")
    embedded = value
primary = next(item for item in artifacts if item["abi"] == "arm64-v8a")
if (manifest["apkUrl"], manifest["apkSha256"], manifest["size"]) != (primary["apkUrl"], primary["apkSha256"], primary["size"]):
    raise SystemExit("primary release fields do not select the arm64 APK")
print(f"verified {manifest['versionName']} ({manifest['versionCode']}) runtime={embedded['baselineVersion']}")
PY
python3 "$repo/scripts/release/release_candidate.py" prove \
  "$directory" "$AGENT_FLEET_EXPECTED_CERTIFICATE_SHA256" >/dev/null
