#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 3 ]] || { echo "usage: $0 VERSION_NAME VERSION_CODE HTTPS_BASE_URL" >&2; exit 2; }
version_name="$1"
version_code="$2"
base_url="${3%/}"
[[ "$version_code" =~ ^[1-9][0-9]*$ ]] || { echo "version code must be a positive integer" >&2; exit 2; }
[[ "$base_url" == https://* ]] || { echo "release URL must use HTTPS" >&2; exit 2; }

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
signing_dir="${AGENT_FLEET_SIGNING_DIR:-$HOME/.local/share/agent-fleet/signing}"
keystore="${AGENT_FLEET_KEYSTORE:-$signing_dir/agent-fleet-release.jks}"
alias_name="${AGENT_FLEET_KEY_ALIAS:-agent-fleet}"
[[ -f "$keystore" ]] || { echo "missing release keystore: $keystore" >&2; exit 1; }

if [[ -z "${AGENT_FLEET_STORE_PASSWORD:-}" ]]; then
  read -r -s -p "Release-key password: " AGENT_FLEET_STORE_PASSWORD
  echo
fi
export AGENT_FLEET_STORE_PASSWORD
export AGENT_FLEET_KEY_PASSWORD="${AGENT_FLEET_KEY_PASSWORD:-$AGENT_FLEET_STORE_PASSWORD}"

sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk" ]]; then
  for candidate in /mnt/c/Users/*/AppData/Local/AgentFleetAndroid/sdk; do
    if [[ -d "$candidate/build-tools" ]]; then sdk="$candidate"; break; fi
  done
fi
[[ -n "$sdk" && -d "$sdk/build-tools" ]] || { echo "set ANDROID_SDK_ROOT to an Android SDK" >&2; exit 1; }
build_tools="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
apksigner_jar="$build_tools/lib/apksigner.jar"
[[ -f "$apksigner_jar" ]] || { echo "missing apksigner.jar in $build_tools" >&2; exit 1; }

export TERMUX_APP_VERSION_NAME="$version_name"
export TERMUX_APP_VERSION_CODE="$version_code"
export TERMUX_APK_VERSION_TAG="$version_name"
export TERMUX_SPLIT_APKS_FOR_RELEASE_BUILDS=0
if [[ "$sdk" == /mnt/* ]] && command -v cmd.exe >/dev/null; then
  windows_repo="$(wslpath -w "$repo")"
  windows_java_home="${AGENT_FLEET_WINDOWS_JAVA_HOME:-}"
  if [[ -z "$windows_java_home" ]]; then
    windows_jdk=""
    for candidate in /mnt/c/Users/*/AppData/Local/AgentFleetAndroid/jdk/*/bin/java.exe; do
      if [[ -f "$candidate" ]]; then windows_jdk="$candidate"; break; fi
    done
    [[ -n "$windows_jdk" ]] || { echo "set AGENT_FLEET_WINDOWS_JAVA_HOME to JDK 17" >&2; exit 1; }
    windows_java_home="$(wslpath -w "$(dirname "$(dirname "$windows_jdk")")")"
  fi
  cmd.exe /d /c "cd /d $windows_repo && set JAVA_HOME=$windows_java_home&& set TERMUX_APP_VERSION_NAME=$version_name&& set TERMUX_APP_VERSION_CODE=$version_code&& set TERMUX_APK_VERSION_TAG=$version_name&& set TERMUX_SPLIT_APKS_FOR_RELEASE_BUILDS=0&& gradlew.bat app:assembleRelease --no-daemon"
else
  (cd "$repo" && ./gradlew app:assembleRelease --no-daemon)
fi

unsigned_apk="$(find "$repo/app/build/outputs/apk/release" -maxdepth 1 -name '*universal.apk' -type f -print -quit)"
[[ -f "$unsigned_apk" ]] || { echo "release APK was not produced" >&2; exit 1; }
out_dir="$repo/dist/$version_name"
mkdir -p "$out_dir"
signed_apk="$out_dir/agent-fleet-$version_name.apk"

java -jar "$apksigner_jar" sign \
  --ks "$keystore" --ks-key-alias "$alias_name" \
  --ks-pass env:AGENT_FLEET_STORE_PASSWORD \
  --key-pass env:AGENT_FLEET_KEY_PASSWORD \
  --out "$signed_apk" "$unsigned_apk"
java -jar "$apksigner_jar" verify --verbose --print-certs "$signed_apk" >"$out_dir/apksigner.txt"

apk_sha="$(sha256sum "$signed_apk" | awk '{print $1}')"
apk_size="$(stat -c '%s' "$signed_apk")"
certificate_sha="$(awk -F': ' '/Signer #1 certificate SHA-256 digest:/{print tolower($2); exit}' "$out_dir/apksigner.txt")"
[[ "$certificate_sha" =~ ^[0-9a-f]{64}$ ]] || { echo "could not read APK certificate" >&2; exit 1; }
apk_name="$(basename "$signed_apk")"

python3 - "$out_dir/manifest.json" "$version_name" "$version_code" "$base_url/$apk_name" "$apk_sha" "$certificate_sha" "$apk_size" "$(git -C "$repo" rev-parse HEAD)" <<'PY'
import datetime, json, pathlib, sys
path, version_name, version_code, apk_url, apk_sha, cert_sha, size, commit = sys.argv[1:]
manifest = {
    "schemaVersion": 1,
    "versionCode": int(version_code),
    "versionName": version_name,
    "apkUrl": apk_url,
    "apkSha256": apk_sha,
    "certificateSha256": cert_sha,
    "size": int(size),
    "createdAt": datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z"),
    "gitCommit": commit,
}
pathlib.Path(path).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
PY
(cd "$out_dir" && sha256sum "$apk_name" manifest.json >SHA256SUMS)
echo "signed release: $out_dir"
