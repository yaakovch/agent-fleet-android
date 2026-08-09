#!/usr/bin/env bash
set -euo pipefail

mode="${1:-local}"
case "$mode" in
  static|package|local|full) ;;
  *) echo "usage: $0 [static|package|local|full]" >&2; exit 2 ;;
esac

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
gradle="$root/scripts/debug/android-gradle.sh"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/android-sdk}}"

say() { printf '[android-quality] %s\n' "$*"; }

repository_files() {
  git ls-files --cached --others --exclude-standard -z
}

run_source_checks() {
  local path first_line
  local -a shell_sources=()
  local -a python_sources=()
  while IFS= read -r -d '' path; do
    [[ -f "$path" ]] || continue
    IFS= read -r first_line <"$path" || true
    case "$path:$first_line" in
      *.sh:*|*:'#!/bin/bash'*|*:'#!/usr/bin/env bash'*) shell_sources+=("$path") ;;
    esac
    case "$path:$first_line" in
      *.py:*|*:'#!/usr/bin/python3'*|*:'#!/usr/bin/env python3'*) python_sources+=("$path") ;;
    esac
  done < <(repository_files)
  ((${#shell_sources[@]} > 0)) || { echo "no shell sources discovered" >&2; return 1; }
  ((${#python_sources[@]} > 0)) || { echo "no Python sources discovered" >&2; return 1; }
  bash -n "${shell_sources[@]}"
  python3 -m py_compile "${python_sources[@]}"
  say "syntax: ${#shell_sources[@]} shell and ${#python_sources[@]} Python sources"
}

run_static() {
  say "checking repository policy, workflow pins, and dependency integrity"
  run_source_checks
  python3 scripts/quality/repository_policy.py
  python3 scripts/runtime/verify-embedded-runtime.py app/src/main/agent-fleet >/dev/null
  git diff --check
}

run_script_tests() {
  say "running release/runtime and rollback regressions"
  python3 -m unittest discover -s scripts/tests -p 'test_*.py'
  scripts/migration/rehearse-backup-rollback.sh
}

run_package() {
  say "building the complete debug APK set"
  "$gradle" :app:assembleDebug --daemon --no-build-cache --parallel --console=plain
  python3 scripts/quality/verify_debug_apks.py >/dev/null
  say "debug APK signatures, identities, archives, and ABI set verified"
}

run_local() {
  run_script_tests
  say "running all JVM tests, debug lint, and package builds"
  "$gradle" test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest \
    --daemon --no-build-cache --parallel --console=plain
  python3 scripts/quality/verify_debug_apks.py >/dev/null
  say "local JVM/lint/package gate passed"
}

run_static
case "$mode" in
  static)
    say "static gate passed"
    ;;
  package)
    run_package
    ;;
  local)
    run_local
    ;;
  full)
    run_local
    say "running the protected Pixel 7 / API 36 instrumentation gate"
    bash scripts/debug/android-check.sh full
    python3 scripts/quality/verify_debug_apks.py >/dev/null
    say "full local-equals-CI gate passed"
    ;;
esac
