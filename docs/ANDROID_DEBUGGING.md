# Emulator-first Android debugging

The Pixel 7 / Android 16 emulator is the primary Android development target.
The S23FE is a final manual smoke target only: automated scripts must never
install, type, scroll, capture, or run ADB commands against it.

## Daily commands

Run the offline/static policy gate after changing scripts, workflows, Gradle
integrity metadata, runtime provenance, or repository policy:

```bash
bash scripts/quality-gate.sh static
```

Run the canonical local gate used by push and pull-request CI. It discovers
extensionless Bash and Python sources, verifies immutable workflow actions,
the Gradle wrapper/distribution and dependency checksums, runtime
license/SBOM/provenance, script regressions and rollback, all JVM variants,
debug lint, and the signed debug APK identity/archive/ABI set:

```bash
bash scripts/quality-gate.sh local
```

For a cross-layer or pre-handoff change, run the same checks plus exactly one
protected API 36 instrumentation suite:

```bash
bash scripts/quality-gate.sh full
```

Under WSL, `full` retains the protected Windows AVD default. The emulator CI
workflow invokes that exact command with
`AGENT_FLEET_EMULATOR_BACKEND=managed`. `package` is an additional
build-workflow mode for producing and verifying debug artifacts; it is not a
substitute for `local`.

Run a focused JVM regression through the Java-17-aware launcher:

```bash
scripts/debug/android-gradle.sh :app:testDebugUnitTest --tests com.termux.app.ExampleTest --console=plain
```

Run the fast Compose scenario suite on the existing Windows AVD:

```bash
bash scripts/debug/android-check.sh fast
```

Run one focused instrumentation class on the same protected emulator:

```bash
AGENT_FLEET_INSTRUMENTATION_CLASS=com.termux.app.AgentFleetImageImportTest bash scripts/debug/android-check.sh focused
```

For embedded fleet-registry changes, use
`com.termux.app.AgentFleetEmbeddedRegistryTest`. It installs the packaged
architecture-neutral runtime and registry on the x86_64 emulator, rewrites the
config to the legacy migration root, and proves an idempotent repair back to
the verified app-owned registry. The JVM metadata test separately covers the
production arm64 automatic-repair decision.

The launcher recovery regression damages an activated APK registry record, starts
the production `AgentFleetActivity`, and waits for `FleetSnapshotStore` to repair
the exact verified record and obtain a fresh bridge snapshot without pairing.
It retains a screenshot and `startup-configuration-recovery.json`. This covers
local upgrade/recovery separately from the synthetic inventory and SSH flow below.
JVM coverage verifies bounded repair retries and foreground VPN/network callbacks,
including suppression of initial network notifications during a cold handshake.
VPN availability triggers reconnection; it does not replace host identity checks.

Run JVM tests plus the complete Pixel 7 / API 36 suite. Under WSL, `auto`
prefers the protected persistent Windows AVD even when `/dev/kvm` is usable:

```bash
bash scripts/debug/android-check.sh full
```

`android-check.sh` remains the direct emulator iteration runner. Use
`quality-gate.sh full` when the repository-wide local=CI checks are part of
the acceptance gate.

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

Golden references use a flat display viewport. The managed Pixel 7 image
enables two device-model overlays that add a camera cutout and a taller status
bar. `AgentFleetGoldenTest` temporarily disables only those enabled Pixel 7
overlays after verifying API 36, x86_64 and the emulator identity, then restores
them after the class. Functional tests retain the normal device viewport;
reference images and comparison tolerances are unchanged.
The golden host explicitly draws edge to edge because Native owns its status-bar
padding. Overlay changes can leave WindowManager reporting the old cutout inset
even after System UI pixels update. The guarded fixture refreshes display
configuration with an orientation round trip, restores and verifies the original
rotation policy, and repeats that refresh when restoring the original overlays.
Each comparison retains content bounds and the status-bar inset as JSON.
Managed `full` runs goldens and the functional suite in separate instrumentation
processes, preserving all tests exactly once. The golden process also needs the
display refresh: either isolation or the refresh alone can retain old insets.
Both phases retain raw results and screenshots and require terminal `OK` and
instrumentation code `-1`, in addition to Gradle success. Terminal service tests
wait for first layout/insets dispatch before asserting exact chrome geometry.

The discovery recovery test runs the packaged bridge and agent against synthetic
inventory, then opens a real managed terminal through a pinned SSH connection to
`127.0.0.1:9840` inside the emulator. It asserts terminal input and output before
capturing the terminal screen and connection evidence. The fixture grants only
the app's declared first-run permissions after verifying the emulator identity;
it does not contact a user host or operate a physical phone.

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
The Windows emulator path also sets `hide_error_dialogs=1` before
instrumentation. This prevents a System UI ANR dialog from covering golden
screens while preserving crash detection through the required terminal
instrumentation result and status codes.
It also collapses any Quick Settings or notification panel retained by the
headless AVD immediately before instrumentation, so screenshot tests capture
the Compose host rather than system UI left open by an earlier run.
Focused mode requires an explicit `com.termux.app.*` instrumentation class and
retains the same install, device-validation, artifact, and result-checking path
as the complete suite.

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

## Build integrity updates

The quality gate enforces the reviewed wrapper JAR hash, the Gradle 8.11.1
distribution checksum, strict dependency locks for the buildscript and every
project, `gradle/verification-metadata.xml` checksums, fixed dependency
versions, immutable GitHub Action commits, fixed Ubuntu runner versions, and
explicit workflow permissions.

Do not regenerate integrity metadata during an ordinary build. When an
approved dependency or toolchain change intentionally alters the resolved
graph, regenerate all dependency locks with:

```bash
bash scripts/debug/android-gradle.sh resolveAndLockAll --write-locks \
  --no-daemon --no-build-cache --console=plain
```

Review every lock-file change. If the graph introduces or changes downloaded
artifacts, separately run the relevant test/lint/package tasks through
`scripts/debug/android-gradle.sh` with
`--write-verification-metadata sha256`, then review every added or removed
component and checksum. Prove the reviewed graph is locally complete by
running a focused Gradle task with `--offline`, followed by
`bash scripts/quality-gate.sh local`. Never combine lock generation with a
dependency-verification override. A wrapper version change must update and
independently verify both the distribution checksum and wrapper JAR
provenance.

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
