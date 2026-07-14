#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 6 ]] || {
  echo "usage: $0 WTMUX_REPOSITORY VERSION SEQUENCE HTTPS_ARTIFACT_URL MIN_APP_VERSION_CODE CREATED_AT" >&2
  exit 2
}
wtmux_repo="$(cd "$1" && pwd)"
version="$2"
sequence="$3"
artifact_url="$4"
min_app="$5"
created_at="$6"
[[ "$sequence" =~ ^[1-9][0-9]*$ && "$min_app" =~ ^[1-9][0-9]*$ ]] || { echo "sequence and app version must be positive integers" >&2; exit 2; }
[[ "$artifact_url" == https://* ]] || { echo "artifact URL must use HTTPS" >&2; exit 2; }
[[ -x "$wtmux_repo/scripts/wtmux-runtime" && -x "$wtmux_repo/scripts/wtmux-runtime-release" ]] || {
  echo "wtmux repository does not contain runtime release tools" >&2
  exit 1
}

android_repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
signing_dir="${AGENT_FLEET_SIGNING_DIR:-$HOME/.local/share/agent-fleet/signing}"
private_key="$signing_dir/agent-fleet-runtime-ed25519.pem"
public_key="$signing_dir/agent-fleet-runtime-ed25519.pub.pem"
[[ -f "$private_key" && -f "$public_key" ]] || { echo "runtime signing key is missing" >&2; exit 1; }
[[ -z "$(git -C "$wtmux_repo" status --porcelain)" ]] || { echo "wtmux checkout must be clean" >&2; exit 1; }

output="$android_repo/dist/runtime/$version"
mkdir -p "$output"
build="$($wtmux_repo/scripts/wtmux-runtime build --version "$version" --output "$output" --source-root "$wtmux_repo")"
bundle="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["bundle"])' "$build")"
sha="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["sha256"])' "$build")"
size="$(stat -c '%s' "$bundle")"
[[ "$artifact_url" == */"$(basename "$bundle")" ]] || { echo "artifact URL file name must match $(basename "$bundle")" >&2; exit 2; }

"$wtmux_repo/scripts/wtmux-runtime-release" sign \
  --private-key "$private_key" --public-key "$public_key" \
  --sequence "$sequence" --version "$version" --protocol-version 2 \
  --artifact-url "$artifact_url" --sha256 "$sha" --size "$size" \
  --min-app-version-code "$min_app" --created-at "$created_at" \
  --output "$output/runtime-manifest.json"
"$wtmux_repo/scripts/wtmux-runtime-release" verify \
  --public-key "$public_key" --manifest "$output/runtime-manifest.json" >"$output/verified-payload.json"
(cd "$output" && sha256sum "$(basename "$bundle")" runtime-manifest.json verified-payload.json >SHA256SUMS)
echo "signed runtime hotfix: $output"
