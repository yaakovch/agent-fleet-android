#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 2 ]] || { echo "usage: $0 ADB_SERIAL APK" >&2; exit 2; }
serial="$1"
apk="$2"
package_name="com.yaakovch.fleet"

[[ -f "$apk" ]] || { echo "missing APK: $apk" >&2; exit 1; }
command -v adb >/dev/null || { echo "adb is required" >&2; exit 1; }

adb_device() { adb -s "$serial" "$@"; }

state="$(adb devices | awk -v serial="$serial" '$1 == serial { print $2 }')"
[[ "$state" == "device" ]] || { echo "ADB device is not ready: $serial ($state)" >&2; exit 1; }

before="$(adb_device shell dumpsys package "$package_name" | sed -n 's/^[[:space:]]*firstInstallTime=//p' | head -1 | tr -d '\r')"
[[ -n "$before" ]] || {
  echo "$package_name is not installed; this update-only command refuses a first install" >&2
  exit 1
}

echo "updating $package_name on $serial without uninstalling it"
adb_device install -r --no-incremental "$apk"

after="$(adb_device shell dumpsys package "$package_name" | sed -n 's/^[[:space:]]*firstInstallTime=//p' | head -1 | tr -d '\r')"
if [[ -z "$after" || "$after" != "$before" ]]; then
  echo "ERROR: package install identity changed (before: $before; after: ${after:-missing})" >&2
  echo "Termux data may need restoration from the encrypted backup." >&2
  exit 1
fi

echo "update preserved the existing app installation: $after"
