#!/usr/bin/env bash
set -euo pipefail

mode="${1:-fast}"
case "$mode" in
  fast|focused|full|update-goldens|coinstall|migration-lanes) ;;
  *) echo "usage: $0 [fast|focused|full|update-goldens|coinstall|migration-lanes]" >&2; exit 2 ;;
esac

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
gradle="$root/scripts/debug/android-gradle.sh"
backend_selector="$root/scripts/debug/select-emulator-backend.sh"
instrumentation_checker="$root/scripts/debug/check-instrumentation-result.sh"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
artifacts="$root/build/reports/agent-fleet/emulator/$stamp-$mode"
mkdir -p "$artifacts"
log="$artifacts/run.log"
app_package="com.yaakovch.fleet"
test_package="${app_package}.test"

java_home="$("$gradle" --print-java-home)"
linux_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/android-sdk}}"
export JAVA_HOME="$java_home"
export ANDROID_HOME="$linux_sdk"

say() { printf '[android-check] %s\n' "$*"; }
fail() { say "FAILED: $*" >&2; say "details: $log" >&2; exit 1; }

if [[ -n "${ANDROID_SERIAL:-}" && "${ANDROID_SERIAL}" != emulator-* ]]; then
  fail "ANDROID_SERIAL points at a physical device; this runner only accepts emulator-*"
fi
focused_class="${AGENT_FLEET_INSTRUMENTATION_CLASS:-}"
if [[ "$mode" == "focused" && ( -z "$focused_class" || "$focused_class" != com.termux.app.* || "$focused_class" == *[[:space:]/]* ) ]]; then
  fail "focused mode requires a com.termux.app.* AGENT_FLEET_INSTRUMENTATION_CLASS"
fi

backend="windows"
if [[ "$mode" == "full" ]]; then
  backend="$("$backend_selector")" || fail "emulator backend selection failed"
elif [[ "${AGENT_FLEET_EMULATOR_BACKEND:-auto}" == "managed" ]]; then
  fail "the managed backend is available only for the full suite"
fi

if [[ "$mode" == "full" && "$backend" == "managed" ]]; then
  say "running full Pixel 7 / API 36 managed-device suite"
  # A managed image's model overlays change the reference viewport. Android
  # can retain old status-bar insets if they change after earlier activities.
  # Give goldens a fresh instrumentation process and retain both phases before
  # Gradle replaces its result directory. Every test still runs exactly once.
  for phase in goldens functional; do
    phase_artifacts="$artifacts/$phase"
    mkdir -p "$phase_artifacts"
    phase_args=(-Pandroid.testInstrumentationRunnerArguments.class=com.termux.app.AgentFleetGoldenTest)
    phase_tasks=(:app:agentFleetPixel7Api36DebugAndroidTest)
    if [[ "$phase" == "functional" ]]; then
      phase_args=(-Pandroid.testInstrumentationRunnerArguments.notClass=com.termux.app.AgentFleetGoldenTest)
      phase_tasks=(:app:testDebugUnitTest :app:agentFleetPixel7Api36DebugAndroidTest)
    fi
    say "managed $phase phase"
    status=0
    "$gradle" "${phase_tasks[@]}" "${phase_args[@]}" \
      --daemon --no-build-cache --parallel --console=plain >"$phase_artifacts/run.log" 2>&1 || status=$?
    cat "$phase_artifacts/run.log" >>"$log"
    for entry in \
      'reports/androidTests/managedDevice:reports' \
      'outputs/androidTest-results/managedDevice:results' \
      'outputs/managed_device_android_test_additional_output:device-output'; do
      source_path="app/build/${entry%%:*}"
      [[ ! -d "$source_path" ]] || cp -R "$source_path" "$phase_artifacts/${entry##*:}"
    done
    if [[ "$status" -ne 0 ]]; then
      tail -n 35 "$phase_artifacts/run.log" >&2
      fail "managed $phase tests failed"
    fi
    for raw in "$phase_artifacts"/results/debug/agentFleetPixel7Api36/adb.*.am.instrument.*.txt; do
      [[ -f "$raw" ]] || fail "managed $phase raw instrumentation report is missing"
      "$instrumentation_checker" "$raw" || fail "managed $phase instrumentation did not finish successfully"
    done
  done
  say "PASS · report: $artifacts"
  exit 0
fi
if [[ "$mode" == "full" ]]; then
  say "using the isolated Windows API 36 emulator for the full suite"
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
  adb_run devices 2>/dev/null | tr -d '\r' | awk '$1 ~ /^emulator-/ && $2 == "device" && !found { print $1; found=1 }'
}

serial="$(emulator_serial)"
if [[ -z "$serial" ]]; then
  [[ -x "$emulator" ]] || fail "Android emulator executable was not found"
  say "starting $avd (the phone will not be used)"
  /init "$cmd_exe" /d /c "set ADB_SERVER_PORT=$adb_port&& set ANDROID_ADB_SERVER_PORT=$adb_port&& $emulator_windows -avd $avd -no-window -no-snapshot-load -no-snapshot-save -no-boot-anim -no-audio" \
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
adb_run -s "$serial" shell settings put global hide_error_dialogs 1 >/dev/null
[[ "$(adb_run -s "$serial" shell settings get global hide_error_dialogs | tr -d '\r')" == "1" ]] || \
  fail "emulator error-dialog suppression did not activate"

if [[ "$mode" == "migration-lanes" ]]; then
  permanent_apk="${AGENT_FLEET_PERMANENT_APK:-}"
  legacy_apk="${AGENT_FLEET_LEGACY_APK:-}"
  [[ -f "$permanent_apk" && -f "$legacy_apk" ]] || \
    fail "set AGENT_FLEET_PERMANENT_APK and AGENT_FLEET_LEGACY_APK to signed universal APKs"
  build_tools="$(find "$linux_sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
  aapt2="$build_tools/aapt2"
  apksigner_jar="$build_tools/lib/apksigner.jar"
  [[ -x "$aapt2" && -f "$apksigner_jar" ]] || fail "Android APK inspection tools were not found"
  permanent_badging="$("$aapt2" dump badging "$permanent_apk")"
  [[ "$permanent_badging" == "package: name='$app_package' "* ]] || \
    fail "permanent lane APK has the wrong application ID"
  legacy_badging="$("$aapt2" dump badging "$legacy_apk")"
  [[ "$legacy_badging" == "package: name='com.termux' "* ]] || \
    fail "legacy lane APK has the wrong application ID"
  permanent_cert="$("$java_home/bin/java" -jar "$apksigner_jar" verify --print-certs "$permanent_apk" | awk -F': ' '/Signer #1 certificate SHA-256 digest:/ && !found {print tolower($2); found=1}')"
  legacy_cert="$("$java_home/bin/java" -jar "$apksigner_jar" verify --print-certs "$legacy_apk" | awk -F': ' '/Signer #1 certificate SHA-256 digest:/ && !found {print tolower($2); found=1}')"
  [[ "$permanent_cert" =~ ^[a-f0-9]{64}$ && "$permanent_cert" == "$legacy_cert" ]] || \
    fail "migration lane APK certificates do not match"
  adb_run -s "$serial" uninstall "$test_package" >>"$log" 2>&1 || true
  adb_run -s "$serial" uninstall "$app_package" >>"$log" 2>&1 || true
  adb_run -s "$serial" uninstall com.termux >>"$log" 2>&1 || true
  adb_run -s "$serial" install -r -t "$(wslpath -w "$legacy_apk")" >>"$log" 2>&1
  adb_run -s "$serial" install -r -t "$(wslpath -w "$permanent_apk")" >>"$log" 2>&1
  {
    printf 'certificate=%s\n' "$permanent_cert"
    adb_run -s "$serial" shell pm path "$app_package"
    adb_run -s "$serial" shell pm path com.termux
    adb_run -s "$serial" shell dumpsys package "$app_package" | tr -d '\r' | awk '/dataDir=/ && !found {print; found=1}'
    adb_run -s "$serial" shell dumpsys package com.termux | tr -d '\r' | awk '/dataDir=/ && !found {print; found=1}'
  } >"$artifacts/migration-lanes.txt"
  grep -q "dataDir=/data/user/0/$app_package" "$artifacts/migration-lanes.txt" || \
    fail "permanent migration lane is not installed in its private root"
  grep -q 'dataDir=/data/user/0/com.termux' "$artifacts/migration-lanes.txt" || \
    fail "legacy migration lane is not installed in its private root"
  say "PASS · signed migration lanes coexist: $artifacts"
  exit 0
fi

say "building tests"
gradle_tasks=(:app:assembleDebug)
[[ "$mode" != "coinstall" ]] && gradle_tasks+=(:app:assembleDebugAndroidTest)
[[ "$mode" == "full" ]] && gradle_tasks+=(:app:testDebugUnitTest)
if ! "$gradle" "${gradle_tasks[@]}" --daemon --no-build-cache --parallel --console=plain >"$log" 2>&1; then
  tail -n 35 "$log" >&2
  fail "test build failed"
fi
app_apk="$(find app/build/outputs/apk/debug -type f -name '*universal.apk' -print -quit)"
[[ -f "$app_apk" ]] || fail "app test APK was not produced"
app_apk_windows="$(wslpath -w "$app_apk")"
adb_run -s "$serial" uninstall "$test_package" >>"$log" 2>&1 || true
adb_run -s "$serial" uninstall "$app_package" >>"$log" 2>&1 || true
adb_run -s "$serial" uninstall com.termux >>"$log" 2>&1 || true
adb_run -s "$serial" install -r -t "$app_apk_windows" >>"$log" 2>&1

if [[ "$mode" == "coinstall" ]]; then
  official_termux="${AGENT_FLEET_OFFICIAL_TERMUX_APK:-}"
  [[ -f "$official_termux" ]] || fail "set AGENT_FLEET_OFFICIAL_TERMUX_APK to a verified x86_64 official Termux APK"
  build_tools="$(find "$linux_sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
  aapt2="$build_tools/aapt2"
  [[ -x "$aapt2" ]] || fail "aapt2 was not found in the Android SDK"
  official_badging="$("$aapt2" dump badging "$official_termux")"
  [[ "$official_badging" == "package: name='com.termux' "* ]] || \
    fail "official Termux fixture has the wrong application ID"
  official_termux_windows="$(wslpath -w "$official_termux")"
  adb_run -s "$serial" install -r -t "$official_termux_windows" >>"$log" 2>&1
  {
    adb_run -s "$serial" shell pm path "$app_package"
    adb_run -s "$serial" shell pm path com.termux
    adb_run -s "$serial" shell dumpsys package "$app_package" | tr -d '\r' | awk '/dataDir=/ && !found {print; found=1}'
    adb_run -s "$serial" shell dumpsys package com.termux | tr -d '\r' | awk '/dataDir=/ && !found {print; found=1}'
  } >"$artifacts/coinstallation.txt"
  grep -q 'com.yaakovch.fleet' "$artifacts/coinstallation.txt" || fail "permanent Agent Fleet package is not installed"
  grep -q 'com.termux' "$artifacts/coinstallation.txt" || fail "official Termux package is not installed"
  say "PASS · permanent Agent Fleet and official Termux coexist: $artifacts"
  exit 0
fi

test_apk="$(find app/build/outputs/apk/androidTest/debug -type f -name '*.apk' -print -quit)"
[[ -f "$test_apk" ]] || fail "instrumentation APK was not produced"
test_apk_windows="$(wslpath -w "$test_apk")"
adb_run -s "$serial" install -r -t "$test_apk_windows" >>"$log" 2>&1

# A retained headless AVD can keep Quick Settings or the notification shade
# expanded between runs. Compose semantics remain reachable underneath it, but
# UiAutomation screenshots then capture System UI instead of the test window.
# Collapse panels only after the API/ABI/qemu checks above have proved this is
# the protected emulator.
adb_run -s "$serial" shell cmd statusbar collapse >>"$log" 2>&1 || \
  fail "emulator system panels could not be collapsed"

instrument_args=(-w -r)
if [[ "$mode" == "update-goldens" ]]; then
  instrument_args+=(-e class com.termux.app.AgentFleetGoldenTest -e agentFleetUpdateGoldens true)
elif [[ "$mode" == "focused" ]]; then
  instrument_args+=(-e class "$focused_class")
elif [[ "$mode" == "fast" ]]; then
  instrument_args+=(-e class com.termux.app.AgentFleetComposeTest,com.termux.app.AgentFleetDrawerComposeTest)
fi
say "running $mode suite on $serial"
set +e
adb_run -s "$serial" shell am instrument "${instrument_args[@]}" "$test_package/androidx.test.runner.AndroidJUnitRunner" \
  >"$artifacts/instrumentation.txt" 2>&1
status=$?
set -e
if [[ $status -ne 0 ]] || ! "$instrumentation_checker" "$artifacts/instrumentation.txt"; then
  mkdir -p "$artifacts/device-output"
  output_windows="$(wslpath -w "$artifacts/device-output")"
  adb_run -s "$serial" pull "/sdcard/Android/media/$app_package/." "$output_windows" >>"$log" 2>&1 || true
  tail -n 60 "$artifacts/instrumentation.txt" >&2
  fail "instrumentation tests failed"
fi

if [[ "$mode" == "update-goldens" ]]; then
  mkdir -p "$artifacts/device-output"
  output_windows="$(wslpath -w "$artifacts/device-output")"
  adb_run -s "$serial" pull "/sdcard/Android/media/$app_package/." "$output_windows" >>"$log" 2>&1 || \
    fail "golden output could not be copied from the emulator"
  say "PASS · review generated images under $artifacts/device-output"
  say "goldens are never replaced automatically"
else
  say "PASS · report: $artifacts"
fi
