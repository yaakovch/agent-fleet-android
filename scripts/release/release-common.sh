#!/usr/bin/env bash

agent_fleet_release_fail() {
  echo "[release] $*" >&2
  return 1
}

agent_fleet_release_read_version() {
  local repo="$1" file name code
  file="$repo/app/version.properties"
  [[ -f "$file" ]] || agent_fleet_release_fail "missing app/version.properties" || return
  name="$(sed -n 's/^VERSION_NAME=//p' "$file")"
  code="$(sed -n 's/^VERSION_CODE=//p' "$file")"
  [[ "$name" =~ ^[0-9]+\.[0-9]+\.[0-9]+-agentfleet\.[0-9]+$ ]] ||
    agent_fleet_release_fail "invalid VERSION_NAME in app/version.properties" || return
  [[ "$code" =~ ^[1-9][0-9]*$ ]] ||
    agent_fleet_release_fail "invalid VERSION_CODE in app/version.properties" || return
  AGENT_FLEET_RELEASE_VERSION_NAME="$name"
  AGENT_FLEET_RELEASE_VERSION_CODE="$code"
  export AGENT_FLEET_RELEASE_VERSION_NAME AGENT_FLEET_RELEASE_VERSION_CODE
}

agent_fleet_release_load_config() {
  local signing_dir config line key value
  signing_dir="${AGENT_FLEET_SIGNING_DIR:-$HOME/.local/share/agent-fleet/signing}"
  config="${AGENT_FLEET_RELEASE_CONFIG:-$signing_dir/release.conf}"
  [[ -f "$config" ]] || agent_fleet_release_fail "missing local release config: $config" || return
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ -z "$line" || "$line" == \#* ]] && continue
    key="${line%%=*}"
    value="${line#*=}"
    [[ "$key" != "$line" ]] || agent_fleet_release_fail "invalid release config line" || return
    case "$key" in
      AGENT_FLEET_RELEASE_BASE_URL|AGENT_FLEET_PUBLISH_PRIMARY|AGENT_FLEET_PUBLISH_FALLBACK|AGENT_FLEET_RUNTIME_MANIFEST_URL|AGENT_FLEET_RELEASE_HTTPS_CONNECT_TO)
        if [[ -z "${!key:-}" ]]; then
          printf -v "$key" '%s' "$value"
          export "$key"
        fi
        ;;
      *) agent_fleet_release_fail "unknown release config key: $key" || return ;;
    esac
  done <"$config"
  [[ "${AGENT_FLEET_RELEASE_BASE_URL:-}" == https://* ]] ||
    agent_fleet_release_fail "release base URL must use HTTPS" || return
  [[ "${AGENT_FLEET_RUNTIME_MANIFEST_URL:-}" == https://* ]] ||
    agent_fleet_release_fail "runtime manifest URL must use HTTPS" || return
  [[ -n "${AGENT_FLEET_PUBLISH_PRIMARY:-}${AGENT_FLEET_PUBLISH_FALLBACK:-}" ]] ||
    agent_fleet_release_fail "a release publication destination is required" || return
}

agent_fleet_release_load_credentials() {
  local signing_dir password_file repo
  signing_dir="${AGENT_FLEET_SIGNING_DIR:-$HOME/.local/share/agent-fleet/signing}"
  password_file="${AGENT_FLEET_STORE_PASSWORD_FILE:-$signing_dir/agent-fleet-release.pass}"
  repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  if [[ -z "${AGENT_FLEET_STORE_PASSWORD:-}" ]]; then
    AGENT_FLEET_STORE_PASSWORD="$(python3 "$repo/scripts/release/release_identity.py" password "$password_file")" || return
  fi
  [[ -n "$AGENT_FLEET_STORE_PASSWORD" && "$AGENT_FLEET_STORE_PASSWORD" != *$'\n'* ]] ||
    agent_fleet_release_fail "signing password must be one non-empty line" || return
  AGENT_FLEET_KEY_PASSWORD="${AGENT_FLEET_KEY_PASSWORD:-$AGENT_FLEET_STORE_PASSWORD}"
  export -n AGENT_FLEET_STORE_PASSWORD AGENT_FLEET_KEY_PASSWORD 2>/dev/null || true
}

agent_fleet_release_load_certificate_fingerprint() {
  local repo="$1" fingerprint_file
  fingerprint_file="$repo/app/release-signing-certificate-sha256.txt"
  AGENT_FLEET_EXPECTED_CERTIFICATE_SHA256="$(
    python3 "$repo/scripts/release/release_identity.py" public-certificate "$fingerprint_file"
  )" || return
  export AGENT_FLEET_EXPECTED_CERTIFICATE_SHA256
}

agent_fleet_release_load_local_certificate_fingerprint() {
  local repo="$1" signing_dir fingerprint_file
  signing_dir="${AGENT_FLEET_SIGNING_DIR:-$HOME/.local/share/agent-fleet/signing}"
  fingerprint_file="${AGENT_FLEET_CERTIFICATE_SHA256_FILE:-$signing_dir/certificate-sha256.txt}"
  AGENT_FLEET_LOCAL_CERTIFICATE_SHA256="$(
    python3 "$repo/scripts/release/release_identity.py" certificate "$fingerprint_file"
  )" || return
}

agent_fleet_release_check_keystore() {
  local repo="$1" signing_dir keystore alias_name java_home observed
  signing_dir="${AGENT_FLEET_SIGNING_DIR:-$HOME/.local/share/agent-fleet/signing}"
  keystore="${AGENT_FLEET_KEYSTORE:-$signing_dir/agent-fleet-release.jks}"
  alias_name="${AGENT_FLEET_KEY_ALIAS:-agent-fleet}"
  [[ -f "$keystore" ]] || agent_fleet_release_fail "missing release keystore: $keystore" || return
  agent_fleet_release_load_certificate_fingerprint "$repo" || return
  agent_fleet_release_load_local_certificate_fingerprint "$repo" || return
  [[ "$AGENT_FLEET_LOCAL_CERTIFICATE_SHA256" == "$AGENT_FLEET_EXPECTED_CERTIFICATE_SHA256" ]] ||
    agent_fleet_release_fail "local certificate backup pin does not match the protected production fingerprint" || return
  java_home="$("$repo/scripts/debug/android-gradle.sh" --print-java-home)" || return
  observed="$(env AGENT_FLEET_STORE_PASSWORD="$AGENT_FLEET_STORE_PASSWORD" \
    "$java_home/bin/keytool" -list -v -keystore "$keystore" -alias "$alias_name" \
    -storepass:env AGENT_FLEET_STORE_PASSWORD 2>/dev/null | \
    awk -F': ' '/SHA256:/{gsub(":", "", $2); print tolower($2); exit}')" ||
    agent_fleet_release_fail "release keystore password or alias is invalid" || return
  [[ "$observed" == "$AGENT_FLEET_EXPECTED_CERTIFICATE_SHA256" ]] ||
    agent_fleet_release_fail "release keystore certificate does not match the pinned fingerprint" || return
  AGENT_FLEET_RELEASE_JAVA_HOME="$java_home"
  export AGENT_FLEET_RELEASE_JAVA_HOME
}

agent_fleet_release_check_git() {
  local repo="$1" upstream counts
  [[ -z "$(git -C "$repo" status --porcelain --untracked-files=all)" ]] ||
    agent_fleet_release_fail "Android checkout must be clean before release" || return
  git -C "$repo" fetch --quiet ||
    agent_fleet_release_fail "could not refresh the release branch upstream" || return
  upstream="$(git -C "$repo" rev-parse --abbrev-ref '@{upstream}' 2>/dev/null)" ||
    agent_fleet_release_fail "release branch has no upstream" || return
  counts="$(git -C "$repo" rev-list --left-right --count "HEAD...$upstream")" || return
  [[ "$counts" == $'0\t0' || "$counts" == "0 0" ]] ||
    agent_fleet_release_fail "release commit must already be pushed and synchronized with $upstream" || return
}

agent_fleet_release_find_sdk() {
  local sdk candidate
  sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [[ -z "$sdk" ]]; then
    for candidate in /mnt/c/Users/*/AppData/Local/AgentFleetAndroid/sdk; do
      if [[ -d "$candidate/build-tools" ]]; then sdk="$candidate"; break; fi
    done
  fi
  [[ -n "$sdk" && -d "$sdk/build-tools" ]] ||
    agent_fleet_release_fail "Android SDK was not found" || return
  AGENT_FLEET_RELEASE_ANDROID_SDK="$sdk"
  export AGENT_FLEET_RELEASE_ANDROID_SDK
}
