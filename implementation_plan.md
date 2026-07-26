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

## Milestone 28: Canonical Android Root Migration Hotfix

1. Reproduce the post-migration Terminal failure with a Legacy registry path
   rooted at `/data/user/0/com.termux`, while confirming Native snapshots and
   the migrated registry remain healthy.
2. Rewrite both `/data/data` and `/data/user/0` package roots in allowlisted
   preferences, wtmux config, and SSH config. On permanent-app startup, repair
   the same stale allowlisted roots left by `.42` so existing migrations recover
   without clearing data or copying again.
3. Cover forward migration, automatic `.42` repair, idempotence, and both root
   aliases in JVM tests. Run lint, the complete JVM and isolated API 36 suites,
   signed migration-lane coinstallation, and release verification.
4. Build and publish permanent-only `0.118.4-agentfleet.43`/1045 while retaining
   `.42`, `.41`, `.41-legacy`, and `.40`. The manual phone smoke updates in
   place, launches once to perform root repair, and verifies Diagnostics plus
   Native and Terminal reconnect before Legacy is removed.

Gate: an already-migrated `.42` install automatically points wtmux and SSH at
the permanent private root; Terminal supplies a real host destination; Native
and Terminal both reconnect; and no phone data reset or second migration is
required.

## Milestone 29: Migrated Registry Load-Order Hotfix

1. Reproduce the `.43` state where Native reads the activated registry directly
   but Terminal sources a migrated config whose runtime registry declaration
   follows the managed loader, leaving `WTMUX_MACHINE_IDS` empty and invoking
   `ssh` without a destination.
2. During forward import, pin the config to the destination app's verified
   `registry/current/machines` path and move the runtime registry block before
   the managed loader. Apply the same bounded, marker-validated, idempotent
   normalization at startup so existing `.43` data repairs without recopying.
3. Cover realistic legacy ordering, forward import, exact `.43` startup repair,
   destination-path binding, and idempotence in JVM tests. Run lint, the full
   JVM and isolated API 36 suites, embedded-runtime verification, signed
   migration-lane coinstallation, and release verification.
4. Build and publish permanent-only `0.118.4-agentfleet.44`/1046 while retaining
   `.43`, `.42`, `.41`, `.41-legacy`, and `.40`. The manual S23FE smoke updates
   in place, launches once, closes any old completed Terminal tab, and reopens
   one existing session in both Native and Terminal without another migration.

Gate: startup places the verified registry declaration before its loader,
Terminal resolves a real host and reconnects, Native remains healthy, the
repair is idempotent, and no phone data reset or second migration is required.

## Milestone 30: Instant Local Terminal History

1. Add an alternate-screen scroll callback below the reusable Termux renderer
   and a shared compact/Desktop history controller. Fail closed for shells,
   normal buffers, and unsupported adapters; do not change remote PTY input.
2. After two quiet seconds, serialize bounded no-follow conversation requests,
   loading 100-item pages with automatic older paging up to 2,000 items. Keep
   every snapshot memory-only and require explicit Retry after failure.
3. Overlay a terminal-styled structured reader with History/Remote, Live,
   Updated, Copy, loading, empty, and error states. Keep keyboard input live,
   preserve the TerminalView instance, and restore terminal focus on exit.
4. Add pure controller tests and API 36 Compose coverage, then run the complete
   isolated fast/full emulator workflow, lint, minified production build,
   embedded-runtime/privacy checks, and signed-release verification.
5. Publish permanent-ID `0.118.4-agentfleet.45`/1047 only after Windows
   `0.11.0-beta.15`; retain `.44`/1046 and all legacy artifacts. The physical
   S23FE update and unstable-link Phone/DeX smoke remain manual through the
   in-app updater.

Gate: alternate-screen scrolling reads already fetched structured history
without sending remote mouse bytes; typing still reaches Live; paging is
bounded and memory-only; compact and Desktop behavior match; and no automated
tool accesses the physical phone.

Implementation record (2026-07-17): the final controller suite passed all 98
JVM tests; the isolated Pixel 7/API 36 full run passed 21 instrumentation tests
at `build/reports/agent-fleet/emulator/20260717T070616Z-full`; debug lint,
release lint, and embedded runtime `git-a6f3063` verification passed. Signed
permanent-ID `.45`/1047 passed APK identity, certificate, checksum, and embedded
runtime verification. It was published only to `fleet/latest`; HTTPS served
manifest SHA-256
`bf445272378045cc7aca85b583117666b0bac17f7601c622fb48facfd9fef01b`
and arm64 APK SHA-256
`25c18a8b1c5cf83d4e75a8668dbdf0c33a09f55e913f386b9ac02924b07004f0`
byte-for-byte. `.44`/1046 and all Legacy artifacts remain available. No
automated tool accessed the physical S23FE.

## Milestone 31: Terminal History Rollback Hotfix

1. Remove the `.45` full-screen History overlay, controller lifecycle, and
   alternate-screen scroll hook from compact and Desktop terminal renderers.
   Restore the exact `.44` live terminal and composer path without changing
   session attachments, Native protocol, migration state, or embedded runtime.
2. Run the complete JVM, lint, isolated API 36 fast/full, release, runtime, and
   privacy checks. Confirm the production resources contain no History overlay
   and the reusable terminal renderer receives scroll directly again.
3. Build and publish permanent-ID `0.118.4-agentfleet.46`/1048 through the
   existing signed updater, retaining `.45` and `.44`. The user manually updates
   the S23FE and verifies one existing Terminal session, image attachment,
   session switching, and reconnect; automated tools never access the phone.

Gate: `.46` renders the live terminal and existing attachment composer through
the proven pre-History path, contains no History/Remote UI, preserves all phone
data, and installs in place with certificate continuity.

Implementation record (2026-07-17): the rollback contract and all 94 JVM tests
passed. The isolated API 36 fast suite passed 15 tests at
`build/reports/agent-fleet/emulator/20260717T085824Z-fast`; the full suite passed
19 instrumentation tests at `20260717T090040Z-full`. Debug and Windows Release
lint passed, as did the minified signed build, certificate/runtime verification,
and checksum-identical local HTTPS payload check. Permanent-ID `.46`/1048 is
published to `fleet/latest`; the manifest SHA-256 is
`cfcf92f29ec847d3663c3c9dfd2d518c6b0c05ee09d207fac7a945b197493036`
and the arm64 APK SHA-256 is
`85f150eea381d4190e8aa80ebac4c7f39787e4eedaef752313d9097f5615135a`.
`.45`, `.44`, and all Legacy artifacts remain retained. Automated tooling did
not access the physical S23FE.

## Milestone 32: Permanent-ID Image Attachment Hotfix

1. Reproduce the permanent-ID picker and upload assumptions with MIME-less
   content, supported and unsupported image bytes, a full stderr pipe, plain
   `wtmux` output, and safe failure mapping.
2. Centralize bounded image import. Preserve validated PNG/JPEG/WebP bytes and
   normalize other Android-decodable phone formats to PNG/JPEG under the
   permanent app's private cache. Delete successful temporary copies and age
   out abandoned copies.
3. Remove the upload client's JSON-output dependency, drain stdout/stderr in
   parallel, keep the timeout, validate the returned `.wtmux/images/` path, and
   show actionable errors in compact Terminal and workspace Native flows.
4. Run all JVM tests, isolated API 36 instrumentation, lint, minified signed
   build, runtime/privacy verification, and updater payload verification.
5. Publish permanent-ID `0.118.4-agentfleet.47`/1049 to `fleet/latest` while
   retaining `.46`, `.45`, `.44`, and all Legacy artifacts. The user performs
   the physical-phone attachment smoke through the in-app updater; automated
   tooling never accesses the phone.

Gate: a MIME-less PNG imports under `/data/user/0/com.yaakovch.fleet`, Android
16 accepts the shared picker path, upload cannot deadlock on stderr, ordinary
`wtmux` output yields an attachment chip, and failure UI contains no private or
repository path.

Implementation record (2026-07-17): the complete JVM suite, debug lint, and
release lint passed. The isolated Pixel 7/API 36 full run passed 20
instrumentation tests (one intentionally skipped) at
`build/reports/agent-fleet/emulator/20260717T093531Z-full`, including the new
MIME-less permanent-ID import contract. The minified signed build and embedded
runtime/certificate verification passed. Permanent-ID `.47`/1049 is published
to `fleet/latest`; local and externally served bytes matched with manifest
SHA-256 `9e7a576f711a3fdabdba0d5f18bbfde0f4dba588724d96b7d7db81df5956ec9d`
and arm64 APK SHA-256
`c172fe8bdc70fd006f36f0366ce6df727732fcdd6b4a1c5445143f8f5562afcc`.
`.46`, `.45`, `.44`, and all Legacy artifacts remain retained. Automated
tooling did not access the physical S23FE.

## Milestone 33: Exit-127 Attachment Runtime Repair

1. Reproduce `.47` at the actual boundaries: send a fixture image to Gaming
   and Work-m with the desktop client, then install the packaged Termux and
   wtmux runtimes on the isolated API 36 emulator and execute the complete
   local image-send pipeline through deterministic fake SSH.
2. Preflight only commands required by that pipeline. Map each to its signed
   offline package, reinstall the minimum distinct package set when a preserved
   runtime is incomplete, and make normal Runtime inspection/repair use the
   same integrity signal.
3. Treat Android process-pipe closure as EOF, keep concurrent bounded draining,
   and distinguish a named missing local command from an unavailable remote
   `wtmux-host` helper without leaking paths or arguments.
4. Cover package selection, exit-127 messages, pipe closure, MIME-less import,
   packaged runtime installation, config loading, hashing, temp files, SSH
   stdin streaming, and returned image path. Run full JVM/API 36/lint/release
   verification.
5. Publish `0.118.4-agentfleet.48`/1050 to permanent `fleet/latest`, retain
   `.47` and all earlier artifacts, and leave the physical phone update plus
   real attachment smoke to the user.

Gate: the full packaged-runtime emulator upload succeeds, a missing local tool
selects its exact verified package for automatic repair, Android pipe closure
cannot crash the app, and any remaining exit 127 identifies the safe failing
component.

Implementation record (2026-07-17): direct desktop `wtmux image send` smokes to
Gaming and Work-m both returned a `.wtmux/images/` path. The isolated Pixel
7/API 36 full workflow passed with zero failures (one golden intentionally
skipped) at
`build/reports/agent-fleet/emulator/20260717T105055Z-full`; it installed the
packaged Termux bootstrap and wtmux runtime and completed the real image-send
pipeline through deterministic SSH. The complete JVM suite, debug/release
lint, minified production build, certificate, checksum, identity, and embedded
runtime verification passed. Permanent-ID `.48`/1050 is published to
`fleet/latest`; local and HTTPS-served bytes match with manifest SHA-256
`32ea99ffb0b1f7d3522b856c32cef33c65c7c6bd6bf4ed0a4480a76204b1eff9`
and arm64 APK SHA-256
`29546763b749d8e580ef43a66aafaaf815eeb74a521313760454ffb753155167`.
`.47` and all earlier artifacts remain retained. Automated tooling did not
access the physical S23FE.

## Milestone 34: Permanent-ID Tailscale Transport Hotfix

1. Change the packaged-runtime attachment fixture from explicit SSH transport
   to a Tailscale host and prove `.48` fails by invoking the absent `tailscale`
   CLI under the permanent app ID.
2. Mark all app-owned runtime children with the Termux environment identity so
   wtmux selects its supported OpenSSH-over-tailnet path. Preserve a genuine
   inherited Termux version and keep the exec compatibility layer unchanged.
3. Cover the environment contract in the JVM suite and execute the complete
   packaged Termux/wtmux image-send pipeline on API 36 through deterministic
   OpenSSH, with no fake or packaged Tailscale CLI.
4. Run the complete JVM/API 36/lint/release verification and publish signed
   permanent-ID `0.118.4-agentfleet.49`/1051 to `fleet/latest`, retaining `.48`
   and every earlier artifact. The physical-phone update and attachment smoke
   remain manual.

Gate: a permanent-ID background upload to a Tailscale-transport host completes
through bundled OpenSSH; Runtime repair never attempts to install Tailscale;
interactive terminal and app-owned processes share the same platform identity;
and no automated tool accesses the physical S23FE.

Implementation record (2026-07-17): the strengthened packaged-runtime test
reproduced `.48` verbatim with `Image upload needs the missing 'tailscale'
command`, then passed after app-owned children received the Termux environment
identity. The complete JVM suite and isolated Pixel 7/API 36 workflow passed
with zero failures (one golden intentionally skipped) at
`build/reports/agent-fleet/emulator/20260717T112827Z-full`; debug/release lint,
the minified signed build, certificate, identity, checksum, and embedded-runtime
verification also passed. Permanent-ID `.49`/1051 is published to
`fleet/latest`; local and HTTPS-served bytes match with manifest SHA-256
`0f6369161641435ab957a01cd31f8ef1349fa176bcc2e9316fd3e0becf628bdf`
and arm64 APK SHA-256
`e9131f21410cebe886eec7c7a965201e1e49eea4b44d34170f57dda4fdceae49`.
`.48` and all earlier artifacts remain retained. Automated tooling did not
access the physical S23FE.

## Milestone 35: Android 16 Diagnostics Accuracy Hotfix

1. Verify the first successful permanent-ID attachment from its host-side
   `.wtmux/images/` path and compare the screenshot with its copied diagnostics
   preview.
2. Refresh and retry a host doctor exactly once on `stale_revision`, retaining
   failure for persistent or non-stale errors. Cover the revision transition in
   a focused JVM test.
3. On Android 10 and newer, download repository files into bounded app-private
   staging and publish them through `MediaStore.Downloads`; open the resulting
   content URI directly. Make Diagnostics exercise that same provider with a
   create, fsync, publish, reopen, verify, and delete probe. Retain the legacy
   direct Downloads path only below Android 10.
4. Cover provider probing and publication on the isolated Android 16 emulator,
   then run the complete JVM/API 36/lint/release gate and publish signed
   permanent-ID `0.118.4-agentfleet.50`/1052 to `fleet/latest`, retaining `.49`
   and all earlier artifacts.

Gate: the attached screenshot is readable and matches the supplied report;
ordinary fleet revision movement does not make healthy hosts fail Diagnostics;
Downloads is tested and used through the Android 16-supported provider; no
temporary diagnostic item remains; and no automated tool accesses the S23FE.

Implementation record (2026-07-17): the host-side attachment was a readable
1080×2201 Diagnostics screenshot matching the copied 6/9 report. Focused API 36
tests published and reopened a real private-staged file through
`MediaStore.Downloads`, and the JVM regression refreshed a stale host-doctor
revision exactly once. The complete JVM suite and isolated Pixel 7/API 36 run
passed with zero failures (one golden intentionally skipped) at
`build/reports/agent-fleet/emulator/20260717T120003Z-full`; debug/release lint,
the minified signed build, certificate, identity, checksum, and embedded-runtime
verification passed. Permanent-ID `.50`/1052 is published to `fleet/latest`;
local and HTTPS-served bytes match with manifest SHA-256
`9ec85141458b82a6778493f00f8b6fdfa2267ab809e6a37ecf0ade990fa0ae1b`
and arm64 APK SHA-256
`cb7e4b77eb067ad34784bec2d0c197f8d9c7abdda46599ddb9bbe65cd836102a`.
`.49` and all earlier artifacts remain retained. Automated tooling did not
access the physical S23FE.

## Milestone 36: Isolated Terminal History Restoration

1. Reproduce `.45` from the retained APK and supplied screenshots. Separate
   its missing-Tailscale environment failure from its blank-Live overlay
   lifecycle, and classify the older migration warnings and Native disconnect
   against the current `.50` fixes.
2. Restore the alternate-screen scroll callback and a bounded, process-memory
   history controller. Reuse `.49`'s Termux/OpenSSH process environment, redact
   runtime paths, serialize 100-item no-follow pages, cap at 2,000 items, and
   require explicit Retry after failure.
3. Keep the compact full-screen reader physically `GONE` while Live is active;
   use only a bounded Desktop control until History opens. Preserve the same
   service PTY and TerminalView, return Remote gestures unchanged, and refit
   the live renderer after Native/composer/toolbar geometry changes.
4. Cover capture policy, chronological paging, memory bounds, overlay
   visibility, Live return, Remote pass-through, and delayed initial scroll in
   JVM and Compose tests. Extend the packaged Tailscale-host acceptance test to
   execute a real `wtmux conversation stream --no-follow` through OpenSSH.
5. Run the complete JVM, lint, isolated API 36 full, minified production build,
   embedded-runtime/privacy, certificate, and release verification gates for
   permanent-ID `0.118.4-agentfleet.51`/1053. Retain `.50` and every earlier
   artifact; publishing and the physical S23FE update remain separate manual
   rollout steps.

Gate: Live contains no mounted history reader, History and Remote can be
entered and exited without replacing the PTY, packaged no-follow history works
for a Tailscale host without a Tailscale binary, prompt geometry refits after
chrome changes, and no automated tool accesses the physical S23FE.

Implementation record (2026-07-17): retained `.45` APK inspection reproduced
the missing-Tailscale history transport and confirmed that its full-screen
Compose reader remained mounted over Live. The restored controller now keeps
that reader `GONE` in Live, pages 100 structured items at a time through the
bundled OpenSSH path, caps process memory at 2,000 items, redacts private paths,
requires explicit retry after errors, preserves the service PTY, and refits the
terminal after chrome changes. The complete JVM suite and isolated Pixel 7/API
36 run passed with zero failures (one review-only golden intentionally skipped)
at `build/reports/agent-fleet/emulator/20260717T131009Z-full`; debug/release lint
passed with zero errors. The minified signed `.51`/1053 arm64 and universal APKs,
certificate, identity, checksums, and 84-package embedded runtime all verified.
The local manifest SHA-256 is
`9b0aacb875772d194aeeee7b0e63f07d258a4d21a8077d261f74b4e356d2e05d`
and the arm64 APK SHA-256 is
`3043d19950141b505fffddf538603058b937870c63a53c42c28b373195d49df9`.
Permanent-ID `.51`/1053 is published to `fleet/latest`; local and HTTPS-served
bytes match the recorded manifest and arm64 APK SHA-256 values. `.50` and all
earlier artifacts remain retained. Automated tooling did not access the
physical S23FE.

## Milestone 37: Seamless Pane Scrollback And Attachment Retry

1. Retire `.51`'s structured History overlay and controls from compact and
   Desktop layouts. Preserve the same live PTY, composer, terminal renderer,
   focus, Native switch, and workspace attachment lifecycle.
2. Add a bounded `pane.scrollback` JSON contract to wtmux. Capture the current
   tmux pane with ANSI attributes, dimensions, history count, truncation flag,
   and SHA-256 revision; cap requests at 5,000 rows and responses at 4 MiB.
3. Embed wtmux `git-9527c82` in `.52`. Prefetch 2,000 rows after 900 quiet
   milliseconds, integrity-check the frame, and install it into a read-only
   `TerminalEmulator` that is drawn by the live `TerminalView`. Consume upward
   scroll locally only when the live buffer is alternate and dimensions match;
   reaching bottom or typing restores Live.
4. Retry one transient image upload after verified runtime repair. Cover empty
   exit 127, SSH 255, Broken pipe, reset, timeout, refusal, and unreachable
   failures; keep permanent errors single-attempt and messages path-redacted.
5. Gate `.52`/1054 on wtmux smoke, pane parser and renderer lifecycle tests,
   packaged-runtime OpenSSH pane/image instrumentation with a first-attempt
   Broken pipe, complete JVM/API 36 suites, debug/release lint, minified signing,
   embedded-runtime verification, and Windows lint/tests for shared protocol
   compatibility. Do not use ADB against the physical phone.
6. Prepare checksum-verified arm64 and universal artifacts while retaining
   `.51` and earlier releases. Publish to `fleet/latest` only after the verified
   artifact and remaining manual phone smoke are explicitly approved.

Gate: terminal scrolling has no History UI and renders cached tmux ANSI in the
terminal itself; the live xterm/PTY identity is stable; reaching bottom is
seamless; a deterministic first Broken pipe upload succeeds on its sole retry;
and an unrecovered 127/255 names the actionable component without private data.

Implementation record (2026-07-17): `.52` removes the structured reader and
uses a memory-only secondary terminal emulator for integrity-checked tmux ANSI;
typing or reaching the bottom restores the unchanged live PTY. Image upload
repairs verified local packages before transfer and retries one simulated SSH
255/Broken pipe before succeeding. wtmux smoke, all 74 Bats tests, all 149
Python tests, Windows TypeScript/124-test/production-build compatibility, all
107 Android JVM tests, release lint, and the isolated Pixel 7/API 36 suite
passed. The final clean API 36 report is
`build/reports/agent-fleet/emulator/20260717T152500Z-full` (23 instrumentation
tests, zero failures/errors, one review-only golden skipped). Signed arm64 and
universal `.52`/1054 APKs, certificate, identity, checksums, and the 84-package
embedded runtime at wtmux `git-9527c82` verified. The arm64 APK SHA-256 is
`f7ad1668ac5e9a824a0a40cdfc69149765124c3b50540e47c6f24fd7498bcea4`;
the local manifest SHA-256 is
`6f1a912551928f1a25074fae112c902c6bc498740af2ef3e1012ce1f55381c3c`.
`.51` and earlier artifacts remain retained. Following explicit rollout
approval, `.52` was published to `fleet/latest`; the HTTPS-served manifest and
arm64 APK matched the verified local SHA-256 values above. The standing release
policy now publishes future fully verified permanent-ID Android versions to
the in-app updater by default unless the user requests a hold. Automated
tooling did not access the physical S23FE.

## Milestone 38: Silent Client-Only Terminal Attachment

1. Reproduce the first-attach warning with the permanent app's intentionally
   client-only registry and trace it to wtmux local-machine registration rather
   than the PTY, terminal renderer, SSH transport, or tmux host.
2. Make noninteractive wtmux startup continue silently when the current client
   is not registered. Preserve the interactive registration choice and every
   genuine remote error.
3. Add shared smoke coverage for a non-local Termux client and packaged API 36
   coverage that installs the exact embedded runtime, resolves a session, and
   reaches fake OpenSSH attach with no warning in the startup stream.
4. Embed wtmux `git-bdc19c0`, bump permanent Android to `.53`/1055, and gate it
   on complete wtmux, Android JVM, API 36, release-lint, minified signing,
   identity, certificate, embedded-runtime, and checksum verification.
5. Publish the verified artifact to `fleet/latest`, retain `.52` for rollback,
   and compare the HTTPS-served manifest and arm64 APK bytes with the local
   verified release. Do not access the physical S23FE through ADB.

Gate: a new client-only Terminal attachment reaches the selected remote tmux
session without rendering a local registration warning; interactive setup is
unchanged; all release gates pass; and updater bytes are exact.

Implementation record (2026-07-17): wtmux smoke, all 149 Python tests and four
subtests, and all 74 Bats tests passed for commit `bdc19c0`. Android embedded
runtime verification passed for exact `git-bdc19c0`; all 107 JVM tests and the
complete managed Pixel 7/API 36 run passed with zero failures (one review-only
golden skipped) at
`build/reports/agent-fleet/emulator/20260717T160845Z-full`. Release lint passed
with zero errors. Signed arm64 and universal `.53`/1055 APKs, certificate,
identity, checksums, and all 84 embedded packages verified. `.53` is published
to `fleet/latest`; the local and HTTPS-served manifest SHA-256 is
`250c6f18ab615cfd50229f4d5c8715ec205658674d8d48d5b71fd0ac8ed743e2`
and the arm64 APK SHA-256 is
`7714e1de078412c3aa09ad1f9c3baa7f1c71ca906c884c82cbcaaabecd3137e5`.
`.52` remains available for rollback. Automated tooling did not access the
physical S23FE.

## 39. Local Reply Suggestions

1. Add pure eligibility, context bounding, prompt/output parsing, and stale
   result contracts with JVM tests shared by composer and text-answer surfaces.
2. Add release-pinned model metadata, resumable verified download and import,
   model preferences, metered-network confirmation, cancellation/removal, and
   storage/error UI under More.
3. Add a non-exported `:local_llm` bound service using LiteRT-LM Gemma 4 E2B,
   GPU then CPU fallback, one active request, explicit cancellation/close, and
   60-second/background/disable process termination.
4. Integrate Suggest/results into Native composer and free-text questions while
   preserving drafts, focus, scrolling, tools, approvals, choices, terminal
   behavior, and controller lifecycle. Exercise the Binder boundary with a fake
   engine and no model download.
5. Bump permanent Android to `.54`/1056 if still available; run all JVM tests,
   the full isolated API 36 workflow, lint, signed/minified release, identity,
   certificate, runtime, and checksum verification. Publish through
   `fleet/latest`, retain `.53`, and leave physical S23FE performance/quality
   smoke to the user.

Implementation record (2026-07-17): the pure suggestion contracts, verified
model storage, non-exported Binder service, and Native composer/question UI are
implemented with the feature disabled by default. All 112 JVM tests and the
complete managed Pixel 7/API 36 run passed with zero failures at
`build/reports/agent-fleet/emulator/20260717T185147Z-full`; all 26
instrumentation tests ran, with the one review-only golden intentionally
skipped. Release lint passed with zero errors. The minified signed arm64 and
universal `.54`/1056 APKs, certificate, permanent app identity, checksums, and
all 84 embedded runtime packages verified. `.54` is published to
`fleet/latest`; the local and HTTPS-served manifest SHA-256 is
`c4fd2f2bc1307bc7414a81885ea9bd19cc4b38df12d58ada942a3a7402b73d1f`
and the arm64 APK SHA-256 is
`d7419ca0a257b2a0732304a3f2a7818009b90c9349b3a788ed223589a8f17bc4`.
`.53` remains available for rollback. The physical S23FE model download,
latency, thermal, memory-reclamation, and suggestion-quality smoke remains
manual; automated tooling did not access the phone.

## 40. High-Density Android Session UI

1. Add an observable, bounded density preference store for Native body text,
   session-drawer title text, and Terminal shortcut-row height; expose all three
   in Agent Fleet appearance with immediate preview and same-signed migration.
2. Implement the approved Reading-first Native layout: flat assistant turns,
   compact interactive frames/header/feed, and a one-row composer that preserves
   Insert, Send, Attach, Suggest, Ctrl+C, Shift+Tab, questions, and approvals.
3. Make the session drawer a full-height overlay, dismiss the IME on open, use
   46dp visual rows with 48dp tap regions, and move Agent Fleet, Keyboard, and
   Appearance into a compact fixed footer.
4. Replace the default two-row extra keys with one horizontally scrollable
   compact pill row, flattening custom matrices without changing dispatch,
   modifier, repeat, haptic, or popup behavior.
5. Add store/migration/layout/key unit tests, Compose interaction coverage, and
   reviewed 393×852 golden candidates. Run focused tests, fast/full API 36,
   lint, minified signing, identity/certificate/runtime/checksum verification,
   then publish `.55`/1057 through `fleet/latest` while retaining `.54`.

Gate: the standard Native fixture exposes 30–40% more content, at least nine
drawer sessions fit at the default density, the drawer covers bottom input
chrome, the one-row Terminal strip preserves every configured key, minimum and
maximum settings do not clip essential actions, and automated tooling never
accesses the physical S23FE.

Implementation record (2026-07-18): the Reading-first Native transcript,
full-height 46dp-row drawer, compact composer/actions, scrollable single-row
Terminal keys, and three independently persisted density controls are
implemented. Reviewed 393×852 candidates show all 11 fixture sessions with the
search field and fixed footer visible. The focused store, migration, layout,
and row-major key tests passed; the final fast emulator run passed at
`build/reports/agent-fleet/emulator/20260718T030735Z-fast`. All 116 JVM tests
and all 29 managed Pixel 7/API 36 tests passed at
`build/reports/agent-fleet/emulator/20260718T031144Z-full`; release lint passed
with zero errors. The minified signed arm64 and universal `.55`/1057 APKs,
certificate, identity, checksums, and all 84 embedded runtime packages
verified. `.55` is published to `fleet/latest`; the HTTPS-served manifest
SHA-256 is `a144383c8d94ccce2b8a87385fd8597733fbdf967204adb27506b62cdd36efe0`
and the arm64 APK SHA-256 is
`cc8ec63f055cd3cca175128dd0159c89edc42724dc9f90a6d2635853d9d0c033`.
`.54` remains available for rollback. Physical S23FE density, IME, and
Terminal-strip interaction smoke remains manual; automated tooling did not
access the phone.

## 41. Phone Native Suggestions and Composer Refit

1. Pass the active phone Native conversation state into the standalone composer
   and reuse the existing local suggestion client, eligibility rules, bounded
   prompt builder, results UI, and cancellation lifecycle.
2. Keep suggestions hidden in Terminal, while a draft exists, and outside a
   completed assistant turn. Selecting a result fills but never submits.
3. Replace the narrow one-line phone editor and redundant overflow menu with a
   four-to-seven-line field beside a vertical Attach, Insert, and Send stack;
   retain Ctrl+C and Shift+Tab in the Native header Actions menu.
4. Add a deterministic API 36 regression that invokes the debug fake model
   through the Binder service, chooses a suggestion, verifies no automatic
   submission, and asserts the action stack geometry.
5. Run the full JVM/API 36/lint/release verification and publish permanent-ID
   `.56`/1058 through `fleet/latest`, retaining `.55` for rollback.

Implementation record (2026-07-18): phone-fullscreen Native now receives the
active conversation state and exposes the same bounded, on-demand local
suggestions as desktop panes. The redundant composer overflow was removed and
the four-to-seven-line editor now sits beside the vertical Attach, Insert, and
Send stack. The fast emulator run passed at
`build/reports/agent-fleet/emulator/20260718T175541Z-fast`; all 116 JVM tests
passed and the final managed Pixel 7/API 36 run completed 30 scenarios with
zero failures at
`build/reports/agent-fleet/emulator/20260718T181042Z-full`; two review-only
golden candidates were skipped. Release lint passed with zero errors.
The signed/minified arm64 and universal APKs, certificate, permanent identity,
checksums, and all 84 embedded packages verified. `.56` is published through
`fleet/latest`, while `.55` remains available for rollback. The HTTPS-served
manifest SHA-256 is
`3a02237d71247127eeb94eb418058e8c178835d70173cb2c8d62fdb64ee6f6d6`
and the arm64 APK SHA-256 is
`453753d2acd7611561ac0af2c2fe3f49d5411e07f0135eac89db8936ed6a5185`.
Automated tooling did not access the physical S23FE.

## 42. Direct-Reply Suggestion Prompt

1. Put the bounded transcript inside an explicit quoted context block and end
   the Gemma prompt with a direct-reply task. Require verbatim human-user
   messages, prohibit explanation/paraphrase, match recent user language, and
   return fewer options instead of padding.
2. Dynamically trim the oldest context after reserving the structured target
   and task so the complete prompt cannot exceed the service's 16-KiB boundary.
3. Add the reported `It means...` regression and maximum-size question coverage,
   then run focused JVM tests, the full isolated API 36 suite, lint, signed
   release/identity/runtime/checksum gates, and publish `.57`/1059 through
   `fleet/latest` while retaining `.56` for rollback.

Implementation record (2026-07-18): commit `e22bed34` adds the task-last prompt,
quoted transcript boundary, and total byte budget. All 118 JVM tests and all 30
managed API 36 scenarios passed at
`build/reports/agent-fleet/emulator/20260718T192426Z-full`; release lint passed
with zero errors. Signed/minified arm64 and universal `.57`/1059 APKs passed
certificate, permanent identity, checksum, and 84-package embedded-runtime
verification. `.57` is published through `fleet/latest`; the HTTPS-served
manifest SHA-256 is
`621d5d4430be9fcab46a527dfb58c15f0d864d45d3831ba6ebea6e4b59deab0a`
and arm64 APK SHA-256 is
`bf7892cafd98b4f03204b627e99771dbcb801c74858eaa980c4ac3f05f189d05`.
`.56` remains available for rollback. The exact Gemma regression and 30-prompt
quality benchmark remain a manual S23FE acceptance step; automated tooling did
not access the phone.

## 43. Foreground Terminal Attachment Recovery

1. Reproduce the reported return-from-another-app path as a managed attachment
   that exits while `TermuxActivity` is stopped but leaves its renderer visible.
2. Add a foreground-only coordinator that reselects a live attachment or starts
   exactly one replacement from the remembered descriptor, then refits and
   redraws the existing terminal view. Do not create a classic shell during
   managed process restoration or resurrect an explicitly closed local tab.
3. Cover live selection, dead replacement, duplicate foreground callbacks,
   background cancellation, missing descriptors, and the real terminal-service
   replacement handoff. Run focused JVM tests and the isolated API 36 fast/full
   suites, lint, signed release/identity/runtime/checksum gates, then publish
   `.58`/1060 through `fleet/latest` while retaining `.57` for rollback.

Implementation record (2026-07-18): source commit `64005c28` adds bounded,
foreground-only managed attachment recovery and prevents managed process
restoration from creating a classic shell. The focused resume/store suite and
the API 36 fast run passed; all 122 JVM tests and 31 managed Pixel 7/API 36
scenarios passed at
`build/reports/agent-fleet/emulator/20260718T202212Z-full`, with two review-only
goldens skipped. Release lint completed with zero errors, and embedded runtime
`git-bdc19c0` verified all 84 packages. Signed/minified arm64 and universal
`.58`/1060 APKs passed certificate, permanent identity, checksum, and runtime
verification. `.58` is published through `fleet/latest`; the HTTPS-served
manifest SHA-256 is
`c92a0cc18459d4c66200ba314374a853c083dab9a254ad9f2cd6042c0a257b21`
and arm64 APK SHA-256 is
`8777aa1c455e5a954ea888807d89578992b71a87e7d2124ec20f27c8eae5c9ce`.
`.57` remains available for rollback. Automated tooling did not access the
physical S23FE.

## 44. Terminal Chrome, Visible Reconnect, And Native Activity Hotfix

1. Give the fullscreen Terminal model/session chrome one fixed 48dp host and
   content height, and derive Terminal padding from the same resource instead
   of a Compose view's transient measured height.
2. Extend managed attachment recovery to unexpected nonzero exits while the
   session remains visible. Use bounded 1/2/5/10/30-second retries, reset the
   budget after 30 seconds of stability, and suppress recovery after explicit
   close, normal exit, backgrounding, or target changes.
3. Put App updates first in More and render provider work activity in Native's
   top row as `Working (elapsed)`. Prefer Codex/Copilot lifecycle events and
   infer Claude activity from its user, tool, reply, question, and completion
   sequence without changing the shared conversation protocol.
4. Cover chrome height, visible/background reconnect decisions, menu order,
   timestamp parsing, provider activity, and elapsed formatting. Run focused
   tests, fast/full isolated API 36 suites, release lint, signing, embedded
   runtime/identity/checksum verification, and publish `.60`/1062 while
   retaining `.59` for rollback.

Implementation record (2026-07-19): source commit `5ac82644` fixes the
full-height Terminal chrome regression, adds bounded visible recovery for SSH
exit 255 and other nonzero transport failures, moves App updates first, and
shows live Native work duration for Codex, Claude Code, and Copilot. The
focused 27-test run and all 25 fast emulator scenarios passed. All 128 JVM
tests and the complete managed Pixel 7/API 36 suite passed at
`build/reports/agent-fleet/emulator/20260719T001055Z-full`; two review-only
golden generators were skipped. Windows-hosted release lint passed with zero
errors, and embedded runtime `git-c8772e3` verified all 84 packages. Signed,
minified arm64 and universal `.60`/1062 APKs passed certificate, permanent
identity, checksum, and runtime verification. `.60` is published through
`fleet/latest`; the HTTPS-served manifest SHA-256 is
`468e80651a6105a963dc1d3cac9ebe0c7ba176075572a30510b9c23c9942677f`
and arm64 APK SHA-256 is
`b6864376bd26676cb00e6df352068345ea61c8619b737e6cabd603036ebdb2b9`.
`.59` remains available for rollback. Automated tooling did not access the
physical S23FE.

## 45. Model Catalog Refresh Race Hotfix

1. Replace the single model-request busy flag with a ticketed request gate so
   lifecycle resets cannot let an old completion release a newer request.
2. When the user opens or retries the model picker during a background
   no-catalog poll, retain one explicit catalog request and run it immediately
   after the active request completes. Show loading while it is queued instead
   of the misleading unavailable state.
3. Cover explicit-over-background priority, repeated background polls, and
   lifecycle reset/late-completion behavior with deterministic JVM tests. Run
   focused, fast, and full isolated API 36 suites plus lint and signed-release
   verification.
4. Publish permanent-ID `.61`/1063 through `fleet/latest`, retain `.60` for
   rollback, and leave the physical-phone update and picker smoke to the user.

Implementation record (2026-07-19): source commit `eac471e3` queues an explicit
catalog load behind any active background model-state poll and uses generation-
safe request tickets across rebinding and lifecycle shutdown. The focused gate
and identity tests passed, as did all 25 fast emulator scenarios. All 131 JVM
tests and the complete managed Pixel 7/API 36 suite passed at
`build/reports/agent-fleet/emulator/20260719T010633Z-full`; 37 instrumentation
cases completed with two review-only golden generators skipped. Release lint
completed with zero errors, and the signed/minified arm64 and universal APKs
passed certificate, permanent identity, checksum, and embedded runtime
`git-c8772e3` verification across all 84 packages. `.61`/1063 is published
through `fleet/latest`; the HTTPS-served manifest SHA-256 is
`3a7f0b741c245ec1d03c3b7e50b44bff84b0d49f5da07ead982535dfb93db7f0`
and arm64 APK SHA-256 is
`391d4aa86a705305d19d504d7582fa5c25f1fd4811127b58bad0b078a814dd2c`.
`.60` remains available for rollback. Automated tooling did not access the
physical S23FE.

## 46. Native Completed Work Duration Hotfix

1. Preserve the existing live `Working (elapsed)` indicator, then derive the
   latest completed turn duration from the bounded Native conversation
   lifecycle without adding a protocol field.
2. Show `Worked for …` in both Native status state and the existing final
   status card, replacing the low-information `Done` label without consuming
   additional conversation space.
3. Cover exact elapsed calculation and visible Compose rendering, then run the
   focused, fast, full API 36, release-lint, signing, identity, embedded-runtime,
   and checksum gates.
4. Publish permanent-ID `.62`/1064 through `fleet/latest`, retain `.61` for
   rollback, and leave the physical-phone update and Native smoke to the user.

Implementation record (2026-07-19): source commit `e8df1ebd` derives completed
work duration from the user-turn start and provider completion event, keeps the
live timer unchanged, and renders the result in the visible final status card.
The focused JVM regression and all 26 fast emulator scenarios passed. All 131
JVM tests and the complete managed Pixel 7/API 36 suite passed at
`build/reports/agent-fleet/emulator/20260719T040148Z-full`; 38 instrumentation
cases completed with two review-only golden generators skipped. Release lint
completed with zero errors, and the signed/minified arm64 and universal APKs
passed certificate, permanent identity, checksum, and embedded runtime
`git-c8772e3` verification across all 84 packages. `.62`/1064 is published
through `fleet/latest`; the HTTPS-served manifest SHA-256 is
`bbd41f41037158ad4704d251ea32c546ae748d9d0c8dda57fa57f725b111ec61`
and arm64 APK SHA-256 is
`b626528fc01705acccc92123a250fff444f530a653c02b46255f6298e1e567ca`.
`.61` remains available for rollback. Automated tooling did not access the
physical S23FE.

## 47. Exact Codex Completed Work Duration Hotfix

1. Read Codex's authoritative `duration_ms` and `completed_at` values from its
   completion event in wtmux and carry them through the existing optional
   conversation-item start/completion timestamps; do not scrape or transport
   terminal output.
2. Prefer that provider interval for Native's completed `Worked for …` value,
   retaining lifecycle inference as a compatibility fallback for older hosts
   and other providers.
3. Give Windows the same exact completed-duration presentation and cover the
   shared normalizer plus both clients with exact millisecond-to-display
   regressions.
4. Run the full isolated API 36 and release gates, publish permanent-ID
   `.63`/1065 through `fleet/latest`, retain `.62` for rollback, and leave the
   physical-phone update and Native smoke to the user.

Implementation record (2026-07-19): wtmux commit `20f1bae` maps Codex's
authoritative completion duration onto existing conversation timestamps, and
Android source commit `bf860719` prefers that exact interval while preserving
the prior inferred fallback. Windows commit `be0913f` adds the same presentation.
The shared 37-test conversation suite, all 165 root Python tests plus four
subtests, all 74 Bats tests, root smoke, Windows lint, all 140 Windows tests,
and the Windows production build passed. All 131 Android JVM tests and the
complete managed Pixel 7/API 36 suite passed at
`build/reports/agent-fleet/emulator/20260719T044321Z-full`; 38 instrumentation
cases completed with two review-only golden generators skipped. Release lint
completed with zero errors, and the signed/minified arm64 and universal APKs
passed certificate, permanent identity, checksum, and embedded runtime
`git-c8772e3` verification across all 84 packages. `.63`/1065 is published
through `fleet/latest`; the HTTPS-served manifest SHA-256 is
`a7010e4f74c02e41935f45e2b14271d69e5241610ed909414165bf65d22c0e03`,
the arm64 APK SHA-256 is
`4bb5307c4995ed6cfbea897dcb611cbfde96d694b053d14827f8cc0b94f28814`,
and the universal APK SHA-256 is
`5981fec815751fd9873f7bf2473829e83352c4dcdd6217d252b4f2e9bdeaec5b`.
`.62` remains available for rollback. Automated tooling did not access the
physical S23FE.

## 48. Live Native Working Visibility Hotfix

1. Preserve the active provider lifecycle marker in every newest conversation
   snapshot even when recent tool output consumes the bounded page budget.
   Keep history cursors contiguous and leave older-page behavior unchanged.
2. Start an Android-local optimistic work timer immediately after a non-empty
   Native message is successfully submitted. Replace it with the provider's
   authoritative `Working` event, and clear it on completion, error, question,
   or approval lifecycle boundaries.
3. Cover page-budget eviction and completion on the host, optimistic send/event
   reconciliation in JVM tests, and visible `Working (…)` rendering from the
   optimistic state in the API 36 Compose suite.
4. Publish permanent-ID `.64`/1066 through `fleet/latest`, retain `.63` for
   rollback, and leave the physical-phone update and live Native smoke to the
   user.

Implementation record (2026-07-19): wtmux commit `a68611f` retains an active
provider lifecycle marker outside the bounded visible page, and Android source
commit `5fcd1882` starts the live timer as soon as the Native composer accepts a
message while reconciling it with authoritative stream state. The real current
Codex transcript reproduced the page-budget eviction and confirmed the repaired
snapshot ends with `Working`. The shared 38-test conversation suite, all 166
root Python tests plus four subtests, all 74 Bats tests, root smoke, Windows
lint, all 140 Windows tests, and the Windows production build passed. All 131
Android JVM tests, all fast emulator scenarios at
`build/reports/agent-fleet/emulator/20260719T051414Z-fast`, and the complete
managed Pixel 7/API 36 suite at
`build/reports/agent-fleet/emulator/20260719T052120Z-full` passed; 38
instrumentation cases completed with two review-only golden generators skipped.
Release lint completed with zero errors, and the signed/minified arm64 and
universal APKs passed certificate, permanent identity, checksum, and embedded
runtime `git-c8772e3` verification across all 84 packages. `.64`/1066 is
published through `fleet/latest`; the HTTPS-served manifest SHA-256 is
`67b246acbbc5162796909c5522c589f57734ea3daf937a004bb592aa9d23d3a6`,
the arm64 APK SHA-256 is
`fa6bf1b717fbc078ed3fc1b614ba52f9dc43d92a1b25c81c77c107ce825deefd`,
and the universal APK SHA-256 is
`fe8a49ff854bf3cc6dc8c58805ced1e77ee415ce4df330ae11b1daaab95df554`.
`.63` remains available for rollback. Automated tooling did not access the
physical S23FE.

## 49. Managed Session Resume Presentation Hotfix

1. Reapply the managed Native/Terminal presentation after Android restores the
   activity view hierarchy so a resumed Terminal cannot retain the layout's
   default hidden composer and session chrome.
2. Persist the active fullscreen Fleet session independently of the local PTY.
   If the activity target is lost while an exited attachment remains selected,
   reconstruct the managed target, surface, composer policy, and bounded
   reconnect instead of leaving a detached exit-255 screen.
3. Preserve an existing managed target when Android delivers a bare main intent,
   and translate checked runtime-start failures into visible recovery errors
   rather than letting them escape on the activity main thread.
4. Cover presentation reapplication, target persistence/recovery, service PTY
   identity, and checked reconnect failures. Run focused JVM tests, a direct
   activity lifecycle reproduction, the complete isolated API 36 suite, release
   lint, signing, identity/runtime/checksum verification, and publish `.65`/1067
   while retaining `.64` for rollback.

Implementation record (2026-07-19): Android source commit `d4948bdc` restores
the managed UI after framework state restoration, remembers the active
fullscreen session separately from its SSH process, and safely reports runtime
restart failures. This is Android activity/process lifecycle behavior only; it
does not alter the shared protocol or require a Windows change. The focused JVM
regressions and direct API 36 lifecycle reproduction passed. All 134 Android JVM
tests and the complete managed Pixel 7/API 36 suite passed at
`build/reports/agent-fleet/emulator/20260719T060346Z-full`; all 37
instrumentation cases completed with two review-only golden generators skipped,
including the new composer/chrome resume regression. Release lint completed
with zero errors, and the signed/minified arm64 and universal APKs passed
certificate, permanent identity, checksum, and embedded runtime `git-c8772e3`
verification across all 84 packages. `.65`/1067 is published through
`fleet/latest`; the HTTPS-served manifest SHA-256 is
`de9bfa9ff7efd0c93d75a55f21fe57fa3b3fade5b3369184265c3840683f808f`,
the arm64 APK SHA-256 is
`abf977141d45dfaef45f44848c81a8d59bbd2a6cbce96639da6bb9c590d1739a`,
and the universal APK SHA-256 is
`b47c4588c27eb38bdfed87ec0b576e16a8e7c928246f61e1f93ca27346dc145a`.
`.64` remains available for rollback. Automated tooling did not access the
physical S23FE.

## 50. Android Two-Row Session Header

1. Replace the compressed phone session toolbar in both Native and Terminal
   with one shared, fixed 88dp header: a 40dp identity row for the session
   title, live status, and Actions, followed by a 48dp control row for the
   flexible model/effort picker and destination-only Native/Terminal button.
2. Keep the header independent of the configurable Native body font size while
   retaining Android system font scaling. Preserve a stable two-row height for
   non-AI sessions, the existing Actions behavior, and all composer, desktop,
   DeX, protocol, and persisted-schema behavior.
3. Cover both surfaces, row geometry, long session/model labels at the maximum
   Native body density, Actions dispatch, the shared resource contract, and
   reviewed Native screenshot goldens.
4. Run focused tests, fast/full isolated API 36 suites, release lint, signing,
   identity/runtime/checksum verification, and publish `.66`/1068 through
   `fleet/latest` while retaining `.65` for rollback.

Implementation record (2026-07-19): Android source commit `3c75d137` adds the
shared two-row header to fullscreen Native and Terminal, gives the model/effort
control the available row width, and exposes the same Actions menu and live
status on both surfaces. The focused compile/JVM gate and all fast emulator
scenarios passed. All 134 Android JVM tests and the complete managed Pixel
7/API 36 suite passed at
`build/reports/agent-fleet/emulator/20260719T070335Z-full`; 38 instrumentation
cases completed with two review-only golden generators skipped. The two
intentional Native golden changes were visually reviewed before adoption, and
the screenshot threshold remains unchanged. Release lint completed with zero
errors after Gradle regenerated stale cross-host resource-merge metadata. The
signed/minified arm64 and universal APKs passed certificate, permanent
identity, checksum, and embedded runtime `git-c8772e3` verification across all
84 packages. `.66`/1068 is published through `fleet/latest`; the HTTPS-served
manifest SHA-256 is
`bf10a55c3a52130e095c0a91f4d39ad54fa77aeac5a0a5083bd591461e36e250`,
the arm64 APK SHA-256 is
`8890a0b7f2214cc6f599b4f825ab0b024740d170de041682323bc95d03aad39b`,
and the universal APK SHA-256 is
`495106f8dab0aea8cb3dfe8fdfc3c58a0d67356531b0566eae9ce381b2285e48`.
`.65` remains available for rollback. Automated tooling did not access the
physical S23FE.

## 51. Terminal Header Inset And Direct-Entry Resume Hotfix

1. Reserve the two-row Terminal header with a real top layout margin instead
   of `TerminalView` padding, because the custom terminal renderer draws from
   the canvas origin and does not offset terminal rows for view padding.
2. Reconnect an unexpectedly ended managed attachment by matching the failed
   attachment to the activity's intended workspace-session target, even when
   direct app entry races before that attachment becomes the selected terminal.
3. Cover the exact 88dp Terminal margin before and after activity recreation,
   plus visible, background, different-target, normal-exit, and exit-255
   reconnect decisions.
4. Run focused, fast/full API 36, release-lint, signing, identity/runtime, and
   served-checksum gates; publish `.67`/1069 through `fleet/latest` while
   retaining `.66` for rollback.

Implementation record (2026-07-19): Android source commit `e91a15f8` replaces
the ineffective Terminal padding with a measured top margin and accepts the
intended managed target's failure callback during direct entry. The focused
lifecycle tests and Android-test compilation passed, as did all fast emulator
scenarios at
`build/reports/agent-fleet/emulator/20260719T074541Z-fast`. All 135 Android JVM
tests and the complete managed Pixel 7/API 36 suite passed at
`build/reports/agent-fleet/emulator/20260719T074854Z-full`; 38 instrumentation
cases completed with two review-only golden generators skipped, including the
activity recreation test's exact header-margin assertion. Release lint
completed with zero errors. The signed/minified arm64 and universal APKs passed
certificate, permanent identity, checksum, and embedded runtime `git-c8772e3`
verification across all 84 packages. `.67`/1069 is published through
`fleet/latest`; the HTTPS-served manifest SHA-256 is
`b37da502713fe78a2a0c76b078ddc1e24e07effbdd4f31d54345e4e562435031`,
the arm64 APK SHA-256 is
`f3573ef32c0f228c4befd8b0a51b8d5310f8a4a8ebfde8af6f216e730dbe1ddb`,
and the universal APK SHA-256 is
`60c19b6c434337cab404eef8939d91a9f4fa596d44083901cad0346eb1f4f585`.
`.66` remains available for rollback. Automated tooling did not access the
physical S23FE.

## 52. Automatic Local Reply Drafts

1. Replace the local enabled flag with Off, Manual, and Automatic modes;
   migrate enabled preferences to Manual and disabled preferences to Off.
2. Use snapshot revisions and live-event serials to baseline session opening,
   reconnect, and focus while starting one request for each later completed
   assistant reply or active structured free-text question.
3. Integrate cancelable preparation, selectable drafts, Regenerate, and quiet
   inline Retry into fullscreen and normal Native composers. Cancel on typing,
   attachments, navigation, backgrounding, newer context, or mode change.
4. Cover migration and automatic state transitions with JVM tests and fake-
   engine Compose tests on both Native surfaces. Run focused, fast/full API 36,
   lint, signing, identity/runtime, and served-checksum gates.
5. Publish `.68`/1070 through `fleet/latest`, retain `.67` for rollback, and
   leave the physical S23FE smoke to the user through the in-app update flow.

Implementation record (2026-07-19): Android source commit `dbd8a823` adds
Off, Manual, and Automatic local-suggestion modes, migrates the legacy enabled
preference to Manual, and prepares cancelable local drafts only for new live
assistant replies or active free-text questions. Drafts remain opt-in at the
final step: selecting one fills the composer or answer field without sending.
The focused JVM and Compose regressions passed. The complete managed Pixel
7/API 36 suite passed at
`build/reports/agent-fleet/emulator/20260719T103541Z-full`; all 42
instrumentation tests finished with zero failures and two review-only golden
generators skipped. Release lint completed with zero errors and three existing
PendingIntent warnings. The signed/minified arm64 and universal APKs passed
certificate, checksum, permanent-identity, and embedded runtime `git-c8772e3`
verification across all 84 packages. `.68`/1070 is published through
`fleet/latest`; the HTTPS-served manifest SHA-256 is
`a54183f268060131c186c58c5ef24f7ebcdd2bfc0d4c246087e17c84b6e3023b`,
the arm64 APK SHA-256 is
`164017203a398eead1bed051bc8fe80f8d4477b29db74a742d48090d5e2cd6a9`,
and the universal APK SHA-256 is
`f11c1b04aeaf84413582cec67a738bffc9970dc10ea62b028e0c73136da9f99d`.
`.67` remains available for rollback. Automated tooling did not access the
physical S23FE.

## 53. Phone Header And Live Working Reliability Hotfix

1. Remove the duplicate Native status-bar inset so the fixed 40dp identity row
   and 48dp control row remain inside the 88dp header at the phone font scale.
2. Keep the bounded conversation metadata stream alive for every visible
   managed surface, allowing the shared Native and Terminal header to receive
   provider Working events throughout a run without changing PTY behavior.
3. Reapply managed presentation immediately after an unexpected attachment exit
   and again when its replacement is selected, preserving the header, exact
   Terminal top margin, and composer during exit-255 reconnection.
4. Cover 1.3 system font scale row boundaries, a provider Working event arriving
   while Terminal is visible, and a real exit-255 activity lifecycle. Run the
   focused, golden-review, fast/full API 36, release-lint, signing, identity,
   runtime, and served-checksum gates.
5. Publish `.69`/1071 through `fleet/latest`, retain `.68` for rollback, and
   leave the physical S23FE smoke to the user through the in-app update flow.

Implementation record (2026-07-19): Android source commit `6c3410b8` makes
status-bar ownership explicit for the embedded Termux activity, keeps the
metadata-only conversation stream live across Native and Terminal, and
reasserts managed chrome during exit-255 reconnection. All 139 Android JVM
tests and the complete managed Pixel 7/API 36 suite passed at
`build/reports/agent-fleet/emulator/20260719T115733Z-full`; 43 reported
instrumentation cases completed with zero failures and two review-only golden
generators skipped. The committed golden geometry remained unchanged after
visual review. Release lint completed with zero errors and three existing
PendingIntent warnings. The signed/minified arm64 and universal APKs passed
certificate, v2/v3 signature, checksum, permanent-identity, and embedded
runtime `git-c8772e3` verification across all 84 packages. `.69`/1071 is
published through `fleet/latest`; the HTTPS-served manifest SHA-256 is
`60ab4c66e1f109139f6873b68bcdd933b28958ffa40ae819e9552f0cf6966e91`,
the arm64 APK SHA-256 is
`344c47bf1abb6f09c23d5b3f9eef9b79a51dd7180ac9c7af023aa938f6b63620`,
and the universal APK SHA-256 is
`9dbe297db83cc2cf8351f421df40531c3d44216e0f36939be6f76c03028e1968`.
`.68` remains available for rollback. Automated tooling did not access the
physical S23FE.

## 54. Device-Faithful Header And Reconnect Correction

1. Reproduce the latest phone screenshot as an activity-window contract, not a
   standalone Compose contract: assert a real nonzero status-bar inset, Terminal
   chrome padding, total chrome height, and the identical PTY top margin.
2. Give the shared header a 96dp body with separate 48dp identity/status and
   model/view rows. Restore Native's explicit Compose inset and reserve the
   measured activity inset outside Terminal's header body.
3. Treat an unmapped finished terminal as the intended managed target only when
   it is still the activity's current terminal. This covers the service-first
   removal callback order without reconnecting background or different-target
   failures.
4. Exercise Working arrival, completed-duration transition, active-marker
   ordering, 1.5 font scale, activity recreation, and exit 255. Review changed
   screenshot candidates manually, then run fast/full API 36 and release gates.
5. Publish `.70`/1072 through `fleet/latest`, retain `.69` for rollback, and
   leave the final physical S23FE interaction to the user.

Implementation record (2026-07-19): Android source commit `edd92686` measures
the activity's real status-bar inset for Terminal, restores Native's Compose
inset, expands the two-row header body to 96dp, and survives the service-first
exit callback ordering when the visible managed attachment exits 255. All 139
Android JVM tests and the complete managed Pixel 7/API 36 suite passed; 45
instrumentation cases finished with zero failures and two review-only golden
generators skipped. The changed Native goldens were reviewed with separate
48dp identity/status and control rows. Release lint completed with zero errors
and three existing PendingIntent warnings. The signed/minified arm64 and
universal APKs passed certificate, v2/v3 signature, checksum,
permanent-identity, and embedded runtime `git-c8772e3` verification across all
84 packages. `.70`/1072 is published through `fleet/latest`; the HTTPS-served
manifest SHA-256 is
`e5ea56b9d5339dabf6e38f06a5f565366b7f5cee3ad2c9c32a087aa2b797d661`,
the arm64 APK SHA-256 is
`2cfdae34e6c26603b6e53e618ea29342990ecf34e78702399a936f6dbc896408`,
and the universal APK SHA-256 is
`ade9ff9ab1e3c26b381be82c156c9312f16574c68bc3d111c67168d6dd2debad`.
`.69` remains available for rollback. Automated tooling did not access the
physical S23FE.

## 55. Compact Provider Activity Counter Hotfix

1. Preserve the host's authoritative provider-activity value while shortening
   `Waiting for background terminal` to `Terminal wait` only in the compact
   Native and Terminal header.
2. Keep the elapsed counter beside the shortened phase so minutes and seconds
   remain visible before the one-line header can ellipsize on the phone.
3. Cover raw protocol preservation, presentation compaction, and Compose
   rendering. Run focused, fast/full API 36, release-lint, signing, identity,
   embedded-runtime, checksum, and externally served-byte gates.
4. Publish `.76`/1082 through `fleet/latest`, retain `.75` for rollback, and
   leave the physical S23FE smoke to the user through the in-app update flow.

Implementation record (2026-07-22): Android source commit `cd23d85b` preserves
the authoritative host activity value while rendering the compact phone header
as `Codex · Terminal wait · elapsed`, keeping the live minutes and seconds
inside the one-line status row. The focused formatter/parser regression and the
34-case fast API 36 suite passed. All 150 JVM tests and the complete managed
Pixel 7/API 36 suite passed at
`build/reports/agent-fleet/emulator/20260722T050034Z-full`, with zero failures
and two intentional review-only golden generators skipped. Release lint
completed with zero errors and the three existing PendingIntent warnings. The
signed/minified arm64 and universal `.76`/1082 APKs passed certificate,
permanent-identity, checksum, and embedded runtime `git-b5bb8ec` verification
across all 84 packages. `.76` is published through `fleet/latest`; the
HTTPS-served manifest SHA-256 is
`a8eb294b612d67f9faa8d22cefde3edd193006ea59cdad0b1718b3ba875267cf`,
the arm64 APK SHA-256 is
`e3590bcb8f7ceb344894bad09eada1d8a7f7bf8f788e0a3d43e63346e6fb7ef7`,
and the universal APK SHA-256 is
`206f1256f3e5ba3f009e9cd13b0506f7fbbdcce28392c0291f66ab515ee5a3dc`.
`.75` remains available for rollback. Automated tooling did not access the
physical S23FE.

## 56. Deterministic Android Build And Release Workflow

1. Make the existing controller JDK 17 the Bash default and add a repository
   Gradle launcher that validates Java before every focused task or lint run.
2. Add `auto`, `windows`, and `managed` emulator backend selection. Prefer the
   isolated Windows API 36 AVD under WSL, preserve the existing compatibility
   flag, and keep managed-device and physical-serial safety coverage.
3. Consolidate app version metadata, migrate the release password to a
   mode-0600 signing file, and validate Java, tooling, credentials, Git state,
   published app/runtime sequences, and source version before Gradle starts.
4. Add one timed app-release command for full validation, lint, one signed
   build, verification, default publication, and HTTPS served-byte checks, with
   an explicit hold mode and metadata-only reports.
5. Add deterministic unit/integration coverage for selection, preflight,
   ordering, hold behavior, and redaction. Benchmark daemon, cache, and
   parallel candidates three times and retain only verified median gains of at
   least ten percent.
6. Update Android debugging and private-release documentation first, then
   AGENTS and the Android debugging skill/lessons. Run the protected Windows
   and managed API 36 suites, lint, script tests, shell syntax, and skill
   validation without publishing an APK or touching the physical S23FE.

Implementation record (2026-07-22): Android commits `11151e9d` and
`00c4d192` add the validated Java 17 launcher, WSL-aware emulator selection,
single version source, local credential/configuration setup, release preflight,
timed release orchestrator, served-byte verifier, workflow tests, CI checks,
and documentation. The controller login and interactive Bash environments now
select the existing pinned JDK 17; no package installation was needed. A
three-run `:app:assembleDebugAndroidTest` benchmark retained daemon reuse
(33.5% median improvement) and parallel execution (48.1% further improvement)
while rejecting build cache (2.3%); evidence is under
`build/reports/agent-fleet/gradle/20260722T055653Z/`.

The focused identity regression, all eight release-workflow tests, shell
syntax, and skill validation passed. All 150 JVM tests and the complete
protected Windows API 36 suite passed at
`build/reports/agent-fleet/emulator/20260722T060546Z-full` with 46 successful
instrumentation cases. The explicit managed-device parity suite passed at
`build/reports/agent-fleet/emulator/20260722T060933Z-full` with zero failures
and two intentional review-only golden skips. Release lint passed. The real
controller credentials and keystore pairing passed, and the clean pushed
orchestrator preflight rejected reused sequence 1082 in seven seconds before
Gradle while recording no stale artifacts. The already-published `.76`
manifest and both APKs passed served-byte verification. This tooling-only work
built and published no APK and did not access the physical S23FE.

## 57. Packaged Fleet Registry Recovery Hotfix

1. Reproduce the `.78` physical-phone state where the bridge returns the two
   registered physical hosts but no sessions, while the healthy gaming host
   still reports two live shell sessions and the phone makes no SSH connection.
2. Add the deterministic three-record registry archive to the strict embedded
   runtime descriptor as a separately bounded and SHA-256-verified data asset.
   Validate its manifest and every record in source, APK, and runtime installer
   paths.
3. Treat a missing registry release or a `wtmux.conf` still bound to the
   legacy migration root as an automatic repair condition even when the
   embedded runtime baseline is already current. Install the registry
   atomically through `wtmux-runtime install-registry` before doctor, keep the
   runtime registry block before the managed loader, and preserve the previous
   registry for rollback.
4. Cover descriptor strictness, same-baseline repair selection, stale binding
   detection, and packaged runtime/registry installation on the protected API
   36 emulator. Run the focused regressions followed by exactly one complete
   API 36 release pipeline.
5. Publish `.79` with a code above active release-set sequence 1086, retain
   `.78`, and require only a replace-in-place manual phone update. Do not reset
   data, repeat Move Fleet state, or automate the physical S23FE.

Gate: launching the corrective build automatically replaces a stale imported
registry binding with verified release `03dd58e64da5f4ad`, the phone reaches the
healthy gaming host and lists its existing sessions, and the compatibility
migration remains available without owning the steady-state registry.

Implementation record (2026-07-25): commit `b4308ab5` packages registry
release `03dd58e64da5f4ad` as a strict embedded asset, extends source and APK
verification, repairs a missing release, stale symlink, or legacy config
binding at the same runtime baseline, and installs the registry atomically
before doctor. Commit `c2eb87b2` adds a certificate-validating local loopback
fallback for release preflight and served-byte verification when the
controller's same-node Tailscale TLS hairpin fails.

The focused JVM regressions failed before the implementation and then passed.
The focused protected API 36 packaged registry test passed at
`build/reports/agent-fleet/emulator/20260725T201824Z-focused`. All 15
release-workflow tests passed. The exactly-once full protected API 36 suite
passed in 278 seconds at
`build/reports/agent-fleet/emulator/20260725T202753Z-full`; release lint passed
with zero errors; signed build, source/APK registry verification, publication,
and HTTPS-served byte verification passed. `.79`/1087 is published with arm64
SHA-256 `8565181274f2164dfb00ffdad188c7f3ecb14fc8634047087b02ac2491244898`.
Signed release set 1088 is active with 1086 retained as its immediate rollback.
Automated release tooling did not access the physical S23FE.

Physical-phone gate record (2026-07-26): the user explicitly approved bounded
ADB inspection of serial `R5CX52J9NTB`. The phone already ran `.79` / 1087,
and More confirmed runtime `git-b42c6a0`, all 84 packages, and the packaged
fleet registry were ready. Opening an existing gaming session revealed a
separate host drift:
`/home/sapir_cz/.local/bin/wtmux-host-runtime: No such file or directory`.
The registry declares the adjacent `wtmux-host` launcher, but the runtime
launcher had been added after the controller's last setup refresh.

`bash setup.sh --repair` recreated the missing symlink without changing phone
data. The phone then showed `3 sessions across 2 hosts` and opened the existing
session in Terminal. wtmux commit `7bb65cd` now validates the exact
registry-declared runtime path and preserves `HOST_RUNTIME_MISSING` when the
remote bridge exits before JSON. Android's focused `TransportContractTest`
passed through `scripts/debug/android-gradle.sh` with Java 17. The packaged
registry/session-discovery gate is complete; Move Fleet state must not be
repeated.
