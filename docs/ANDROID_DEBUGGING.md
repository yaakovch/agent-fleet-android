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
configuration after waiting for the targeted broadcast and application-thread
barriers with `am wait-for-broadcast-barrier --flush-broadcast-loopers
--flush-application-threads`. Require both barrier completion markers; a fixed
sleep does not establish delivery. The fixture then performs an orientation
round trip, restores and verifies the original
rotation policy, and repeats that refresh when restoring the original overlays.
Each comparison retains content bounds and the status-bar inset as JSON.
Managed `full` runs goldens and the functional suite in separate instrumentation
processes, preserving all tests exactly once. The golden process also needs the
display refresh: either isolation or the refresh alone can retain old insets.
Both phases retain raw results and screenshots and require terminal `OK` and
instrumentation code `-1`, in addition to Gradle success. Terminal service tests
wait for first layout/insets dispatch before asserting exact chrome geometry.

Host setup coverage exercises More → Find and repair hosts, account review,
exactly one pairing and repair action, and visible completion. The packaged
bridge test can run the real agent with its historical 1.5.0 advertisement while
keeping session inventory synthetic. Keep the captured review and repaired
screens separate from the real pinned loopback SSH/input evidence. A discovery
list is not proof that a host session can be opened. Live Tailnet probes use the
registry's exact stable entrypoint and wait for capabilities before requesting a
snapshot; the physical phone remains user-operated.

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

The Linux host-repair archive is an embedded executable input. Use the same
artifact validator for source assets and the signed APK, and exercise corruption,
source/component mismatch, and a nonportable target. Source-directory validation
alone does not prove the APK's complete asset inventory accepts the new input.
After a host-runtime upgrade, also inspect that host's activated registry and
sourced shell configuration: legacy records lack endpoint identity evidence, and
an unconditional local-host array append can duplicate a registry entry. Preserve
existing members and overrides when repairing these independent configuration
inputs, then check the actual installed CLI and its remote inventory.

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

## Native question provider acceptance

`AgentFleetComposeTest.liveNativeQuestionsReachProviderAndContinue` is an optional
connected probe. It reads an actual conversation snapshot from the protected
emulator's `/sdcard/Download/agent-fleet-native-question-snapshot.json`, taps the
second option for every question and writes the exact answer payload to the
corresponding `-answer.json`. An external isolated-provider driver sends that
payload through `wtmux-conversation answer` and writes `-response.json` containing
the matching receipt and a fresh snapshot with the agent continuation. The probe
asserts one submission, closes only after receipt, and captures prompt/tap/continued
images through PlatformTestStorage. It exercises Native UI and the production
bridge; the file relay substitutes the Android transport. It must not be reported
as an SSH/controller integration test.

The probe skips when its input fixture is absent. An `OK (1 test)` report can
therefore be a skip: acceptance additionally requires no assumption violation,
the actual tap payload, provider receipt, continuation and inspected images.
Remove only those three temporary fixture files after the probe so routine full
suites skip it instead of replaying an already answered provider request.

UiAutomation shell commands are argument-tokenized, not interpreted as shell
syntax. Use `executeShellCommandRw` with `tee` to transfer JSON without shell
quoting or redirection. Capture a real current provider transcript: Codex 0.155.1
ordinary messages use `item_completed` UserMessage/AgentMessage records; its async
answers use quoted titles while newer upstream versions use explicit reply IDs.

Conversation Markdown prose is hosted in Android TextViews. Compose text matchers
do not inspect those views; use the actual view hierarchy for visible-content
assertions and retain the rendered screenshot. A missing Compose text node alone
is not evidence that the conversation text is absent from the screen.


## Packaged Native controller acceptance

`NativeQuestionControllerTest` uses the actual NativeSessionController,
packaged launcher, pinned OpenSSH, host runtime and isolated Codex process.
`scripts/debug/native-controller-probe.py` supplies a synthetic Responses endpoint
that validates all three selected answers before generating continuation. No API
account or physical phone is used. Start/prepare/verify/stop the fixture explicitly;
reserve/register HTTP 9802 and SSH 9803 through ports_list before starting it.
Use the protected runner with this focused class and a guarded emulator push of
`fixture.json` to `/sdcard/Download/agent-fleet-controller-fixture.json`.
The private fixture key permits only that isolated session's stream/answer calls.
Keep SSH StrictModes enabled: authorized_keys lives in a protected home directory,
not underneath the world-writable /tmp ancestor.

Capture prompt, each tap and visible continuation, then require `verify` to prove
one actual launcher submission, one matching bridge receipt and one provider
output containing exactly the selected answers. Bind these to the installed APK
hash/revision/version and emulator identity in the flow receipt. A fixture-free
full run skips this connected class and does not replace the focused acceptance.
Remove the emulator fixture JSON after acceptance and use `stop` to terminate only
its isolated processes and delete its ephemeral authorization/key files.

A Kotlin daemon left in another network namespace can leave compilation waiting
on an unreachable loopback socket. For that invocation, use
`-Pkotlin.compiler.execution.strategy=in-process`; for the protected runner,
set `GRADLE_OPTS=-Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process`.
Bash does not reliably preserve dotted environment-variable names for child
commands, so do not pass this through a dotted ORG_GRADLE_PROJECT variable.
Keep the normal Java-17 launcher and emulator safety checks.

The Windows AVD may not reach a WSL fixture through its LAN address or the
emulator host alias. Preserve pinned SSH and use a loopback-only transparent TCP
relay plus isolated `adb -P 9801 -s emulator-5554 reverse tcp:9804 tcp:9804` after
verifying API 36/x86_64/qemu. The fixture sees the emulator address 127.0.0.1:9804;
SSH still terminates on the WSL fixture at 127.0.0.1:9803. Reserve/register both
listeners, keep SSH StrictModes and forced-session restrictions, verify the
retrieved host key, and remove the reverse mapping and relay during cleanup.
This is a network tunnel, not an answer-callback or provider substitution.

The checked-in tunnel consists of `native-controller-tcp-relay.mjs` (Windows
Node listener) and `native-controller-tcp-relay.py` (WSL stdio/TCP leg). Start the
fixture with `start --root <private-new-directory> --source <clean-runtime-source>
--listen-address 127.0.0.1`, then `prepare --root <same-directory> --models-cache
<metadata-only-models_cache.json> --address 127.0.0.1 --ssh-port 9804`. The `start`
listener is SSH 9803; `prepare --ssh-port` describes the emulator-facing endpoint.
From Windows, run Node with these arguments (quote paths containing spaces):

```text
node <Windows-path-to-native-controller-tcp-relay.mjs> <Linux-path-to-native-controller-tcp-relay.py> 9803 9804 Ubuntu
```

Record the emitted Windows PID for targeted cleanup. In WSL, obtain a Windows
path with `wslpath -w`; pass it as a subprocess argument rather than interpolating
a UNC path into a shell command. Before every manual artifact pull, fixture push,
or reverse mapping, verify the same isolated server/serial is API 36, x86_64 and
qemu=1. Successful focused runs retain test results but do not automatically pull
all PlatformTestStorage files: pull `/sdcard/Android/media/com.yaakovch.fleet/.`
to the report's `device-output` directory before another run uninstalls the app.
Remove only `/sdcard/Download/agent-fleet-controller-fixture.json` afterward.
Remove the reverse mapping, stop the recorded relay process after checking its
command line, run the fixture's `stop`, and update/validate the ports registry.
