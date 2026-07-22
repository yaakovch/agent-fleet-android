#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

java_major() {
  "$1/bin/java" -version 2>&1 | awk -F'[".]' '/version/ { if ($2 == "1") print $3; else print $2; exit }'
}

select_java_home() {
  local candidate major
  for candidate in \
    "${AGENT_FLEET_JAVA_HOME:-}" \
    "${JAVA_HOME:-}" \
    "${AGENT_FLEET_DEFAULT_JAVA_HOME:-$HOME/.local/share/agent-fleet/jdk17}"; do
    [[ -n "$candidate" && -x "$candidate/bin/java" ]] || continue
    major="$(java_major "$candidate")"
    if [[ "$major" =~ ^[0-9]+$ && "$major" -ge 17 ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  echo "Android Gradle requires JDK 17; set AGENT_FLEET_JAVA_HOME to a compatible JDK" >&2
  return 1
}

java_home="$(select_java_home)"
if [[ "${1:-}" == "--print-java-home" ]]; then
  printf '%s\n' "$java_home"
  exit 0
fi

export JAVA_HOME="$java_home"
case ":$PATH:" in
  *":$JAVA_HOME/bin:"*) ;;
  *) export PATH="$JAVA_HOME/bin:$PATH" ;;
esac
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/android-sdk}}"

exec "$root/gradlew" "$@"
