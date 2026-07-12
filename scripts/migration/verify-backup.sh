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
listing="$(mktemp)"
trap 'rm -f "$listing"' EXIT
for name in complete home; do
  archive="$directory/termux-$name.tar.gz.enc"
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:AGENT_FLEET_BACKUP_PASSWORD -in "$archive" |
    tar -tzf - >"$listing"
  first="$(sed -n '1p' "$listing")"
  [[ "$first" == home || "$first" == home/ ]] || { echo "$name archive does not begin with home" >&2; exit 1; }
  awk '
    /^\// || /(^|\/)\.\.($|\/)/ { unsafe=1 }
    END { exit unsafe ? 1 : 0 }
  ' "$listing" || { echo "$name archive contains an unsafe path" >&2; exit 1; }
  if [[ "$name" == complete ]]; then
    awk 'BEGIN { found=0 } /^usr(\/|$)/ { found=1 } END { exit found ? 0 : 1 }' "$listing" || {
      echo "complete archive does not contain usr" >&2
      exit 1
    }
  elif grep -Evq '^home(/|$)' "$listing"; then
    echo "portable archive contains paths outside home" >&2
    exit 1
  fi
done
echo "backup checksums, password, and archive structure verified"
