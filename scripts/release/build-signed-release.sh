#!/usr/bin/env bash
set -euo pipefail

preflight_only=0
if [[ "${1:-}" == "--preflight-only" ]]; then
  preflight_only=1
  shift
fi
[[ $# -eq 3 ]] || { echo "usage: $0 [--preflight-only] VERSION_NAME VERSION_CODE HTTPS_BASE_URL" >&2; exit 2; }
version_name="$1"
version_code="$2"
base_url="${3%/}"
[[ "$version_code" =~ ^[1-9][0-9]*$ ]] || { echo "version code must be a positive integer" >&2; exit 2; }
[[ "$base_url" == https://* ]] || { echo "release URL must use HTTPS" >&2; exit 2; }

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$repo/scripts/release/release-common.sh"
agent_fleet_release_read_version "$repo"
[[ "$version_name" == "$AGENT_FLEET_RELEASE_VERSION_NAME" && "$version_code" == "$AGENT_FLEET_RELEASE_VERSION_CODE" ]] || {
  echo "release arguments must match app/version.properties" >&2
  exit 1
}
signing_dir="${AGENT_FLEET_SIGNING_DIR:-$HOME/.local/share/agent-fleet/signing}"
keystore="${AGENT_FLEET_KEYSTORE:-$signing_dir/agent-fleet-release.jks}"
alias_name="${AGENT_FLEET_KEY_ALIAS:-agent-fleet}"
agent_fleet_release_load_credentials
agent_fleet_release_check_keystore "$repo"
agent_fleet_release_check_git "$repo"
agent_fleet_release_find_sdk
sdk="$AGENT_FLEET_RELEASE_ANDROID_SDK"
java_home="$AGENT_FLEET_RELEASE_JAVA_HOME"
build_tools="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
apksigner_jar="$build_tools/lib/apksigner.jar"
[[ -f "$apksigner_jar" ]] || { echo "missing apksigner.jar in $build_tools" >&2; exit 1; }
if [[ "$preflight_only" == "1" ]]; then
  echo "release preflight passed for $version_name ($version_code)"
  exit 0
fi

"$repo/scripts/runtime/verify-embedded-runtime.py" "$repo/app/src/main/agent-fleet"

export TERMUX_APP_VERSION_NAME="$version_name"
export TERMUX_APP_VERSION_CODE="$version_code"
export TERMUX_APK_VERSION_TAG="$version_name"
export TERMUX_SPLIT_APKS_FOR_RELEASE_BUILDS=1
if [[ "$sdk" == /mnt/* ]] && command -v cmd.exe >/dev/null; then
  windows_repo="$(wslpath -w "$repo")"
  windows_sdk="$(wslpath -w "$sdk")"
  windows_java_home="${AGENT_FLEET_WINDOWS_JAVA_HOME:-}"
  if [[ -z "$windows_java_home" ]]; then
    windows_jdk=""
    for candidate in /mnt/c/Users/*/AppData/Local/AgentFleetAndroid/jdk/*/bin/java.exe; do
      if [[ -f "$candidate" ]]; then windows_jdk="$candidate"; break; fi
    done
    [[ -n "$windows_jdk" ]] || { echo "set AGENT_FLEET_WINDOWS_JAVA_HOME to JDK 17" >&2; exit 1; }
    windows_java_home="$(wslpath -w "$(dirname "$(dirname "$windows_jdk")")")"
  fi
  windows_cmd=(cmd.exe)
  if ! cmd.exe /d /c exit >/dev/null 2>&1; then
    native_cmd="/mnt/c/Windows/System32/cmd.exe"
    [[ -x /init && -x "$native_cmd" ]] || { echo "Windows command runner is unavailable" >&2; exit 1; }
    windows_cmd=(/init "$native_cmd")
  fi
  "${windows_cmd[@]}" /d /c "cd /d $windows_repo && set JAVA_HOME=$windows_java_home&& set ANDROID_SDK_ROOT=$windows_sdk&& set ANDROID_HOME=$windows_sdk&& set TERMUX_APP_VERSION_NAME=$version_name&& set TERMUX_APP_VERSION_CODE=$version_code&& set TERMUX_APK_VERSION_TAG=$version_name&& set TERMUX_SPLIT_APKS_FOR_RELEASE_BUILDS=1&& gradlew.bat app:assembleRelease --no-daemon --console=plain" </dev/null
else
  "$repo/scripts/debug/android-gradle.sh" app:assembleRelease --no-daemon --console=plain
fi

unsigned_arm64="$(find "$repo/app/build/outputs/apk/release" -maxdepth 1 -name '*arm64-v8a.apk' -type f -print -quit)"
unsigned_universal="$(find "$repo/app/build/outputs/apk/release" -maxdepth 1 -name '*universal.apk' -type f -print -quit)"
[[ -f "$unsigned_arm64" && -f "$unsigned_universal" ]] || { echo "arm64 and universal release APKs were not produced" >&2; exit 1; }
out_dir="$repo/dist/$version_name"
mkdir -p "$out_dir"
signed_arm64="$out_dir/agent-fleet-$version_name-arm64-v8a.apk"
signed_universal="$out_dir/agent-fleet-$version_name-universal.apk"

sign_apk() {
  local input="$1"
  local output="$2"
  local report="$3"
  "$java_home/bin/java" -jar "$apksigner_jar" sign \
    --ks "$keystore" --ks-key-alias "$alias_name" \
    --ks-pass env:AGENT_FLEET_STORE_PASSWORD \
    --key-pass env:AGENT_FLEET_KEY_PASSWORD \
    --out "$output" "$input"
  "$java_home/bin/java" -jar "$apksigner_jar" verify --verbose --print-certs "$output" >"$report"
}

sign_apk "$unsigned_arm64" "$signed_arm64" "$out_dir/apksigner-arm64.txt"
sign_apk "$unsigned_universal" "$signed_universal" "$out_dir/apksigner-universal.txt"

arm64_sha="$(sha256sum "$signed_arm64" | awk '{print $1}')"
arm64_size="$(stat -c '%s' "$signed_arm64")"
universal_sha="$(sha256sum "$signed_universal" | awk '{print $1}')"
universal_size="$(stat -c '%s' "$signed_universal")"
certificate_sha="$(awk -F': ' '/Signer #1 certificate SHA-256 digest:/{print tolower($2); exit}' "$out_dir/apksigner-arm64.txt")"
universal_certificate_sha="$(awk -F': ' '/Signer #1 certificate SHA-256 digest:/{print tolower($2); exit}' "$out_dir/apksigner-universal.txt")"
[[ "$certificate_sha" =~ ^[0-9a-f]{64}$ ]] || { echo "could not read APK certificate" >&2; exit 1; }
[[ "$universal_certificate_sha" == "$certificate_sha" ]] || { echo "APK certificates do not match" >&2; exit 1; }
arm64_name="$(basename "$signed_arm64")"
universal_name="$(basename "$signed_universal")"

python3 - "$out_dir/manifest.json" "$version_name" "$version_code" "$base_url/$arm64_name" "$arm64_sha" "$certificate_sha" "$arm64_size" "$(git -C "$repo" rev-parse HEAD)" "$base_url/$universal_name" "$universal_sha" "$universal_size" <<'PY'
import datetime, json, pathlib, sys
path, version_name, version_code, apk_url, apk_sha, cert_sha, size, commit, universal_url, universal_sha, universal_size = sys.argv[1:]
manifest = {
    "schemaVersion": 1,
    "applicationId": "com.yaakovch.fleet",
    "versionCode": int(version_code),
    "versionName": version_name,
    "apkUrl": apk_url,
    "apkSha256": apk_sha,
    "certificateSha256": cert_sha,
    "size": int(size),
    "createdAt": datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z"),
    "gitCommit": commit,
    "artifacts": [
        {"abi": "arm64-v8a", "apkUrl": apk_url, "apkSha256": apk_sha, "size": int(size)},
        {"abi": "universal", "apkUrl": universal_url, "apkSha256": universal_sha, "size": int(universal_size)},
    ],
}
pathlib.Path(path).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
PY
(cd "$out_dir" && sha256sum "$arm64_name" "$universal_name" manifest.json >SHA256SUMS)
echo "signed release: $out_dir"
