# Emulator-first Android debugging

The Pixel 7 / Android 16 emulator is the primary Android development target.
The S23FE is a final manual smoke target only: automated scripts must never
install, type, scroll, capture, or run ADB commands against it.

## Daily commands

Run a focused JVM regression through the Java-17-aware launcher:

```bash
scripts/debug/android-gradle.sh :app:testDebugUnitTest --tests com.termux.app.ExampleTest --console=plain
```

Run the fast Compose scenario suite on the existing Windows AVD:

```bash
bash scripts/debug/android-check.sh fast
```

Run JVM tests plus the complete Pixel 7 / API 36 suite. Under WSL, `auto`
prefers the protected persistent Windows AVD even when `/dev/kvm` is usable:

```bash
bash scripts/debug/android-check.sh full
```

For a publish-bound change, run the focused regression and then `full`; do not
also run `fast`, because `full` contains that Compose coverage. `fast` remains
the normal iteration loop for UI changes that are not immediately releasing.

Force the Gradle-managed emulator for CI parity or after changing emulator
orchestration:

```bash
AGENT_FLEET_EMULATOR_BACKEND=managed bash scripts/debug/android-check.sh full
```

`AGENT_FLEET_EMULATOR_BACKEND` accepts `auto`, `windows`, or `managed`.
`AGENT_FLEET_USE_WINDOWS_AVD=1` remains a compatibility alias for `windows`.

Generate candidate screenshot references for review:

```bash
bash scripts/debug/android-check.sh update-goldens
```

After verifying an official x86_64 Termux APK against its upstream checksum,
prove package-level coinstallation on the same isolated emulator:

```bash
AGENT_FLEET_OFFICIAL_TERMUX_APK=/absolute/termux-x86_64.apk bash scripts/debug/android-check.sh coinstall
```

After both release lanes are signed, prove their IDs, certificate continuity,
private roots, and coinstallation before testing transfer behavior manually:

```bash
AGENT_FLEET_PERMANENT_APK=/absolute/permanent-universal.apk AGENT_FLEET_LEGACY_APK=/absolute/legacy-universal.apk bash scripts/debug/android-check.sh migration-lanes
```

The runner uses an isolated ADB server on port 5038, accepts only an API 36
x86_64 emulator, and verifies `ro.kernel.qemu=1` before uninstalling or
installing anything. A physical `ANDROID_SERIAL` is rejected. Results are kept
under `build/reports/agent-fleet/emulator/`; successful output is deliberately
short and failures point to the full log and instrumentation report. Failed
instrumentation runs also pull screenshot actual/diff output into that run's
`device-output` directory before exiting.

Instrumentation is installed and invoked as `com.yaakovch.fleet.test`; generated
media is collected from `Android/media/com.yaakovch.fleet`. The runner also
cleans the temporary `com.termux` legacy lane on its isolated emulator so stale
bridge state cannot mask permanent-ID failures.

When the runner cold-starts the Windows AVD, it passes the same isolated ADB
port into the emulator process. This is required because an emulator that
registers itself with the default port 5037 remains invisible to the safe test
runner even after Android finishes booting.

The canonical local AVD is `AgentFleet_S23FE_API36`, configured as a Pixel 7 at
1080×2400 and 420 dpi. The Gradle managed-device equivalent is
`agentFleetPixel7Api36`.

## Build performance

The debug runner keeps Gradle's daemon and parallel project execution, while
leaving the build cache disabled. A three-run controller benchmark of
`:app:assembleDebugAndroidTest` measured a 33.5% median gain from daemon reuse,
a further 48.1% from parallel execution, and only 2.3% from build cache. Re-run
the benchmark after material Gradle, filesystem, or toolchain changes:

```bash
scripts/debug/benchmark-gradle.sh
```

Results are written under `build/reports/agent-fleet/gradle/`. Keep a candidate
only when its independent median improvement is at least 10% and subsequent
focused/full validation passes.

## Coverage

JVM tests cover parsers, lifecycle decisions, Classic drawer refresh safety and phone-local drawer state,
diagnostic redaction, bounded journal rotation, and metadata-only archives. Compose instrumentation covers
Sessions and More, repository retry/download states, Native bottom-anchored
conversation structures, grouped tools, task boards, active/stale limits, and
one- and three-question Plan prompts with exactly-once submission, and the unified
drawer's search, remembered view, swipe confirmation, offline, and local-shell flows.

Runtime-ordering coverage must include installing a verified hotfix before an
APK-only version increase whose embedded baseline is unchanged. The app may
promote its monotonic replay floor in that case, but it must not reactivate the
older baseline. A genuinely changed embedded baseline still takes precedence.

Dark screenshot references are normalized to exactly 393×852 pixels. A pixel
is different when any RGB channel differs by more than 8, and the test fails
when more than 0.5% of pixels differ. Candidate references are generated as
artifacts only; replacing a committed reference always requires human review.

## In-app diagnostics

More → Diagnostics runs local five-second checks, parallel read-only host
checks with a twenty-second budget, and reports if the full run exceeds the
forty-five-second target. The journal retains at most 200 events, 256 KiB, and
seven days in app-private storage.

Export always shows a preview first. The ZIP contains only
`diagnostics.json` and `events.ndjson` with schema
`agent-fleet-diagnostics-v1`. It excludes prompts, responses, transcripts,
terminal output, credentials, tokens, invitations, attachments, and repository
paths.

## Final phone smoke

After emulator and release verification pass, install through the normal
signed in-app update flow. Manually check launch, one existing Native session,
one terminal fallback, one file download, one Plan answer, limits, and
Diagnostics preview/export. Do not uninstall or reset the phone during this
smoke test.
