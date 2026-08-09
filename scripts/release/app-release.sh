#!/usr/bin/env bash
set -euo pipefail

hold=0
preflight_only=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --hold) hold=1 ;;
    --preflight-only) preflight_only=1 ;;
    *) echo "usage: $0 [--hold] [--preflight-only]" >&2; exit 2 ;;
  esac
  shift
done

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$repo/scripts/release/release-common.sh"
agent_fleet_release_read_version "$repo"
version_name="$AGENT_FLEET_RELEASE_VERSION_NAME"
version_code="$AGENT_FLEET_RELEASE_VERSION_CODE"
git_commit="$(git -C "$repo" rev-parse HEAD)"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
report_dir="$repo/build/reports/agent-fleet/release/$stamp-$version_name"
mkdir -p "$report_dir"
stages="$report_dir/stages.tsv"
: >"$stages"
sequence_reservation="$report_dir/sequence-reservation-v1.json"
publication_transaction="${AGENT_FLEET_PUBLICATION_RECEIPT:-$(
  python3 "$repo/scripts/release/publication_receipt.py" default-path "$version_name"
)}"
publication_status="$report_dir/publication-status.json"
loopback_args=()

say() { printf '[release] %s\n' "$*"; }

write_report() {
  local outcome="$1" release_dir="$repo/dist/$version_name"
  if [[ -e "$publication_transaction" || -L "$publication_transaction" ]]; then
    python3 "$repo/scripts/release/publication_receipt.py" redact \
      "$publication_transaction" "$publication_status" || true
  fi
  python3 - "$report_dir/release-report.json" "$stages" "$outcome" "$version_name" "$version_code" "$git_commit" "$hold" "$release_dir" <<'PY'
import hashlib, json, pathlib, sys
output, stages_path, outcome, version_name, version_code, commit, hold, release_dir = sys.argv[1:]
stages = []
for line in pathlib.Path(stages_path).read_text(encoding="utf-8").splitlines():
    name, seconds, status = line.split("\t")
    stages.append({"name": name, "durationSeconds": int(seconds), "status": status})
artifacts = []
manifest_path = pathlib.Path(release_dir, "manifest.json")
signed_build_passed = any(
    stage["name"] == "signed-build" and stage["status"] == "passed"
    for stage in stages
)
if signed_build_passed and manifest_path.is_file():
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    artifacts = [
        {"abi": item["abi"], "sha256": item["apkSha256"], "size": item["size"]}
        for item in manifest.get("artifacts", [])
    ]
report = {
    "schemaVersion": 1,
    "versionName": version_name,
    "versionCode": int(version_code),
    "gitCommit": commit,
    "emulatorBackend": "windows",
    "publicationHeld": hold == "1",
    "outcome": outcome,
    "stages": stages,
    "artifacts": artifacts,
}
pathlib.Path(output).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
PY
  say "report: $report_dir/release-report.json"
}

run_stage_deferred() {
  local name="$1" started elapsed status
  shift
  say "$name"
  started="$SECONDS"
  if "$@"; then
    status=0
  else
    status=$?
  fi
  elapsed=$((SECONDS - started))
  if [[ "$status" -eq 0 ]]; then
    printf '%s\t%s\tpassed\n' "$name" "$elapsed" >>"$stages"
    say "$name passed in ${elapsed}s"
    return 0
  fi
  printf '%s\t%s\tfailed\n' "$name" "$elapsed" >>"$stages"
  return "$status"
}

run_stage() {
  local status
  set +e
  run_stage_deferred "$@"
  status=$?
  set -e
  if [[ "$status" -ne 0 ]]; then
    write_report failed
    exit "$status"
  fi
}

check_release_sequence() {
  local -a reservation_args=()
  if [[ -e "$sequence_reservation" ]]; then
    reservation_args=(--reservation "$sequence_reservation")
  else
    reservation_args=(--reservation-out "$sequence_reservation")
  fi
  "$repo/scripts/release/check-release-sequence.py" "$version_code" \
    "$AGENT_FLEET_RELEASE_BASE_URL/manifest.json" "$AGENT_FLEET_RUNTIME_MANIFEST_URL" \
    "${reservation_args[@]}" \
    "${loopback_args[@]}"
}

release_preflight() {
  agent_fleet_release_load_config
  agent_fleet_release_load_credentials
  agent_fleet_release_check_keystore "$repo"
  agent_fleet_release_check_git "$repo"
  agent_fleet_release_find_sdk
  if [[ "$AGENT_FLEET_PUBLISH_PRIMARY" == local:* ]]; then
    loopback_args=(--loopback-fallback)
  fi
  check_release_sequence
}

run_stage preflight release_preflight
export -n AGENT_FLEET_STORE_PASSWORD AGENT_FLEET_KEY_PASSWORD 2>/dev/null || true
if [[ "$preflight_only" == "1" ]]; then
  write_report passed
  exit 0
fi

run_stage full-api36 env -u AGENT_FLEET_STORE_PASSWORD -u AGENT_FLEET_KEY_PASSWORD \
  AGENT_FLEET_EMULATOR_BACKEND=windows \
  bash "$repo/scripts/debug/android-check.sh" full
run_stage release-lint env -u AGENT_FLEET_STORE_PASSWORD -u AGENT_FLEET_KEY_PASSWORD \
  "$repo/scripts/debug/android-gradle.sh" :app:lintRelease \
  --daemon --no-build-cache --parallel --console=plain
run_stage signed-build env -u AGENT_FLEET_STORE_PASSWORD -u AGENT_FLEET_KEY_PASSWORD \
  "$repo/scripts/release/build-signed-release.sh" \
  "$version_name" "$version_code" "$AGENT_FLEET_RELEASE_BASE_URL"
release_dir="$repo/dist/$version_name"
run_stage release-verification env -u AGENT_FLEET_STORE_PASSWORD -u AGENT_FLEET_KEY_PASSWORD \
  "$repo/scripts/release/verify-release.sh" "$release_dir"

if [[ "$hold" == "1" ]]; then
  printf 'publication\t0\theld\n' >>"$stages"
  write_report passed
  say "verified release held before publication"
  exit 0
fi

run_stage sequence-recheck check_release_sequence
run_stage publication env -u AGENT_FLEET_STORE_PASSWORD -u AGENT_FLEET_KEY_PASSWORD \
  AGENT_FLEET_PUBLISH_PRIMARY="${AGENT_FLEET_PUBLISH_PRIMARY:-}" \
  AGENT_FLEET_PUBLISH_FALLBACK="${AGENT_FLEET_PUBLISH_FALLBACK:-}" \
  AGENT_FLEET_RELEASE_BASE_URL="$AGENT_FLEET_RELEASE_BASE_URL" \
  AGENT_FLEET_RUNTIME_MANIFEST_URL="$AGENT_FLEET_RUNTIME_MANIFEST_URL" \
  "$repo/scripts/release/publish-release.sh" "$release_dir" "$publication_transaction" \
  --sequence-reservation "$sequence_reservation" "${loopback_args[@]}"
set +e
run_stage_deferred served-verification "$repo/scripts/release/verify-served-release.py" \
  "$release_dir" "$AGENT_FLEET_RELEASE_BASE_URL" "${loopback_args[@]}"
served_status=$?
set -e
if [[ "$served_status" -ne 0 ]]; then
  set +e
  run_stage_deferred publication-rollback env \
    AGENT_FLEET_PUBLISH_PRIMARY="${AGENT_FLEET_PUBLISH_PRIMARY:-}" \
    AGENT_FLEET_PUBLISH_FALLBACK="${AGENT_FLEET_PUBLISH_FALLBACK:-}" \
    "$repo/scripts/release/publish-release.sh" --rollback "$publication_transaction"
  rollback_status=$?
  set -e
  if [[ "$rollback_status" -ne 0 ]]; then
    say "served verification and publication rollback both failed"
    write_report failed
    exit 75
  fi
  set +e
  run_stage_deferred restored-served-verification \
    "$repo/scripts/release/verify-served-release.py" --restored-from-receipt \
    "$publication_transaction" "$AGENT_FLEET_RELEASE_BASE_URL" "${loopback_args[@]}"
  restored_status=$?
  set -e
  write_report failed
  if [[ "$restored_status" -ne 0 ]]; then
    say "previous target was switched back but its externally served bytes could not be verified"
    exit 75
  fi
  say "served verification failed; exact previous publication restored and externally verified"
  exit "$served_status"
fi
write_report passed
say "published and verified $version_name ($version_code)"
