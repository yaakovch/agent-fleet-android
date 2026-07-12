#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 1 ]] || { echo "usage: $0 RELEASE_DIRECTORY" >&2; exit 2; }
directory="$(cd "$1" && pwd)"
(cd "$directory" && sha256sum -c SHA256SUMS)

sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk" ]]; then
  for candidate in /mnt/c/Users/*/AppData/Local/AgentFleetAndroid/sdk; do
    if [[ -d "$candidate/build-tools" ]]; then sdk="$candidate"; break; fi
  done
fi
build_tools="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
apksigner_jar="$build_tools/lib/apksigner.jar"

python3 - "$directory/manifest.json" "$directory" "$apksigner_jar" <<'PY'
import hashlib, json, pathlib, re, subprocess, sys
manifest_path, directory, apksigner = sys.argv[1:]
manifest = json.loads(pathlib.Path(manifest_path).read_text(encoding="utf-8"))
required = {"schemaVersion", "versionCode", "versionName", "apkUrl", "apkSha256", "certificateSha256", "size"}
if not required <= manifest.keys() or manifest["schemaVersion"] != 1:
    raise SystemExit("invalid release manifest")
apk = pathlib.Path(directory, pathlib.PurePosixPath(manifest["apkUrl"]).name)
if apk.stat().st_size != manifest["size"]:
    raise SystemExit("APK size mismatch")
if hashlib.sha256(apk.read_bytes()).hexdigest() != manifest["apkSha256"]:
    raise SystemExit("APK checksum mismatch")
result = subprocess.run(["java", "-jar", apksigner, "verify", "--print-certs", str(apk)], check=True, text=True, capture_output=True)
match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})", result.stdout)
if not match or match.group(1).lower() != manifest["certificateSha256"]:
    raise SystemExit("APK certificate mismatch")
print(f"verified {manifest['versionName']} ({manifest['versionCode']})")
PY
