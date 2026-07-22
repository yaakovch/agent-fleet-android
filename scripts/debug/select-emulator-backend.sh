#!/usr/bin/env bash
set -euo pipefail

requested="${AGENT_FLEET_EMULATOR_BACKEND:-auto}"
legacy="${AGENT_FLEET_USE_WINDOWS_AVD:-}"
case "$requested" in auto|windows|managed) ;; *)
  echo "AGENT_FLEET_EMULATOR_BACKEND must be auto, windows, or managed" >&2
  exit 2
esac
case "$legacy" in ""|0|1) ;; *)
  echo "AGENT_FLEET_USE_WINDOWS_AVD must be 0 or 1" >&2
  exit 2
esac
if [[ "$legacy" == "1" ]]; then
  if [[ "$requested" == "managed" ]]; then
    echo "conflicting emulator backend selections" >&2
    exit 2
  fi
  requested="windows"
fi

windows_sdk="${AGENT_FLEET_WINDOWS_ANDROID_SDK:-}"
if [[ -z "$windows_sdk" ]]; then
  for candidate in /mnt/c/Users/*/AppData/Local/AgentFleetAndroid/sdk; do
    if [[ -x "$candidate/platform-tools/adb.exe" && -x "$candidate/emulator/emulator.exe" ]]; then
      windows_sdk="$candidate"
      break
    fi
  done
fi
windows_available=0
[[ -n "$windows_sdk" ]] && windows_available=1
kvm_available=0
[[ -r /dev/kvm && -w /dev/kvm ]] && kvm_available=1
wsl=0
grep -qi microsoft /proc/sys/kernel/osrelease 2>/dev/null && wsl=1

if [[ "$requested" == "auto" ]]; then
  if [[ "$wsl" == "1" && "$windows_available" == "1" ]]; then
    requested="windows"
  elif [[ "$kvm_available" == "1" ]]; then
    requested="managed"
  elif [[ "$windows_available" == "1" ]]; then
    requested="windows"
  else
    echo "no protected API 36 emulator backend is available" >&2
    exit 1
  fi
fi
if [[ "$requested" == "windows" && "$windows_available" != "1" ]]; then
  echo "the Windows Android SDK/AVD backend is unavailable" >&2
  exit 1
fi
if [[ "$requested" == "managed" && "$kvm_available" != "1" ]]; then
  echo "the managed emulator backend requires readable and writable /dev/kvm" >&2
  exit 1
fi

printf '%s\n' "$requested"
