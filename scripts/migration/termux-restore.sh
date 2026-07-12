#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

[[ $# -eq 2 ]] || { echo "usage: $0 BACKUP_DIRECTORY complete|home" >&2; exit 2; }
backup_dir="$(cd "$1" && pwd)"
mode="$2"
[[ "$mode" == complete || "$mode" == home ]] || { echo "restore mode must be complete or home" >&2; exit 2; }
files_root="${TERMUX_FILES_ROOT:-${PREFIX%/usr}}"
archive="$backup_dir/termux-$mode.tar.gz.enc"
[[ -f "$archive" ]] || { echo "missing $archive" >&2; exit 1; }
(cd "$backup_dir" && sha256sum -c SHA256SUMS)

if [[ -z "${AGENT_FLEET_BACKUP_PASSWORD:-}" ]]; then
  read -r -s -p "Backup password: " AGENT_FLEET_BACKUP_PASSWORD
  echo
fi
export AGENT_FLEET_BACKUP_PASSWORD
required="RESTORE ${mode^^} TO $files_root"
if [[ "${AGENT_FLEET_RESTORE_CONFIRM:-}" != "$required" ]]; then
  read -r -p "Type '$required' to continue: " confirmation
  [[ "$confirmation" == "$required" ]] || { echo "restore cancelled" >&2; exit 1; }
fi

mkdir -p "$files_root"
temporary_root="${TMPDIR:-$files_root/usr/tmp}"
mkdir -p "$temporary_root"
temporary="$temporary_root/agent-fleet-restore-$$.tar.gz"
staging="$files_root/.agent-fleet-restore-staging-$$"
previous="$files_root/.agent-fleet-before-restore-$(date -u +%Y%m%dT%H%M%SZ)"
rm_bin="$(command -v rm)"
mv_bin="$(command -v mv)"
[[ -x /system/bin/rm ]] && rm_bin=/system/bin/rm
[[ -x /system/bin/mv ]] && mv_bin=/system/bin/mv
trap '"$rm_bin" -f "$temporary"' EXIT
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 \
  -pass env:AGENT_FLEET_BACKUP_PASSWORD -in "$archive" -out "$temporary"
gzip -t "$temporary"

while IFS= read -r entry; do
  [[ "$entry" != /* && "$entry" != *"../"* && "$entry" != ".." ]] || { echo "unsafe archive path: $entry" >&2; exit 1; }
  if [[ "$mode" == home ]]; then
    [[ "$entry" == home || "$entry" == home/* ]] || { echo "unexpected portable archive path: $entry" >&2; exit 1; }
  else
    [[ "$entry" == home || "$entry" == home/* || "$entry" == usr || "$entry" == usr/* ]] || { echo "unexpected complete archive path: $entry" >&2; exit 1; }
  fi
done < <(tar -tzf "$temporary")

mkdir -p "$staging" "$previous"
tar -C "$staging" -xzpf "$temporary" --preserve-permissions
components=(home)
[[ "$mode" == complete ]] && components+=(usr)
for component in "${components[@]}"; do
  if [[ -e "$files_root/$component" ]]; then
    "$mv_bin" "$files_root/$component" "$previous/$component"
  fi
  if ! "$mv_bin" "$staging/$component" "$files_root/$component"; then
    [[ -e "$previous/$component" ]] && "$mv_bin" "$previous/$component" "$files_root/$component"
    echo "restore swap failed for $component" >&2
    exit 1
  fi
done
"$rm_bin" -rf "$staging"
echo "$mode restore complete; fully stop and reopen Termux before use"
echo "pre-restore state retained at $previous until post-restore verification"
