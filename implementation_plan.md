# Agent Fleet Android Companion Implementation Plan

Status: approved for execution on 2026-07-12.

## Milestone 1: Repository and Toolchain

- Preserve the upstream Termux 0.118.3 history and GPLv3 provenance.
- Install JDK 17, current Android SDK/build tools, and an accelerated Android 16
  S23FE-sized Windows emulator.
- Add provenance, privacy, security, migration, release, and contribution docs.
- Add public CI for unsigned builds, tests, lint, licenses, and dependencies.

Gate: upstream baseline bootstrap, shell, `pkg`, storage, PTY, and tests pass.

## Milestone 2: Mobile Shell and Terminal

- Upgrade for Kotlin/Material 3 without changing app ID, executable paths, or
  target-SDK behavior.
- Validate the fixture prototype's navigation, typography, density, and touch
  targets at S23FE dimensions.
- Bind Compose to `TermuxService`, embed `TerminalView`, and implement tabs,
  Classic recovery, themes, terminal controls, extra keys, gestures, hardware
  input, and memory-only scrollback.
- Implement Android Compose and Classic Terminal input modes.

## Milestone 3: Fleet Sessions

- Add guided runtime provisioning, restore-or-QR pairing, progress, doctor
  gating, and sanitized diagnostics.
- Supervise JSONL while visible with strict parsing, capabilities, bounded
  reconnect, and validated cache fallback.
- Implement grouped/searchable sessions and all safe actions.
- Open remote terminal tabs using existing wtmux attach argument arrays and add
  visible-only Retry/Close reconnect behavior.

## Milestone 4: Composer and Images

- Add in-memory drafts, literal multiline Send, attachment chips, Share,
  multi-share, picker, and camera input.
- Normalize/optimize images and retain failed uploads for manual retry.
- Add `wtmux image send --json`, seven-day host cleanup, local temp deletion,
  and path insertion without submission.

## Milestone 5: Limits, Schedules, and Health

- Add strict designated quota-source data and verified bundle sync.
- Add metadata-only Codex/Claude collectors with explicit failover.
- Add desktop-managed source mutations/settings and capability-negotiated safe
  `profileAlias` session creation.
- Implement Limits, recommendations, hard-limit actions, schedules, health,
  versions, pairing, and diagnostics.

## Milestone 6: Release, Migration, and Rollout

- Generate an offline private signing key with two encrypted backups.
- Script local signing and gaming-desktop publication with work-m fallback.
- Implement manual update checks and signature/checksum verification.
- Build backup/restore tooling and complete emulator migration/rollback.
- Verify both real backups, perform S23FE cutover, and run a seven-day soak.

## Milestone 7: Native Session View

- Add a dedicated wtmux conversation protocol with cursor-paged history, live
  follow, typed approvals, current-directory listing, strict validation, and
  content-free logs. Implement Codex, Claude Code, and Copilot adapters plus a
  cleaned-pane fallback and exact metadata for newly launched Windows sessions.
- Layer a Compose session screen over the live `TerminalView`; implement the
  feed, Markdown/code/diff rendering, tool cards, status, native approvals,
  cross-device updates, reconnect, bounded in-memory paging, and an explicit
  Native/Terminal state machine.
- Move the current AI composer into Native view while preserving Insert,
  Send/Enter, Ctrl+C, Attach, and Camera. Add Bash/Zsh OSC shell integration,
  command/result cards, command helpers, breadcrumbs, and a directory-only
  browser. Unsupported and alternate-screen states fall back automatically.
- Install two signed previews: first a read-only Linux Codex feed, then the
  feature-complete build. Deploy the compatible host runtime to gaming-desktop
  before work-m. Make Native the default in the next signed build immediately
  after the complete automated and S23FE checklist passes.

Gate: all three AI adapters, Linux and Windows behavior, old-session fallback,
simultaneous desktop input, shell cards, safe approvals, attachments, takeover,
offline recovery, large history/font behavior, updater, SSH, and Classic
Terminal pass without persistent transcript data.

## Milestone 8: Native Conversation Protocol v2

- Coordinate a protocol-v2 wtmux host runtime, phone client, and Android build.
  Add structured interaction mode, complete tool lifecycle fields, normalized
  question schemas, and revision-bound `conversation answer` delivery. Surface
  version mismatch explicitly while leaving the real terminal available.
- Connect per-session Plan state to the shared composer. Fold adjacent tools
  into stable ordered display groups across live updates and history paging,
  add safe action/target titles, and render formatted Input/Result details.
- Add one-question-at-a-time native forms for Codex, Claude, and Copilot,
  including multi-select and Other, a persistent pending shortcut, transcript
  confirmation, cross-client resolution, timeout recovery, and fail-closed
  Terminal fallback.

Gate: protocol, lifecycle merge, grouping boundaries, pagination, Plan-mode
reconnect, all question types, stale revisions, concurrent answers, malformed
frames, and S23FE interaction tests pass without persistent conversation data.

## Milestone 9: Human Tool Cards And Location Browser

- Consume optional semantic tool presentations and build accessible terminal-
  themed preview/full/raw layers with ordered call groups and Copy actions.
- Refactor native question forms into provider-aware first-input advancement,
  Back revision, final Submit, transcript-confirmed completion, and retryable
  delivery errors that preserve the form.
- Add directory list/create bridge operations, capability negotiation, a
  shortcut-aware accessible folder browser, explicit folder confirmation, and
  locally persisted per-host/backend recents.
- Replace free-text project launch with Host, Backend, location, folder, label,
  and tool stages. Keep paths out of titles/lists and expose them in details.
- Add parser, state-machine, Compose, accessibility, lifecycle, and release
  upgrade tests; ship signed `.16` (version code 1018) after host/client contract
  and physical S23FE verification pass.

## Verification

- Preserve upstream terminal/service/file-provider/package tests.
- Add Compose navigation, screenshot, accessibility, and lifecycle tests.
- Add protocol/schema/framing/revision/idempotency/injection tests.
- Add provisioning, pairing, reconnect, alias, quota, schedule, image, update,
  migration-manifest, and rollback tests.
- Exercise clean/restored onboarding, process death, reboot, offline cache,
  Tailscale loss, multi-image share, quota failure, and incompatible hosts on
  Android 16 before phone cutover.

## Milestone 10: Built-In Runtime And Offline Repair

1. Add a reproducible arm64 package lock and fetch/verification pipeline for
   pinned official Termux artifacts. Generate license/source inventory and an
   SBOM, embed the verified local package set during release builds, and keep
   unsupported ABIs explicit rather than silently downloading dependencies.
2. Build a deterministic wtmux archive from the cross-repository pinned commit.
   Commit an `embedded-runtime-v1` descriptor and make release verification
   prove the descriptor, archive, package lock, APK assets, and git commits all
   agree.
3. Implement an Android provisioner that distinguishes clean and restored
   prefixes, installs only missing/below-floor packages offline, installs the
   APK baseline through `wtmux-runtime`, runs a bounded doctor check, and
   preserves all user-owned state. Add an explicit Repair action and an offline
   `Preparing terminal` first-launch surface.
4. Extend wtmux runtime activation with an immutable baseline pointer, bounded
   active/previous/baseline retention, richer status, and baseline recovery.
   Keep launcher replacement atomic and retain the current rollback semantics.
5. Add strict `client-policy-v1` pairing artifacts and parsers. Replace manual
   Android update-source entry with pairing-provisioned primary/fallback APK
   and runtime endpoints while preserving a safe migration read of the old app
   preference for one release.
6. Add dedicated Ed25519 runtime release-key tooling, `runtime-update-v1`
   generation/verification, foreground six-hour checks, artifact verification,
   atomic activation, doctor gating, automatic rollback, replay protection,
   diagnostics, Check, and Roll Back.
7. Produce signed `.18` / version code 1020 as arm64-primary and universal
   recovery artifacts. Validate clean offline arm64 install, restored-prefix
   preservation, newer-package preservation, tamper/replay/interrupted repair,
   primary/fallback endpoints, rollback, and Android 16 lifecycle behavior.
   Upgrade the S23FE in place only after the emulator matrix passes, then begin
   a seven-day daily-driver soak.

Gate: a clean arm64 install reaches a usable local terminal without network or
manual Termux commands; a restored phone is never reset or downgraded; a signed
runtime hotfix activates and a bad one rolls back; diagnostics can recover the
APK baseline; release artifacts are reproducible and fully traceable.

## Milestone 11: Native Reliability Parity Release

- Share one foreground fleet snapshot owner between Sessions, Limits, and Native
  terminal surfaces; react immediately to revision changes and stop it when all
  three surfaces are hidden.
- Port the Windows-validated pinned action panel, inline linked hard-limit
  actions, Close/Kill menu, scroll rules, and complete Copy coverage while
  retaining Android's semantic tool presentation.
- Align protocol fixtures and lifecycle merging with wtmux and Windows, add
  reconnect and toast-suppression tests, and run JVM, Compose/instrumentation,
  runtime-bundle, and signed-release verification.
- Build signed `0.118.4-agentfleet.21` (`1023`) with the compatible embedded
  runtime, install replace-in-place on the S23FE, and repeat the Windows
  acceptance matrix without uninstalling or resetting Termux data.

## Milestone 12: Native Lifecycle Efficiency

- Gate remote conversation streaming on foreground Native visibility and stop
  it immediately for manual or automatic Terminal mode.
- Treat unchanged heartbeat/status frames as no-ops and suppress invisible
  TerminalView invalidation while preserving emulator output, local shell
  extraction, and alternate-screen takeover.
- Cover the visibility and heartbeat decisions with JVM tests, run the complete
  debug unit suite, and ship signed `0.118.4-agentfleet.22` (`1024`) as an
  in-place S23FE update without changing the wtmux protocol or runtime bundle.

## Milestone 13: Structured Work And Interaction Repair

- Parse and merge the additive `conversation.structured-work.v1` task and plan
  records and consume the byte-identical shared golden fixture used by wtmux
  and Windows.
- Replace expanded inline tools with bounded previews and a dedicated viewer;
  add active-task boards, completed-board collapse, and Markdown plan viewers.
- Replace oversized pending cards with a pinned action bar and scrollable
  viewport-bounded dialog. Deliver final single/boolean answers on tap, use Done
  for multi-select and Send for text, and keep revision/error confirmation.
- Preserve newest-first paging and anchors, suppress redundant Working/Done
  rows, and keep large bodies out of the lazy feed. Validate long output, long
  questions, task merging, plan gates, reconnects, and S23FE rendering.
- Build signed `0.118.4-agentfleet.26` (`1028`) with the compatible embedded
  runtime, install it in place on the S23FE without resetting Termux data, then
  deploy the matched wtmux host runtime after the Windows and Android clients.

Gate: the shared fixture, complete JVM suite, release build verification, and
physical S23FE smoke pass; tool details, tasks, plans, and long questions remain
readable and interactive with no blank frames or unexpected terminal input.

## Milestone 14: Session Repository Downloads

- Add strict repository page and download state models matching wtmux and the
  Windows client; request list/search metadata through the existing foreground
  bridge without snapshot persistence.
- Add Download a file to Session More and implement a viewport-bounded Compose
  browser with Up, recursive search, clear search, hidden-file toggle, paging,
  large-file confirmation, progress, cancellation, and completion state.
- Run the file stream through the built-in wtmux runtime into Android Downloads,
  verify its bounded result, media-scan the completed file, and expose it via a
  read-only scoped FileProvider URI on explicit Open file.
- Add parser, transfer, cancellation, Compose, full JVM, release-build, and S23FE
  smoke coverage. Ship signed `0.118.4-agentfleet.28` (`1030`) after Windows
  beta.6 and before activating the matching host runtime.

## Milestone 15: Stale Hard-Limit Attention Hotfix

- Defensively filter fleet attention to active states and suppress the Native
  card immediately on Dismiss while retaining exact host identity.
- Keep optimistic suppression across snapshot churn, restore only after a real
  failure, and cover filtering plus suppression with JVM tests.
- Embed wtmux `git-838dd1a`, run the full JVM and runtime-bundle checks, and ship
  signed `0.118.4-agentfleet.29` (`1031`) after Windows beta.7 and before host
  activation. Preserve `.28`, its APK runtime baseline, and current phone data
  as rollback paths.

## Milestone 16: Repository Browser Readiness Hotfix

- Keep one correlated bridge only while the repository browser is in use,
  reusing it for navigation/search and releasing it on dismissal or background.
- Give the S23FE a full-width search layout, exclusive loading/error/empty
  states, and Retry of the exact failed list or search operation.
- Embed the matched wtmux runtime, pass lifecycle/UI/JVM/release checks, and ship
  signed `0.118.4-agentfleet.30` (`1032`) after Windows beta.8 while preserving
  `.29` and phone data for rollback.

## Milestone 17: Repository Download Finalization Hotfix

- Embed the shared receiver that finalizes verified downloads without hard
  links so Android emulated Downloads works after repository browsing succeeds.
- Collapse multiline command failures to their final actionable exception
  instead of showing the first `Traceback` line; cover traceback, warning, and
  fallback formatting in JVM tests.
- Build and publish signed `0.118.4-agentfleet.31` (`1033`) for a manual in-app
  update and manual file-download smoke, without controlling the phone over ADB.

## Milestone 18: Emulator-First Android Debugging

- Add production metadata-only diagnostics with bounded local and read-only
  host checks, a rotating app-private journal, preview/copy/export UI, a strict
  `agent-fleet-diagnostics-v1` archive, and seeded-secret privacy tests.
- Add AndroidJUnitRunner and stable Compose semantics plus fixture-driven
  scenarios for Sessions/More, repository workflows, native paging and
  structured work, active/stale limits, and one- and three-question Plan
  prompts with exactly-once delivery.
- Add the canonical Pixel 7/API 36 managed device and an isolated local runner
  with fast, full, and explicit update-goldens modes. Refuse physical serials,
  assert the emulator ABI/API/qemu properties, retain full artifacts, and keep
  routine output concise.
- Add exact 393×852 dark screenshot comparison with per-channel threshold 8,
  0.5% maximum changed pixels, actual/reference/diff output, and human-reviewed
  reference updates only. Add an opt-in CI managed-device workflow.
- Run JVM, Compose instrumentation, screenshot, debug/release build, embedded
  runtime, and privacy verification. Do not access or update the S23FE; leave a
  short manual phone smoke for a later signed release decision. Publish signed
  `0.118.4-agentfleet.32` (`1034`) to the private update channel without
  installing it on the phone.

Gate: JVM and isolated emulator suites pass, reference screenshots are reviewed,
no seeded secret or private path reaches an export, and the runner cannot select
the connected S23FE.

## Milestone 19: Repository Recovery And Runtime Floor

- Consume typed repository failure codes and show Retry only for offline,
  disconnect, and timeout failures. Keep permanent legacy/custom/path failures
  readable without a button that cannot succeed.
- Treat the installed APK version code as the minimum healthy runtime sequence.
  After its embedded baseline passes health checks, activate it when accepted or
  healthy runtime state is below that floor and persist the floor atomically.
  Preserve an active runtime only when its signed accepted and healthy sequence
  is already at or above the APK floor.
- Require runtime release sequence numbers to share the app version-code
  namespace from 1035 onward. Publish sequence 1035 for app code 1034+, embed the
  same wtmux commit in signed `.33` (`1035`), and prevent any older feed entry
  from replacing that baseline.
- Cover transient/permanent repository UI, floor reconciliation, hotfix
  preservation, and release ordering in JVM and isolated API 36 emulator suites.
  Publish for manual in-app phone update only; do not operate the S23FE over ADB.

## Milestone 20: Shared Desktop Layout Contract Reservation

- Add the canonical workspace-layout-v1 golden fixture to JVM contract tests
  and validate its bounded split tree, unique pane/session identities, focus,
  ratios, and Native/Terminal modes.
- Reserve Auto, Phone, and Desktop settings plus separate phone/desktop layout
  stores in the specification only. Keep current Android runtime and Compose UI
  unchanged during the Windows beta10 release.
- Implement wide-window/DeX presentation in a later Android milestone using the
  shared behavior and the isolated API 36 emulator before any manual S23FE smoke.

## Milestone 21: Wide Workspace And Stale-Session Recovery

- Add persisted Auto/Phone/Desktop presentation state and a validated Android
  split-tree reducer/store. Auto switches at 840 dp, preserves Desktop state
  independently, and safely falls back to one empty pane on invalid data.
- Replace activity-owned terminal callbacks with a stable Termux-service broker
  and per-session observers. Reuse one managed local attachment across classic
  phone and embedded Desktop views, refactor Native controllers to pane hosts,
  and pause invisible renderers/streams while retaining assigned PTYs.
- Make managed attachment creation atomic in `TermuxService`, reconcile old
  duplicates by preserving the selected running copy (otherwise the oldest
  running copy), and keep a four-entry active/MRU cache. Route compact and
  shared-image opens through the broker, explicitly select the target PTY after
  activity startup, and release local attachments after Close or successful
  Kill without ending remote tmux.
- Filter managed PTYs from the Classic Termux drawer, cycling shortcuts, and
  local notification count; use the Agent Fleet label and local attachment
  state for Enter/Return. Cover marker validation, canonical selection,
  retention, repeated/concurrent opens, legacy cleanup, drawer isolation,
  image reuse, Back, Close, and Kill in JVM and API 36 emulator tests.
- Build the wide Compose workspace with navigation and session rails, search,
  placement actions, all split presets, touch dividers, focus, Native/Terminal,
  responsive DeX handoff, existing session actions, draft/attachment guards,
  local detach, and explicit Kill.
- Mark sessions from non-healthy hosts unavailable, disable remote mutations,
  persist bounded local Hide records until a healthy snapshot, and make
  host-offline/stale-revision outcomes deterministic and human-readable. Apply
  and validate the equivalent behavior in Windows beta.11.
- Run JVM and compact/wide API 36 Compose coverage, screenshots, full emulator,
  release, runtime, and privacy verification. Publish signed
  `0.118.4-agentfleet.34` (`1036`) for manual in-app S23FE update and DeX smoke;
  never operate the physical phone through automated ADB and fix forward as
  `.35` if required.

Acceptance record (2026-07-15): `.34` passed the complete emulator-first suite
at `build/reports/agent-fleet/emulator/20260715T204313Z-full`, including JVM,
Compose instrumentation, compact/wide UI, screenshot, release, runtime, and
privacy checks. Both signed APKs and their manifest passed release verification
with embedded wtmux `git-b4515bf`; version 1036 was published to the private
update channel. The S23FE was not accessed over ADB and remains a manual in-app
update plus Phone/DeX smoke gate.

## Milestone 22: Stop-Session Startup Revision Hotfix

- Reproduce the phone failure with an immediate revisioned Kill against a fresh
  bridge whose host snapshot is deliberately delayed. Require the operation to
  wait for bounded bridge startup instead of comparing against a transient
  connecting-only fleet revision.
- Keep the existing request shape, exact session identity, host-side revision
  check, and same-key one-retry behavior. Embed wtmux `git-be5d82d`, run shared
  and Android emulator suites, and publish signed `.35`/1037 without automated
  S23FE access.

Acceptance record (2026-07-16): the delayed-startup Kill regression failed on
the `.34` runtime and passed after `git-be5d82d`. The shared suite passed 147
Python tests, 73 Bats cases, and smoke; Windows passed 103 tests; Android fast
and full API 36 suites passed at `20260715T212120Z-fast` and
`20260715T212306Z-full`. Signed `.35`/1037 passed APK, certificate, checksum,
and embedded-runtime verification and was published for manual S23FE update.

## Milestone 23: Managed Attachment Coalescing Hotfix

- Move Agent Fleet attachment identity into `TermuxService` so compact,
  Native, image-share, and Desktop entry points atomically reuse one marked
  local PTY for each remote session.
- Reconcile legacy duplicate attachments without ending remote tmux, keep a
  four-entry active/MRU cache, and release local attachments after Close,
  replacement, retention eviction, or successful Kill.
- Hide managed attachments from the Classic Termux drawer, numbering,
  shortcuts, and local-shell notification count. Treat an uninitialized shell
  pid of zero as no child process and never signal the app process group.
- Cover canonical selection, repeated/concurrent acquisition, legacy cleanup,
  drawer isolation, image reuse, and pre-render cleanup in JVM and isolated API
  36 tests. Publish signed `.36`/1038 for manual in-app S23FE update without
  automated physical-device access.

## Milestone 24: Classic Drawer Recursion Hotfix

- Reproduce the S23FE `StackOverflowError` caused by the Classic drawer adapter
  rebuilding itself through `ArrayAdapter.clear()` inside its overridden data
  notification callback.
- Rebuild the filtered session list through an adapter-owned backing list so a
  refresh emits exactly one notification and never reenters itself.
- Add a focused Robolectric regression, run fast and full isolated API 36
  suites, and publish signed `.37`/1039 for manual in-app S23FE update.

## Milestone 25: Focused-Pane Desktop Chrome

- Replace Desktop/DeX pane rows with compact draggable title chips and one
  focused toolbar containing identity, Native/Terminal, Retry, split/layout,
  and More controls. Add Detach to the focused session action dialog.
- Suppress the embedded Native top bar only inside multi-pane Desktop/DeX;
  preserve the fullscreen phone UI and independent workspace persistence.
- Add pure chrome/swap tests plus Compose coverage for one control set, one chip
  per pane, focus, Close, and drag-to-swap. Run isolated API 36 fast/full suites
  and signed-release verification for `.40`/1042 without automated S23FE use.

Implementation record (2026-07-16): the pure reducer/chrome tests and 15-test
fast Compose run passed, followed by the full isolated Pixel 7/API 36 suite at
`build/reports/agent-fleet/emulator/20260716T163934Z-full` (20 instrumentation
tests, one optional golden candidate skipped, zero failures). Embedded runtime
verification passed for wtmux `git-fea7c08`, and the minified `.40`/1042
production APK set built successfully with zero lint errors. The physical
S23FE was not accessed. The signed arm64 and universal APKs passed checksum,
certificate-continuity, version, and embedded-runtime verification and were
published to the private update channel. The external HTTPS manifest and
arm64 APK verified byte-for-byte with SHA-256
`24856f2340c4bd6978db0307a01833fa0400248e385b57ba030cf5558a89d092`
and `8d1476a65de9b80f7b80e1ae4091e14241dfa9a26dbdf24a565551ff6ccd367f`
respectively.

## Milestone 26: Permanent App ID, Custom Runtime, And Migration Bridge

1. Preserve `.40` at commit `270b5a0d`; maintain independent
   `agent-fleet-main` and `legacy/com.termux` worktrees. Create the public
   `yaakovch/agent-fleet-termux-packages` fork at upstream commit
   `c7ca367ba4271dd58dee1bdc220899dda7dc4a71`, pin its builder image digest,
   and produce arm64/x86_64 custom-prefix bootstrap, packages, lock, SBOM,
   provenance, and immutable release checksums. Pin the CI actions by commit and
   leave remote APT sources disabled so official-prefix packages cannot enter
   the Fleet runtime.
2. Change the production application identity and all runtime path constants to
   `com.yaakovch.fleet` / `/data/data/com.yaakovch.fleet/files/usr`, remove the
   shared UID and exported generic Termux components, retain the internal PTY
   terminal, and eliminate local-shell/package entry points from Agent Fleet.
   Replace the launcher art with Android vectors derived from the Windows icon.
3. Add strict `agent-fleet-migration-v1` export/import with same-certificate
   protected activities, explicit confirmation, allowlisted preferences and
   files, hash/size/path checks, atomic file activation, verified registry
   handling, private-root/update-lane rewriting, and forward/reverse tests.
4. Build `0.118.4-agentfleet.41-legacy`/1043 on `com.termux` with an explicit
   label and badged icon. Keep the old top-level update lane for that app and
   publish the permanent-ID app only under `agent-fleet/fleet/latest`; verify
   the package ID from each signed APK rather than trusting filenames.
5. Run the complete JVM suite, lint, production builds, embedded-runtime and
   privacy verification, isolated API 36 arm64/x86_64 provisioning and
   coinstallation/migration tests, plus Native/Terminal/reconnect/resize/DeX
   checks. Sign with the existing external keystore, verify checksums and
   certificate continuity, retain `.40`, and publish both lanes without
   accessing the physical S23FE.
6. Hand off a manual S23FE checklist: install legacy `.41`, migrate forward,
   verify the permanent app, migrate back, and migrate forward once more. Then
   remove only the temporary bridge, install official Termux beside the permanent
   app, and verify Fleet and terminal workflows. The bridge and official Termux
   cannot coexist because both own `com.termux`; retain the bridge APK for
   rollback until both 90 days and two successful permanent-ID releases have
   elapsed.

Gate: no APK contains a bootstrap or package compiled for another application
prefix; both signed apps coexist; forward and reverse Fleet-only migration pass;
official Termux remains independent; the new release lane cannot serve a legacy
APK; and all emulator, build, runtime, privacy, and release checks pass.

## Milestone 27: Permanent-ID Clean-Launch Runtime Hotfix

1. Reproduce the `.41` arm64 first-launch failure from diagnostics and distinguish
   a missing executable from an absent `files/home` process working directory.
2. Create and validate the permanent app's private home immediately before any
   embedded-runtime subprocess, with a regression test starting from an absent
   nested home directory.
3. Run the focused test, complete JVM/lint suite, embedded-runtime verifier, and
   isolated API 36 emulator suites. Build and verify signed
   `0.118.4-agentfleet.42`/1044 without changing the runtime bundle or legacy
   bridge, publish it only to `agent-fleet/fleet/latest`, and retain `.41`,
   `.41-legacy`, and `.40` for rollback.
4. Hand off only the normal signed update flow for a manual S23FE smoke. Update
   `.41` in place, wait for terminal preparation, then verify Diagnostics,
   forward migration, Native, Terminal, and reconnect. Automated tools must not
   access the physical phone.

Gate: a clean permanent-ID install cannot fail because `files/home` is absent;
the embedded package/runtime inputs remain byte-for-byte pinned; `.42` replaces
`.41` in place with certificate continuity; and the permanent release lane never
serves the legacy bridge.
