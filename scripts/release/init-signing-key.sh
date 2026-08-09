#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 BACKUP_DIRECTORY_1 BACKUP_DIRECTORY_2" >&2
  exit 2
}

[[ $# -eq 2 ]] || usage
[[ "$1" != "$2" ]] || { echo "backup directories must be different" >&2; exit 2; }

signing_dir="${AGENT_FLEET_SIGNING_DIR:-$HOME/.local/share/agent-fleet/signing}"
keystore="$signing_dir/agent-fleet-release.jks"
alias_name="${AGENT_FLEET_KEY_ALIAS:-agent-fleet}"
umask 077
mkdir -p "$signing_dir" "$1" "$2"
[[ ! -e "$keystore" ]] || { echo "refusing to replace $keystore" >&2; exit 1; }

if [[ -z "${AGENT_FLEET_STORE_PASSWORD:-}" ]]; then
  read -r -s -p "New release-key password: " AGENT_FLEET_STORE_PASSWORD
  echo
  read -r -s -p "Repeat release-key password: " confirmation
  echo
  [[ "$AGENT_FLEET_STORE_PASSWORD" == "$confirmation" ]] || { echo "passwords do not match" >&2; exit 1; }
fi
[[ ${#AGENT_FLEET_STORE_PASSWORD} -ge 16 ]] || { echo "password must be at least 16 characters" >&2; exit 1; }

env AGENT_FLEET_STORE_PASSWORD="$AGENT_FLEET_STORE_PASSWORD" keytool -genkeypair -noprompt \
  -keystore "$keystore" \
  -storepass:env AGENT_FLEET_STORE_PASSWORD \
  -keypass:env AGENT_FLEET_STORE_PASSWORD \
  -alias "$alias_name" \
  -keyalg RSA -keysize 4096 -validity 9125 \
  -dname "CN=Agent Fleet Private Release, OU=Private Distribution, O=Agent Fleet"

fingerprint="$(env AGENT_FLEET_STORE_PASSWORD="$AGENT_FLEET_STORE_PASSWORD" \
  keytool -list -v -keystore "$keystore" -storepass:env AGENT_FLEET_STORE_PASSWORD \
  -alias "$alias_name" | awk -F': ' '/SHA256:/{gsub(":", "", $2); print tolower($2); exit}')"
[[ "$fingerprint" =~ ^[0-9a-f]{64}$ ]] || { echo "could not read certificate fingerprint" >&2; exit 1; }
printf '%s\n' "$fingerprint" >"$signing_dir/certificate-sha256.txt"
chmod 600 "$signing_dir/certificate-sha256.txt"

for destination in "$1" "$2"; do
  cp -p "$keystore" "$destination/agent-fleet-release.jks"
  cp -p "$signing_dir/certificate-sha256.txt" "$destination/certificate-sha256.txt"
  (cd "$destination" && sha256sum agent-fleet-release.jks certificate-sha256.txt >SHA256SUMS)
done

echo "release key created at $keystore"
echo "two encrypted keystore backups created; store the password separately"
echo "certificate sha256: $fingerprint"
