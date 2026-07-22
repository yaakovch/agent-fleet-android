#!/usr/bin/env bash
set -euo pipefail

[[ $# -ge 4 && $# -le 5 ]] || {
  echo "usage: $0 PASSWORD_SOURCE RELEASE_BASE_URL PUBLISH_PRIMARY RUNTIME_MANIFEST_URL [PUBLISH_FALLBACK]" >&2
  exit 2
}
password_source="$1"
release_base="${2%/}"
publish_primary="$3"
runtime_manifest="$4"
publish_fallback="${5:-}"
[[ -f "$password_source" ]] || { echo "password source was not found" >&2; exit 1; }
[[ "$release_base" == https://* && "$runtime_manifest" == https://* ]] || {
  echo "release and runtime URLs must use HTTPS" >&2; exit 2;
}
[[ "$publish_primary" == *:* ]] || { echo "publication destination is invalid" >&2; exit 2; }

signing_dir="${AGENT_FLEET_SIGNING_DIR:-$HOME/.local/share/agent-fleet/signing}"
password_file="$signing_dir/agent-fleet-release.pass"
config_file="$signing_dir/release.conf"
password="$(sed -n 's/^[[:space:]]*//;s/[[:space:]]*$//;/./p' "$password_source" | head -1)"
[[ -n "$password" ]] || { echo "password source has no non-empty line" >&2; exit 1; }

umask 077
mkdir -p "$signing_dir"
chmod 700 "$signing_dir"
password_tmp="$(mktemp "$signing_dir/.agent-fleet-release.pass.XXXXXX")"
config_tmp="$(mktemp "$signing_dir/.release.conf.XXXXXX")"
cleanup() { rm -f "$password_tmp" "$config_tmp"; }
trap cleanup EXIT
printf '%s' "$password" >"$password_tmp"
{
  printf 'AGENT_FLEET_RELEASE_BASE_URL=%s\n' "$release_base"
  printf 'AGENT_FLEET_PUBLISH_PRIMARY=%s\n' "$publish_primary"
  [[ -z "$publish_fallback" ]] || printf 'AGENT_FLEET_PUBLISH_FALLBACK=%s\n' "$publish_fallback"
  printf 'AGENT_FLEET_RUNTIME_MANIFEST_URL=%s\n' "$runtime_manifest"
} >"$config_tmp"
chmod 600 "$password_tmp" "$config_tmp"
mv -f "$password_tmp" "$password_file"
mv -f "$config_tmp" "$config_file"
trap - EXIT
printf 'configured local release credentials: %s\n' "$signing_dir"
