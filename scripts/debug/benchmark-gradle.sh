#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
gradle="$root/scripts/debug/android-gradle.sh"
runs="${AGENT_FLEET_BENCHMARK_RUNS:-3}"
task="${AGENT_FLEET_BENCHMARK_TASK:-:app:assembleDebugAndroidTest}"
[[ "$runs" =~ ^[1-9][0-9]*$ ]] || { echo "AGENT_FLEET_BENCHMARK_RUNS must be positive" >&2; exit 2; }
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
report_dir="$root/build/reports/agent-fleet/gradle/$stamp"
mkdir -p "$report_dir"
measurements="$report_dir/measurements.tsv"
: >"$measurements"

echo "[gradle-benchmark] priming $task"
"$gradle" "$task" --daemon --console=plain >"$report_dir/prime.log" 2>&1

measure() {
  local candidate="$1" run="$2"
  shift 2
  local start end millis
  start="$(date +%s%N)"
  "$gradle" "$task" "$@" --console=plain >"$report_dir/$candidate-$run.log" 2>&1
  end="$(date +%s%N)"
  millis=$(( (end - start) / 1000000 ))
  printf '%s\t%s\t%s\n' "$candidate" "$run" "$millis" >>"$measurements"
  echo "[gradle-benchmark] $candidate run $run: ${millis}ms"
}

for run in $(seq 1 "$runs"); do measure baseline "$run" --no-daemon --no-build-cache --no-parallel; done
for run in $(seq 1 "$runs"); do measure daemon "$run" --daemon --no-build-cache --no-parallel; done
for run in $(seq 1 "$runs"); do measure build-cache "$run" --daemon --build-cache --no-parallel; done
for run in $(seq 1 "$runs"); do measure parallel "$run" --daemon --no-build-cache --parallel; done

python3 - "$measurements" "$report_dir/summary.json" "$task" <<'PY'
import json, pathlib, statistics, sys
measurements, output, task = sys.argv[1:]
values = {}
for line in pathlib.Path(measurements).read_text(encoding="utf-8").splitlines():
    candidate, _, millis = line.split("\t")
    values.setdefault(candidate, []).append(int(millis))
medians = {name: statistics.median(samples) for name, samples in values.items()}
parents = {"daemon": "baseline", "build-cache": "daemon", "parallel": "daemon"}
results = {}
for name, median in medians.items():
    parent = parents.get(name)
    reference = median if parent is None else medians[parent]
    improvement = 0.0 if parent is None else (reference - median) * 100.0 / reference
    results[name] = {
        "samplesMillis": values[name],
        "medianMillis": median,
        "improvementPercent": round(improvement, 1),
        "relativeTo": parent,
        "retain": parent is not None and improvement >= 10.0,
    }
report = {"schemaVersion": 1, "task": task, "thresholdPercent": 10, "results": results}
pathlib.Path(output).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
for name in ("daemon", "build-cache", "parallel"):
    value = results[name]
    decision = "retain" if value["retain"] else "do not retain"
    print(f"{name}: {value['improvementPercent']}% ({decision})")
PY
echo "[gradle-benchmark] report: $report_dir/summary.json"
