#!/usr/bin/env bash
set -euo pipefail

mode="${1:-fast}"
case "$mode" in
  fast|full|update-goldens) ;;
  *) echo "usage: $0 [fast|full|update-goldens]" >&2; exit 2 ;;
esac

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
artifacts="$root/build/reports/agent-fleet/emulator/$stamp-$mode"
mkdir -p "$artifacts"
log="$artifacts/run.log"

java_home="${JAVA_HOME:-$HOME/.local/share/agent-fleet/jdk17}"
linux_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/android-sdk}}"
export JAVA_HOME="$java_home"
export ANDROID_HOME="$linux_sdk"

say() { printf '[android-check] %s\n' "$*"; }
fail() { say "FAILED: $*" >&2; say "details: $log" >&2; exit 1; }

if [[ -n "${ANDROID_SERIAL:-}" && "${ANDROID_SERIAL}" != emulator-* ]]; then
  fail "ANDROID_SERIAL points at a physical device; this runner only accepts emulator-*"
fi

if [[ "$mode" == "full" && -r /dev/kvm && -w /dev/kvm ]]; then
  say "running full Pixel 7 / API 36 managed-device suite"
  if ./gradlew :app:testDebugUnitTest :app:agentFleetPixel7Api36DebugAndroidTest --no-daemon --console=plain >"$log" 2>&1; then
    cp -R app/build/reports/androidTests/managedDevice "$artifacts/" 2>/dev/null || true
    cp -R app/build/outputs/androidTest-results/managedDevice "$artifacts/" 2>/dev/null || true
    say "PASS · report: $artifacts"
    exit 0
  fi
  tail -n 35 "$log" >&2
  fail "managed-device tests failed"
fi
if [[ "$mode" == "full" ]]; then
  say "KVM is unavailable; using the isolated Windows API 36 emulator for the full suite"
fi

windows_sdk="${AGENT_FLEET_WINDOWS_ANDROID_SDK:-}"
if [[ -z "$windows_sdk" ]]; then
  for candidate in /mnt/c/Users/*/AppData/Local/AgentFleetAndroid/sdk; do
    [[ -x "$candidate/platform-tools/adb.exe" ]] && windows_sdk="$candidate" && break
  done
fi
[[ -n "$windows_sdk" ]] || fail "Windows Android SDK was not found"
adb="$windows_sdk/platform-tools/adb.exe"
emulator="$windows_sdk/emulator/emulator.exe"
avd="${AGENT_FLEET_AVD:-AgentFleet_S23FE_API36}"
adb_port="${AGENT_FLEET_ADB_PORT:-5038}"
cmd_exe="/mnt/c/Windows/System32/cmd.exe"
adb_windows="$(wslpath -w "$adb")"
emulator_windows="$(wslpath -w "$emulator")"

adb_run() { /init "$cmd_exe" /c "$adb_windows" -P "$adb_port" "$@"; }
adb_run start-server >>"$log" 2>&1

emulator_serial() {
  adb_run devices 2>/dev/null | tr -d '\r' | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }'
}

serial="$(emulator_serial)"
if [[ -z "$serial" ]]; then
  [[ -x "$emulator" ]] || fail "Android emulator executable was not found"
  say "starting $avd (the phone will not be used)"
  /init "$cmd_exe" /c "$emulator_windows" -avd "$avd" -no-window -no-snapshot-save -no-boot-anim -no-audio \
    >"$artifacts/emulator.log" 2>&1 &
  for _ in $(seq 1 90); do
    sleep 2
    serial="$(emulator_serial)"
    [[ -n "$serial" ]] && break
  done
fi
[[ "$serial" == emulator-* ]] || fail "no emulator became available"

for _ in $(seq 1 90); do
  booted="$(adb_run -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  [[ "$booted" == "1" ]] && break
  sleep 2
done
[[ "${booted:-}" == "1" ]] || fail "emulator did not finish booting"

abi="$(adb_run -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
api="$(adb_run -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$abi" == "x86_64" && "$api" == "36" ]] || fail "expected x86_64 API 36, got $abi API $api"
[[ "$(adb_run -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || fail "selected device is not an emulator"
adb_run -s "$serial" shell wm size 1080x2400 >/dev/null
adb_run -s "$serial" shell wm density 420 >/dev/null
adb_run -s "$serial" shell settings put system user_rotation 0 >/dev/null
adb_run -s "$serial" shell settings put global window_animation_scale 0 >/dev/null
adb_run -s "$serial" shell settings put global transition_animation_scale 0 >/dev/null
adb_run -s "$serial" shell settings put global animator_duration_scale 0 >/dev/null

say "building tests"
gradle_tasks=(:app:assembleDebug :app:assembleDebugAndroidTest)
[[ "$mode" == "full" ]] && gradle_tasks+=(:app:testDebugUnitTest)
if ! ./gradlew "${gradle_tasks[@]}" --no-daemon --console=plain >"$log" 2>&1; then
  tail -n 35 "$log" >&2
  fail "test build failed"
fi
app_apk="$(find app/build/outputs/apk/debug -type f -name '*universal.apk' | head -1)"
test_apk="$(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk' | head -1)"
[[ -f "$app_apk" && -f "$test_apk" ]] || fail "test APKs were not produced"
app_apk_windows="$(wslpath -w "$app_apk")"
test_apk_windows="$(wslpath -w "$test_apk")"
adb_run -s "$serial" uninstall com.termux.test >>"$log" 2>&1 || true
adb_run -s "$serial" uninstall com.termux >>"$log" 2>&1 || true
adb_run -s "$serial" install -r -t "$app_apk_windows" >>"$log" 2>&1
adb_run -s "$serial" install -r -t "$test_apk_windows" >>"$log" 2>&1

instrument_args=(-w -r)
if [[ "$mode" == "update-goldens" ]]; then
  instrument_args+=(-e class com.termux.app.AgentFleetGoldenTest -e agentFleetUpdateGoldens true)
elif [[ "$mode" == "fast" ]]; then
  instrument_args+=(-e class com.termux.app.AgentFleetComposeTest)
fi
say "running $mode suite on $serial"
set +e
adb_run -s "$serial" shell am instrument "${instrument_args[@]}" com.termux.test/androidx.test.runner.AndroidJUnitRunner \
  >"$artifacts/instrumentation.txt" 2>&1
status=$?
set -e
if [[ $status -ne 0 ]] || grep -qE 'FAILURES|INSTRUMENTATION_FAILED' "$artifacts/instrumentation.txt"; then
  tail -n 60 "$artifacts/instrumentation.txt" >&2
  fail "instrumentation tests failed"
fi

if [[ "$mode" == "update-goldens" ]]; then
  mkdir -p "$artifacts/device-output"
  output_windows="$(wslpath -w "$artifacts/device-output")"
  adb_run -s "$serial" pull /sdcard/Android/media/com.termux/. "$output_windows" >>"$log" 2>&1 || \
    fail "golden output could not be copied from the emulator"
  say "PASS · review generated images under $artifacts/device-output"
  say "goldens are never replaced automatically"
else
  say "PASS · report: $artifacts"
fi
