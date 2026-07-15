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
