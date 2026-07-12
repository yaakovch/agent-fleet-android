#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 1 ]] || { echo "usage: $0 RELEASE_DIRECTORY" >&2; exit 2; }
directory="$(cd "$1" && pwd)"
version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["versionName"])' "$directory/manifest.json")"
primary="${AGENT_FLEET_PUBLISH_PRIMARY:-}"
fallback="${AGENT_FLEET_PUBLISH_FALLBACK:-}"
[[ -n "$primary" || -n "$fallback" ]] || { echo "set AGENT_FLEET_PUBLISH_PRIMARY or AGENT_FLEET_PUBLISH_FALLBACK to local:/path or user@host:/path" >&2; exit 2; }

publish() {
  local destination="$1" remote root release_path
  remote="${destination%%:*}"
  root="${destination#*:}"
  [[ "$remote" != "$destination" && -n "$root" ]] || return 1
  release_path="$root/releases/$version"
  if [[ "$remote" == local ]]; then
    [[ "$root" == /* ]] || { echo "local publication path must be absolute" >&2; return 1; }
    mkdir -p "$release_path"
    cp -p "$directory"/* "$release_path/"
    ln -sfn "releases/$version" "$root/latest"
    return
  fi
  ssh "$remote" "mkdir -p '$release_path'"
  scp "$directory"/* "$remote:$release_path/"
  ssh "$remote" "cd '$root' && ln -sfn 'releases/$version' latest"
}

if [[ -n "$primary" ]] && publish "$primary"; then
  echo "published $version to primary"
elif [[ -n "$fallback" ]] && publish "$fallback"; then
  echo "published $version to fallback"
else
  echo "publication failed" >&2
  exit 1
fi
