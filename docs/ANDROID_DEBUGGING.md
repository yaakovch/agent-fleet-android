# Emulator-first Android debugging

The Pixel 7 / Android 16 emulator is the primary Android development target.
The S23FE is a final manual smoke target only: automated scripts must never
install, type, scroll, capture, or run ADB commands against it.

## Daily commands

Run the fast Compose scenario suite on the existing Windows AVD:

```bash
bash scripts/debug/android-check.sh fast
```

Run JVM tests plus the canonical Gradle-managed Pixel 7 / API 36 device:

```bash
bash scripts/debug/android-check.sh full
```

When `/dev/kvm` is unavailable (as in this WSL environment), `full` uses the
isolated Windows API 36 AVD and runs the same JVM, Compose, and golden suites.
KVM-enabled Linux and CI use the Gradle-managed device.

Generate candidate screenshot references for review:

```bash
bash scripts/debug/android-check.sh update-goldens
```

The runner uses an isolated ADB server on port 5038, accepts only an API 36
x86_64 emulator, and verifies `ro.kernel.qemu=1` before uninstalling or
installing anything. A physical `ANDROID_SERIAL` is rejected. Results are kept
under `build/reports/agent-fleet/emulator/`; successful output is deliberately
short and failures point to the full log and instrumentation report. Failed
instrumentation runs also pull screenshot actual/diff output into that run's
`device-output` directory before exiting.

When the runner cold-starts the Windows AVD, it passes the same isolated ADB
port into the emulator process. This is required because an emulator that
registers itself with the default port 5037 remains invisible to the safe test
runner even after Android finishes booting.

The canonical local AVD is `AgentFleet_S23FE_API36`, configured as a Pixel 7 at
1080×2400 and 420 dpi. The Gradle managed-device equivalent is
`agentFleetPixel7Api36`.

## Coverage

JVM tests cover parsers, lifecycle decisions, diagnostic redaction, bounded
journal rotation, and metadata-only archives. Compose instrumentation covers
Sessions and More, repository retry/download states, Native bottom-anchored
conversation structures, grouped tools, task boards, active/stale limits, and
one- and three-question Plan prompts with exactly-once submission.

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
