#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 3 ]] || { echo "usage: $0 ADB_SERIAL PHONE_BACKUP_DIRECTORY LOCAL_DIRECTORY" >&2; exit 2; }
serial="$1"
phone_backup="$2"
local_root="$3"
adb_bin="${ADB:-adb}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
destination="$local_root/$timestamp"
mkdir -p "$destination/archives" "$destination/apks" "$destination/metadata"

adb_host_path() {
  local path="$1"
  if [[ "$adb_bin" == *.exe ]] && command -v wslpath >/dev/null; then
    wslpath -w "$path"
  else
    printf '%s\n' "$path"
  fi
}

"$adb_bin" -s "$serial" get-state >/dev/null
"$adb_bin" -s "$serial" pull "$phone_backup/." "$(adb_host_path "$destination/archives")"
"$adb_bin" -s "$serial" shell getprop >"$destination/metadata/getprop.txt"
"$adb_bin" -s "$serial" shell dumpsys package com.termux >"$destination/metadata/package-com.termux.txt"
"$adb_bin" -s "$serial" shell pm list packages -f | tr -d '\r' | grep -E 'com\.termux($|\.)' >"$destination/metadata/termux-packages.txt" || true

for package in com.termux com.termux.widget com.termux.api com.termux.boot com.termux.styling com.termux.tasker com.termux.float; do
  path="$("$adb_bin" -s "$serial" shell pm path "$package" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1 || true)"
  if [[ -n "$path" ]]; then
    "$adb_bin" -s "$serial" pull "$path" "$(adb_host_path "$destination/apks/$package.apk")"
    "$adb_bin" -s "$serial" shell dumpsys package "$package" >"$destination/metadata/package-$package.txt"
  fi
done
(cd "$destination" && find archives apks metadata -type f -print0 | sort -z | xargs -0 sha256sum >SHA256SUMS)
echo "device backup captured at $destination"
echo "verify the encrypted archives before any uninstall"
