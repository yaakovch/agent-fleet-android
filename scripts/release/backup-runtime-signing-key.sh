#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 2 ]] || { echo "usage: $0 BACKUP_DIRECTORY_1 BACKUP_DIRECTORY_2" >&2; exit 2; }
[[ "$1" != "$2" ]] || { echo "backup directories must be different" >&2; exit 2; }

signing_dir="${AGENT_FLEET_SIGNING_DIR:-$HOME/.local/share/agent-fleet/signing}"
private_key="$signing_dir/agent-fleet-runtime-ed25519.pem"
public_key="$signing_dir/agent-fleet-runtime-ed25519.pub.pem"
[[ -f "$private_key" && -f "$public_key" ]] || { echo "runtime signing key is missing from $signing_dir" >&2; exit 1; }

if [[ -z "${AGENT_FLEET_RUNTIME_KEY_PASSWORD:-}" ]]; then
  read -r -s -p "Runtime-key backup password: " AGENT_FLEET_RUNTIME_KEY_PASSWORD
  echo
  read -r -s -p "Repeat runtime-key backup password: " confirmation
  echo
  [[ "$AGENT_FLEET_RUNTIME_KEY_PASSWORD" == "$confirmation" ]] || { echo "passwords do not match" >&2; exit 1; }
fi
[[ ${#AGENT_FLEET_RUNTIME_KEY_PASSWORD} -ge 16 ]] || { echo "password must be at least 16 characters" >&2; exit 1; }

umask 077
runtime_tool="${WTMUX_RUNTIME_RELEASE_TOOL:-$(command -v wtmux-runtime-release || true)}"
[[ -n "$runtime_tool" && -x "$runtime_tool" ]] || { echo "set WTMUX_RUNTIME_RELEASE_TOOL to scripts/wtmux-runtime-release" >&2; exit 1; }
key_id="$("$runtime_tool" key-id --public-key "$public_key")"
for destination in "$1" "$2"; do
  mkdir -p "$destination"
  encrypted="$destination/agent-fleet-runtime-ed25519.pem.enc"
  [[ ! -e "$encrypted" ]] || { echo "refusing to replace $encrypted" >&2; exit 1; }
  printf '%s' "$AGENT_FLEET_RUNTIME_KEY_PASSWORD" | openssl enc -aes-256-cbc -salt -pbkdf2 -iter 250000 \
    -pass stdin -in "$private_key" -out "$encrypted"
  cp -p "$public_key" "$destination/agent-fleet-runtime-ed25519.pub.pem"
  printf '%s\n' "$key_id" >"$destination/runtime-key-id.txt"
  (cd "$destination" && sha256sum agent-fleet-runtime-ed25519.pem.enc agent-fleet-runtime-ed25519.pub.pem runtime-key-id.txt >RUNTIME-SHA256SUMS)
done

echo "two encrypted runtime-key backups created"
echo "runtime key id: $key_id"
