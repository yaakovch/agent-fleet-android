#!/usr/bin/env bash
set -euo pipefail

report="${1:-}"
[[ -f "$report" ]] || {
  echo "usage: $0 /path/to/instrumentation.txt" >&2
  exit 2
}

if grep -qE \
  'INSTRUMENTATION_STATUS_CODE: -2|INSTRUMENTATION_FAILED|FAILURES!!!|INSTRUMENTATION_RESULT: shortMsg=|Process crashed' \
  "$report"; then
  exit 1
fi

grep -qE '^OK \([1-9][0-9]* tests?\)[[:space:]]*$' "$report"
grep -qE '^INSTRUMENTATION_CODE: -1[[:space:]]*$' "$report"
