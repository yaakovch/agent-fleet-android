#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 1 ]] || { echo "usage: $0 BACKUP_DIRECTORY" >&2; exit 2; }
directory="$(cd "$1" && pwd)"
(cd "$directory" && sha256sum -c SHA256SUMS)
if [[ -z "${AGENT_FLEET_BACKUP_PASSWORD:-}" ]]; then
  read -r -s -p "Backup password: " AGENT_FLEET_BACKUP_PASSWORD
  echo
fi
export AGENT_FLEET_BACKUP_PASSWORD
for name in complete home; do
  archive="$directory/termux-$name.tar.gz.enc"
  first="$(openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:AGENT_FLEET_BACKUP_PASSWORD -in "$archive" | tar -tzf - | sed -n '1p')"
  [[ "$first" == home || "$first" == home/ ]] || { echo "$name archive does not begin with home" >&2; exit 1; }
done
echo "backup checksums, password, and archive structure verified"
