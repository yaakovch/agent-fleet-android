#!/usr/bin/env bash
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
files="$work/files"
output="$work/output"
mkdir -p "$files/home/.config/wtmux" "$files/usr/bin" "$output"
printf 'fleet-token-fixture\n' >"$files/home/.config/wtmux/state"
printf '#!/bin/sh\necho original\n' >"$files/usr/bin/fixture"
chmod 700 "$files/usr/bin/fixture"
before="$(cd "$files" && find home usr -type f -print0 | sort -z | xargs -0 sha256sum)"

export PREFIX="$files/usr"
export TERMUX_FILES_ROOT="$files"
export AGENT_FLEET_BACKUP_OUTPUT="$output"
export AGENT_FLEET_BACKUP_PASSWORD="emulator-rehearsal-password"
bash "$repo/scripts/migration/termux-backup.sh" >/dev/null
backup_dir="$(find "$output" -mindepth 1 -maxdepth 1 -type d -print -quit)"
bash "$repo/scripts/migration/verify-backup.sh" "$backup_dir" >/dev/null

printf 'mutated\n' >"$files/home/.config/wtmux/state"
rm -f "$files/usr/bin/fixture"
printf 'new file\n' >"$files/home/new-file"
export AGENT_FLEET_RESTORE_CONFIRM="RESTORE COMPLETE TO $files"
bash "$repo/scripts/migration/termux-restore.sh" "$backup_dir" complete >/dev/null
after="$(cd "$files" && find home usr -type f -print0 | sort -z | xargs -0 sha256sum)"
[[ "$before" == "$after" ]] || { echo "rollback state does not match original" >&2; diff <(printf '%s\n' "$before") <(printf '%s\n' "$after"); exit 1; }
echo "synthetic complete backup and rollback rehearsal passed"
