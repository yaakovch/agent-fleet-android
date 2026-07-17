# Agent Fleet Android Companion Specification

Status: approved for implementation on 2026-07-12.

## Goal

Build a daily-driver Android companion for the S23FE that combines a complete,
customizable Termux-compatible terminal with Agent Fleet sessions, host-sourced
AI limits, scheduling, and image attachments.

This GPLv3 fork retains Termux 0.118.3's internal `com.termux` identity,
bootstrap, package paths, terminal emulator, and service. All visible branding,
navigation, terminal controls, and settings become Agent Fleet. The first alpha
targets Android 16/arm64 and is privately signed and distributed through the
trusted fleet.

## Experience

- Open to a large, mobile-native Sessions view with bottom navigation for
  Sessions, Terminal, Limits, and More.
- Group/search sessions by host and support open, create, rename, favorite,
  copy attach command, schedule, and confirmed kill.
- Open local shells and remote wtmux attachments as persistent terminal tabs.
  Persist tab descriptors but never terminal screen content.
- Use bounded reconnect while visible; show Retry and Close when disconnected.
- Preserve shell, `pkg`, PTY, storage, SSH, tmux, file-provider, and
  `RUN_COMMAND` compatibility plus a Classic Termux recovery screen/preset.
- Provide System, Light, Dark, OLED, Classic Termux, Nord, Dracula, and
  Solarized themes with terminal sizing, spacing, cursor, extra-key, gesture,
  hardware-input, and per-tab controls. Honor compatible Termux properties and
  font files.
- Default AI tabs to an Android-native multiline composer with normal IME/voice
  behavior, image chips, and explicit literal Send. Default shells to classic
  direct PTY input. Keep a one-tap per-tab mode toggle.

### Native Session View

- Replace the visible character-grid terminal with a conversation-first
  Material 3 view after staged validation, while keeping the same Termux PTY,
  tmux attachment, and `TerminalView` alive underneath as the source of truth
  and recovery path.
- Render Codex, Claude Code, and Copilot as a live feed of user and assistant
  turns, Markdown, code, diffs, compact status, collapsed tool activity,
  attachments, errors, and safely recognized approvals.
- Indicate structured Codex and Claude Plan mode with an amber composer outline
  and `Plan message…` hint in both Native and manually selected Terminal views.
  Retain the last verified state while reconnecting, reset it for a new session,
  and never infer it from terminal text.
- Group two or more adjacent tool calls into one collapsed, state-aware row.
  Show useful action/target names and oldest-to-newest numbering; expand first
  to collapsed calls and then to ordered Tool/Status, Input, Result, and Duration
  details. Messages, questions, approvals, errors, status, and changes break a
  group. Long groups reveal 25 calls at a time without moving a reader's feed.
- Render Codex `request_user_input`, Claude `AskUserQuestion`, and Copilot
  `ask_user` as focused native question cards with Back/Next, explicit Submit,
  single choice, multi-select, boolean, and free-form Other controls. Keep an
  `Answer needed` shortcut above the composer and collapse transcript-confirmed
  answers. Unknown forms stay readable and open Terminal rather than guessing.
- Load the complete current conversation progressively from the host and follow
  turns entered from any attached phone or desktop. Keep feed contents in
  memory only and re-fetch them after process death.
- Render ordinary Bash and Zsh use as command/result cards only while no AI
  tool is active. Provide a command bar, history, Tab, Ctrl+C, common command
  helpers, current-path breadcrumbs, and a lightweight directory-only browser.
- Open Native by default after the full validation checklist passes. Always
  expose a one-tap Native/Terminal switch. Automatically enter Terminal for an
  alternate-screen program or an interaction that cannot be represented
  safely, then return only when the app initiated that takeover.
- Degrade in order from structured feed, to a cleaned live transcript, to the
  real terminal. Existing and older Windows-backed sessions remain usable even
  when they cannot be mapped to a structured transcript.

## Limits, Scheduling, and Health

- Android is client-only and reads no phone-local quota profiles.
- Display metadata-only Codex and Claude quota snapshots from designated host
  profile aliases, including freshness, reset times, and errors.
- Show genuine wtmux hard-limit events, recommend an eligible available
  profile, and launch through a safe configured alias.
- Offer guarded `continue` for reset plus one minute with edit/dismiss controls.
- More contains schedules, host health/version, pairing, diagnostics, and
  refresh. Host update/repair and registry administration stay desktop-only.
- Poll fleet state only while visible. Terminal tabs retain Termux's foreground
  service notification; no cloud relay or background fleet monitor is added.

## Images

- Accept Android Share/multi-share, gallery/files, and camera input.
- Normalize PNG, JPEG, WebP, and HEIC and optimize unusually large photos.
- Send through wtmux to the active host project under `.wtmux/images/`.
- Add returned host-readable paths to the composer without submitting.
- Keep failed in-memory drafts for manual retry, delete successful local temp
  files, and expire host copies after seven days.

## Security and Distribution

- Use a Kotlin/Material 3 shell layering a native session view over the existing
  Java `TerminalView` and bound `TermuxService` rather than rewriting the
  emulator. The hidden terminal remains laid out and attached.
- Run a verified repo-less wtmux runtime inside Termux and strictly validate its
  JSONL protocol, revisions, idempotency keys, capabilities, and safe aliases.
- Add no arbitrary command endpoint. The metadata bridge still rejects prompts,
  responses, transcripts, terminal output, and credentials. A separate,
  capability-gated session stream may transmit only the actively opened
  session's normalized visible content over the authenticated fleet transport.
  It never stores content in bridge caches, phone storage, logs, or diagnostics.
- Native approval responses are typed, revision-bound operations that validate
  the active process and prompt before injecting an allowed choice. Directory
  reads are restricted to listing the active session's current directory.
- Native question responses are bounded, idempotent, and revision-bound. The
  host must match the active transcript call and re-verify the visible prompt,
  options, selection, and pane after every navigation step. Any mismatch stops
  input and directs the user to Terminal; delivery remains pending until the
  transcript confirms the result.
- Keep host alias paths and credentials in untracked host-local configuration.
- Public CI builds unsigned artifacts. A controller signs using an offline key
  and publishes APK/manifest/checksum to gaming-desktop; work-m is fallback.
- Verify checksum, version, and expected signing certificate before opening
  Android's confirmed installer.
- Integrate styling, sharing, storage, shortcuts, and Agent Fleet actions in the
  main app. Official Termux plugins are signature-incompatible and their forks
  are deferred.

## Provisioning, Migration, and Rollback

- Restored installs validate/reuse wtmux state. Clean installs scan/paste an
  invitation, wait for controller approval, install required packages and a
  checksummed runtime, and require a passing doctor result.
- The current S23FE Termux is F-Droid-signed and cannot update in place. Before
  uninstalling it or Widget, archive the complete Termux files tree plus a
  portable home/config archive; record packages, permissions, properties,
  manifests, checksums, installed APKs, and signing certificates.
- Keep verified copies on the controller PC and Android Downloads.
- Restore a sanitized copy on an emulator, migrate to Agent Fleet, roll back to
  captured F-Droid Termux, and compare state before touching the phone.
- Phone rollback uninstalls the fork, reinstalls captured F-Droid APKs, and
  restores the verified archive.

## Success Criteria

- The S23FE replaces current Termux use for seven consecutive days.
- Local shells, packages, SSH/tmux, storage, shortcuts, Classic Termux,
  customization, Compose input, all safe fleet actions, reconnect, limits,
  profile launch, scheduling, and multiple-image attachment work end to end.
- A signed fleet-host update installs after verification.
- Codex, Claude Code, and Copilot render complete progressive history and live
  cross-device activity in Native view on supported Linux sessions. Newly
  launched Windows sessions map exactly and older sessions fall back cleanly.
- Local and remote shells produce bounded command cards and directory
  navigation; editors and other alternate-screen programs take over with the
  real terminal and return correctly.
- Feed contents do not survive process death, do not appear in metadata caches
  or diagnostics, and reconnect without duplicate turns.
- Backup, emulator rollback rehearsal, and phone rollback instructions pass
  before cutover.
- Critical soak defects are hotfixed in place and restart the clock; corruption,
  security exposure, unusable terminals, or inability to hotfix trigger rollback.

## Human Tool Details, Questions, And Session Locations

- Native tool groups open into ordered terminal-themed summaries with meaningful
  action/target labels. Each call shows a compact semantic preview first, can
  expand to the complete presentation, offers Copy, and keeps raw JSON behind a
  separate collapsed disclosure. Unknown tools show readable key/value fields.
- Safe ANSI styling may be represented with the app terminal palette; raw escape
  sequences are never rendered or executed.
- For multi-question prompts, single and boolean choices advance immediately
  and submit immediately when final. Multi-select questions use Done and text
  questions use Send. Back revises earlier answers. A failed delivery preserves
  answers in Native view and shows the exact safe error with Retry and Terminal
  actions.
- New Session follows Host, Backend, Projects/Other location, Folder, editable
  label, and Tool. Projects are real host folders, not inferred active sessions.
- Other location provides an accessible-directory browser rooted at home/profile
  with project, filesystem, mount, and drive shortcuts; it hides dot entries,
  supports safe one-child New Folder, and requires Use this folder.
- Up to ten recent locations are kept locally per host/backend and can be
  cleared. Full paths appear only in Session Details and never in routine session
  titles or lists. Directory listings are transient and never persisted.

## Built-In Android Runtime

- The arm64 APK is fleet-ready without a separate Termux package or wtmux
  installation. It carries an APK-authenticated baseline containing Bash,
  Python, OpenSSH, Git, certificates, core terminal tools, and the exact
  compatible wtmux runtime. AI CLIs, Node, credentials, fleet topology, and
  Tailscale remain outside the APK.
- A universal recovery/testing APK is also produced with only fixed-prefix
  arm64 and x86_64 bootstraps. Android 16/arm64 remains the supported private
  daily-driver target; x86_64 exists for the isolated API 36 emulator and does
  not claim the arm64 offline wtmux repair payload.
- The offline package set is built in the pinned public Agent Fleet Termux fork,
  never downloaded from the official Termux repository during an app build.
  The immutable bundle and every member have a version, size, and SHA-256 lock
  plus license/source metadata and an SBOM. Repair installs only missing
  packages or packages below the compatible floor and never downgrades newer
  user packages or performs a full package upgrade. The fixed-prefix bootstrap
  leaves remote APT sources disabled because official Termux packages target a
  different prefix; package refreshes ship only in a reviewed Fleet release.
- Clean first launch automatically shows one `Preparing terminal` surface and
  provisions entirely offline before pairing. Existing permanent-ID prefixes
  keep home, SSH keys, package state, shell history, terminal properties, and
  wtmux configuration; they start immediately when healthy and otherwise offer
  an explicit one-tap offline Repair. Fleet-only bridge migration deliberately
  does not copy the incompatible old package prefix. Clean provisioning creates
  the permanent app's private home before the first package inspection or
  runtime process is started.
- Runtime storage retains an immutable APK baseline, the active release, and
  one previous release. Health-check failure rolls back atomically. Diagnostics
  exposes baseline/current/previous versions, package-floor health, update
  source, signing key ID, last check, Repair, Check, and Roll Back without
  exposing credentials or conversation content.
- wtmux hotfixes remain independently deployable. A dedicated Ed25519 release
  key signs canonical `runtime-update-v1` envelopes containing a monotonic
  sequence, version, protocol, HTTPS artifact URL, SHA-256, size, minimum app
  version, and key ID. The APK pins trusted public keys and rejects unknown
  keys, replay/downgrade, incompatible versions, malformed envelopes,
  unapproved origins, oversized artifacts, and checksum failures.
- Pairing provisions a versioned `client-policy-v1` file with primary and
  fallback private HTTPS endpoints for both APK and runtime manifests. Neither
  endpoints nor fleet credentials are baked into the APK. Runtime checks occur
  while the app is foregrounded at most once every six hours and on explicit
  request; successful compatible hotfixes activate quietly and remain visible
  in diagnostics.
- Essential Android integrations for clipboard, share/files, camera,
  notifications, and storage live in the main APK. The full Termux:API catalog
  and separate signature-coupled Termux plugins remain out of scope.

## Native Reliability And Windows Parity

- Native sessions use the same newest-first opening, anchor-preserving upward
  pagination, conditional bottom-follow, New messages, rich tool lifecycle,
  pinned question/approval, hard-limit, Close/Kill, and Copy behavior validated
  by the Windows client first.
- Fleet state is owned by one foreground-scoped runtime shared by Sessions,
  Limits, and Native terminal surfaces. It is active only while one of those
  surfaces is visible; Android adds no background fleet polling.
- Pending questions replace the composer, use the immediate/Done/Send behavior
  above, and clear only after transcript confirmation. Reconnects restore the
  pending action rather than hiding it.
- Routine legacy Termux title and session-switch toasts are suppressed while
  Native view is visible. Genuine exits, connection failures, and errors remain
  visible.
- All code-like blocks expose explicit Copy actions. Clipboard content is
  written only on user request and never retained in app logs or diagnostics.

## Native View Lifecycle Efficiency

- A remote conversation stream runs only while its managed activity and Native
  view are visible. Manual or automatic Terminal mode stops the stream; return
  to Native starts a fresh newest-page stream without persisting content.
- Unchanged conversation heartbeats do not replace Compose state or update the
  composer. Terminal emulation remains authoritative while Native is visible,
  but the invisible TerminalView is invalidated only when it becomes visible.
- Local shell cards continue receiving bounded emulator text while Native is
  visible, and alternate-screen detection continues to switch safely to the
  terminal without waiting for a conversation stream.

## Focused-Pane Desktop Chrome

- Desktop/DeX replaces repeated pane headers and embedded Native top bars with
  one shared focused-pane toolbar. It contains identity, Native/Terminal,
  conditional Retry, and More; Detach/Close acts on the focused pane.
- Every pane retains a compact draggable chip with pane number, status, title,
  and N/T badge. Tapping anywhere focuses the pane, while dragging its chip
  swaps the complete session assignment and view mode.
- Empty and opening panes keep stable disabled controls and Close in More.
  Terminal and embedded Native content reserve only the chip clearance.
- The compact phone presentation keeps its existing fullscreen Native and
  Terminal controls because it has no multi-pane workspace chrome.

## Native Structured Work And Interaction Reliability

- Consume additive `task_list` and `plan` records from wtmux. One stable task
  board updates in place, highlights the active task, bounds large lists around
  that task, and collapses after completion. Plans render as Markdown previews
  and open in a dedicated scrollable viewer.
- Keep feed tool cards intentionally small: at most six preview lines and eight
  named actions. Full semantic input and output open in a viewport-bounded
  viewer with terminal-style output wrapping, horizontal code/path scrolling,
  per-block Copy, optional raw data, and a persistent close action.
- Pending questions and approvals use a compact pinned action bar. Their dialog
  is viewport-bounded, internally scrollable, and keeps navigation and delivery
  actions fixed so long prompts and option descriptions remain usable.
- Recognize only wtmux-verified structured questions and exact known terminal
  Plan gates. Unknown terminal screens fail closed and continue to offer the
  real Terminal view.
- Task/tool lifecycle updates preserve list anchors and do not emit redundant
  generic Working/Done cards. Large bodies stay outside the lazy feed so the
  S23FE does not display blank, partial, or top-jumping frames.

### Built-In Runtime Contracts

- `embedded-runtime-v1` binds the APK baseline version and wtmux commit to the
  runtime archive and the package lock, including hashes, sizes, supported ABI,
  protocol version, trusted key IDs, and generation metadata.
- `client-policy-v1` contains only policy revision, approved primary/fallback
  HTTPS manifest URLs, allowed artifact origins, and optional check cadence.
  It is installed as a pairing artifact with mode 0600 and is never sourced as
  shell code.
- `runtime-update-v1` is a bounded JSON envelope containing base64url canonical
  payload bytes, a key ID, and an Ed25519 signature. The decoded payload has an
  exact field set and monotonic integer sequence; the last accepted sequence is
  persisted before a release is considered healthy.
- From app version code 1035 onward, runtime sequences share that monotonic
  namespace. A healthy embedded baseline records at least the installed APK
  code as both accepted and healthy; only a signed healthy runtime at or above
  that floor may remain active across an APK baseline refresh.

## Session Repository Downloads

- Session More includes Download a file. The browser uses the shared transient
  repository page contract, folders-first navigation, recursive name search,
  explicit hidden-file control, cursor paging, and the same 50 MiB confirmation
  and 2 GiB maximum as Windows and terminal clients.
- File bytes stay outside the fleet JSON bridge. The embedded wtmux runtime
  streams directly to Android Downloads, verifies the host SHA-256 trailer,
  avoids overwrites, removes partial files on cancellation/failure, reports
  progress, and indexes successful files with Android's media scanner.
- Completed files open only after an explicit tap through the app's scoped
  FileProvider URI. Repository metadata and downloaded content never enter
  fleet snapshots, app logs, diagnostics, or Compose saved state.
- Repository errors retain their typed host code. Retry is offered only for
  offline, disconnect, and timeout failures; permanent session/path failures
  remain readable without an ineffective Retry action.

## Stale Hard-Limit Attention

- Parse and retain only `detected`, `offering`, and `offered` hard-limit
  attention. Resolved, expired, dismissed, scheduled, and unknown future states
  never produce cards in Sessions, Limits, or Native view.
- Dismiss hides the Native card immediately and keeps it hidden during the host
  round trip. A successful or already-resolved response stays hidden; a genuine
  transport or host failure restores the card with an error.
- The app never infers recovery from terminal pixels or rendered conversation
  rows. The embedded wtmux runtime remains authoritative for verified later
  Codex/Claude activity and linked schedule cancellation.

## Emulator-First Debugging And Private Diagnostics

- Android development uses three layers: fast JVM tests, fixture-driven Compose
  instrumentation on an Android 16 x86_64 Pixel 7 emulator, and a final manual
  S23FE smoke only. Automated tooling must reject physical serials and prove
  `ro.kernel.qemu=1` before any install, uninstall, or device mutation.
- The local canonical AVD is `AgentFleet_S23FE_API36` at 1080×2400 and 420 dpi.
  Gradle exposes an equivalent managed Pixel 7/API 36 Google APIs device. The
  runner offers fast, full, and explicit update-goldens modes and keeps quiet
  summaries plus complete logs, instrumentation results, screenshots, and
  diffs as build artifacts.
- Stable Compose semantics cover navigation, Sessions/More, repository
  loading/error/retry/progress/cancel, Native paging, grouped tools/details,
  tasks/plans, active/stale limits, and one- or three-question Plan prompts.
  Choice taps advance single/boolean questions and only the final choice
  submits; tests prove exactly-once delivery and long-card scrolling.
- Key dark references are exactly 393×852. A pixel differs when any RGB channel
  differs by more than eight; more than 0.5% changed pixels fails. Reference
  updates are explicit artifacts and require human review.
- More → Diagnostics offers Run checks, Preview, Copy summary, and Export. Local
  checks have a five-second budget, remote read-only host checks have a
  twenty-second budget, and a run reports attention beyond forty-five seconds.
  Checks cover app/ABI, required tools, runtime health, Downloads create/fsync/
  collision/replace/cleanup, pairing/update readiness, fleet snapshot/host
  doctor, and local shell/process startup.
- The app-private diagnostic journal retains at most 200 events, 256 KiB, and
  seven days. Events contain operation, time, duration, status, safe code and
  message, and optional host/session IDs; they never contain payload bodies.
  Errors are assigned a safe event ID and may lead to Details, Copy, or Open
  Diagnostics without logging conversation content.
- Export previews before sharing and creates only `diagnostics.json` and
  `events.ndjson` under schema `agent-fleet-diagnostics-v1`. Redaction and tests
  exclude prompts, responses, transcripts, terminal output, credentials,
  tokens, invitations, attachments, and repository paths.

## Wide-Window Session Workspace

- Android implements Auto, Phone, and Desktop presentation modes from current
  window width rather than a Samsung-only API. Auto keeps the existing compact
  single-session flow below 840 dp and selects Desktop at or above 840 dp, so
  DeX, tablets, and AR-glasses windows share the same behavior. Manual Phone and
  Desktop overrides remain available.
- Desktop mode uses the shared `workspace-layout-v1` split tree with a
  collapsible vertical session rail, one to four unique assignments, split
  actions and presets, draggable dividers, focus/MRU, and an independent Native
  or Terminal view per pane. New AI assignments start Native.
- Desktop state persists separately from the compact phone flow. Width changes
  never compress or overwrite the desktop tree. Entering Desktop hands the
  active phone session's existing local attachment to the focused pane; leaving
  Desktop continues the focused session full-screen without a duplicate PTY.
- Assigned local PTYs remain alive while Desktop is hidden, but invisible
  terminal renderers and Native conversation streams stop. Closing or replacing
  a pane detaches locally; only explicit guarded Kill ends tmux. Classic local
  Termux shells remain outside the split workspace.
- `TermuxService` owns Agent Fleet attachment identity. Every compact, Native,
  image-share, and Desktop open reuses one marked PTY per remote session and
  reconciles legacy duplicates without touching tmux. Active attachments plus
  the most recently used inactive attachments are capped at four. Android Back
  caches the attachment; local Close, pane replacement, retention eviction, or
  successful remote Kill releases it.
- Managed attachments are hidden from the Classic Termux drawer, numbering,
  shortcuts, and local-session notification count. When no classic shell is
  open, the foreground notification says `Agent Fleet active`. Agent Fleet's
  Enter/Return state reflects this device's reusable attachment rather than a
  remote client's attached flag.
- Sessions owned by connecting or offline hosts are last-known and unavailable:
  remote actions are disabled, Hide removes only the local cached record, and a
  healthy authoritative snapshot may restore it. Assigned panes reconnect or
  become ended when the host returns. Host-offline races use a plain inline
  message and keep diagnostic IDs under Details; Kill retries one stale revision
  with the same idempotency key and accepts an already-absent session. A newly
  opened bridge must finish its bounded startup snapshot before checking the
  fleet revision for any mutation, so startup itself cannot manufacture a stale
  result.

## Permanent Android Identity And Fixed Terminal Runtime

- Move the daily-driver app permanently to `com.yaakovch.fleet`, remove the
  shared UID, and rebuild every native Termux package for
  `/data/data/com.yaakovch.fleet/files/usr`. Keep the Java/Kotlin source
  namespace independent at `com.termux`; application identity, native prefix,
  and public package artifacts must never be mixed.
- Preserve the real terminal renderer, PTY service, remote wtmux attachments,
  pairing, image workflows, downloads, diagnostics, and terminal appearance.
  Remove visible local-shell/package management and exported generic Termux
  surfaces (`RUN_COMMAND`, document provider, generic file receiver, shortcuts,
  plugin/shared-UID coupling). Official Termux must install alongside Agent
  Fleet without signature, UID, provider-authority, or prefix conflicts.
- Build the fixed arm64 production and x86_64 emulator runtime in the public
  `yaakovch/agent-fleet-termux-packages` fork from an exact upstream commit and
  pinned builder digest. Publish immutable bootstrap, package closure, package
  lock, source/license inventory, SBOM, checksums, and provenance. Package
  floors change only in planned app releases.
- Use the Windows clock mark for the Android adaptive/legacy launcher icon. The
  temporary `com.termux` bridge is labeled `Agent Fleet Legacy` and uses an
  obvious legacy badge so both apps are distinguishable when coinstalled.
- Transfer only allowlisted Fleet preferences, wtmux configuration and client
  policy, the current verified registry release, and bounded regular SSH files.
  Require same signing certificates and explicit user confirmation, verify a
  checksummed `agent-fleet-migration-v1` archive, rewrite only managed private
  roots and update lanes, and support both forward and reverse transfer. Never
  copy packages, arbitrary home content, history, caches, downloads,
  attachments, transcripts, credentials outside `.ssh`, or runtime releases.
  Rewrite both Android credential-storage aliases (`/data/data` and
  `/data/user/0`) when the package identity changes, including managed wtmux
  and SSH configuration paths. A permanent build must repair those same
  allowlisted paths left by an older importer before starting a session. The
  activated registry declaration must precede the managed registry loader;
  migration and startup repair normalize that order and bind it to the
  destination app's verified `registry/current/machines` path so Terminal and
  Native resolve the same hosts.
- Keep the existing top-level private update lane for `com.termux` legacy
  rollback. Publish `com.yaakovch.fleet` only through
  `agent-fleet/fleet/latest`, and require manifests plus APK inspection to match
  the lane application ID, version, checksum, and existing signing certificate.
- Ship new-ID `.41`/1043 and bridge `.41-legacy`/1043. Retain `.40` and the
  legacy bridge until at least 90 days have passed and two permanent-ID releases
  have succeeded. The final S23FE acceptance is manual and must prove official
  Termux coinstallation, forward/reverse migration, Native/Terminal sessions,
  reconnect, resize/DeX, update, and rollback. Reverse migration is tested while
  the bridge is installed; the bridge must then be removed before installing
  official Termux because both own `com.termux`. Automated tools never operate
  the physical phone.

## Instant Local Terminal History

- In compact and Desktop Terminal views, alternate-screen Codex, Claude, and
  Copilot sessions expose a small History/Remote control. History presents the
  existing structured conversation as a terminal-styled, read-only overlay;
  Remote gives wheel gestures back to the remote full-screen application.
- Prefetch starts after two quiet seconds only while an eligible Terminal view
  is visible. One process-wide request queue loads 100-item no-follow pages and
  automatically paginates near the top to a 2,000-item ceiling. Metered links
  are allowed; failures wait for an explicit Retry.
- The cache exists only in process memory and is discarded when its controller
  closes. It is never migrated, persisted, diagnosed, logged, or copied into
  terminal scrollback. No protocol or embedded runtime change is required.
- The service-owned PTY and renderer remain attached behind the overlay.
  Keyboard input continues to control Live, terminal output marks cached
  History Updated, and refresh occurs only after the user returns Live. Closing
  or copying restores terminal focus.
- Phone and Desktop use the same controller, bounds, failure states, and scroll
  capture policy. Unsupported adapters, normal-buffer programs, Native views,
  and classic shells preserve their existing behavior.

## Terminal History Rollback Hotfix

- Permanent-ID `.46` removes the `.45` History/Remote overlay and alternate-
  screen scroll interception from both Phone and Desktop terminal surfaces.
- The live service-owned PTY, `TerminalView`, terminal composer, image picker,
  and Native/Terminal switch return to the `.44` rendering and lifecycle path.
- Structured terminal history remains deferred until its blank-Live, failed-
  History, reconnect, and unstable-link behavior can be reproduced and fixed
  on the isolated API 36 emulator. No protocol or embedded-runtime change is
  part of this rollback.

## Isolated Terminal History Restoration

- Permanent-ID `.51` restores structured Terminal history without restoring
  `.45`'s always-mounted full-screen overlay. The compact reader view is
  physically `GONE` while Live is selected; Desktop embeds only a bounded
  control until History opens. The service-owned PTY and `TerminalView` remain
  the same instances throughout open, close, Native switching, and refresh.
- History requests use the permanent-ID Termux process environment introduced
  in `.49`, so Tailscale-transport hosts use packaged OpenSSH and never require
  a Tailscale CLI. Errors redact private runtime paths, stop automatic retries,
  and leave Live usable.
- The shared controller prefetches after two quiet seconds, reads 100-item
  no-follow pages, keeps at most 2,000 memory-only items, and loads older pages
  only after the initial reader position reaches the newest item. Remote mode
  returns wheel gestures to the alternate-screen program.
- Terminal, composer, toolbar, and Native visibility changes explicitly refit
  and redraw the live renderer so Codex prompts receive the current rows and
  columns after keyboard, window, and Desktop resizing.

## Permanent-ID Image Attachment Hotfix

- Permanent-ID `.47` imports picker content by its actual PNG, JPEG, or WebP
  signature instead of trusting a provider MIME string. HEIC, HEIF, GIF, and
  other images the Android decoder supports are bounded and normalized to PNG
  or JPEG before transfer; empty, unreadable, or over-20-MB input fails safely.
- Image transfer uses the ordinary `wtmux image send` path response and does
  not require companion JSON generation. Standard output and error are drained
  concurrently so a child process cannot block on a full pipe, and the 30-second
  timeout remains enforced.
- Compact Terminal and workspace Native attachment paths share the importer and
  uploader. Failures expose a bounded action for configuration, pairing,
  reachability, project, format, or timeout problems without exposing the
  permanent app's private path or repository paths.
- The `.46` History rollback, app identity, migration state, embedded runtime,
  wtmux protocol, terminal renderer, and retained rollback artifacts do not
  change.
- Follow-up `.48` treats exit 127 as a runtime-integrity failure. Before each
  upload it verifies the exact local command set used by `wtmux image send` and
  automatically reinstalls only the owning checksum-verified offline packages
  when a migrated runtime is incomplete. Manual Runtime repair uses the same
  package-integrity rule.
- The Android 16 acceptance test must install the packaged Termux bootstrap and
  packaged wtmux runtime, load a machine config, run real hash/temp/SSH command
  sequencing through a deterministic fake host, stream a PNG, and validate the
  returned attachment path. Closing a completed Android process pipe is normal
  end-of-stream behavior and must not crash the reader thread.
- If exit 127 remains after local integrity passes, show whether the missing
  command is local or the remote `wtmux-host` helper without exposing a private
  app path, home path, repository path, or command arguments.
- Follow-up `.49` marks every app-owned runtime child as Termux even though the
  permanent application ID is `com.yaakovch.fleet`. A Tailscale-transport host
  must therefore use the bundled OpenSSH client, matching interactive terminal
  sessions, rather than attempt to invoke the intentionally absent Tailscale
  CLI. The packaged-runtime attachment acceptance test uses a Tailscale host so
  this transport distinction cannot be mocked away again.
- Follow-up `.50` makes Diagnostics reflect supported Android 16 behavior.
  Host doctor requests refresh and retry once when their UI snapshot revision
  has changed, rather than treating ordinary background fleet activity as a
  host failure. Downloads diagnostics and repository downloads use the Android
  Downloads provider on Android 10 and newer, staging shell output privately
  before publishing it, so shared storage does not depend on legacy direct-file
  permission. Diagnostic probes must publish, reopen, verify, and delete a
  bounded temporary item through the same provider path.

## Seamless Prefetched Tmux Scrollback Correction

- Permanent-ID `.52` removes `.51`'s structured History reader, History/Remote
  controls, cards, headers, and full-screen Compose surface. That interaction
  did not meet the requirement: scrolling Terminal must continue to look and
  behave like the terminal itself.
- While a visible eligible alternate-screen terminal is quiet, the client asks
  `wtmux pane scrollback` for at most 2,000 rows and 4 MiB of ANSI from the real
  tmux pane. The frame is memory-only, session-bound, SHA-256 checked, and
  discarded on detach, normal-buffer transition, resize mismatch, stop, or
  controller close.
- An upward wheel, drag, or Page Up renders the cached pane through the existing
  `TerminalRenderer`; the live `TerminalSession`, PTY, keyboard, colors, font,
  and dimensions remain attached. Reaching the cached bottom or typing returns
  directly to Live with no mode change or visible seam. Live output never snaps
  a user out of cached scrollback.
- The client-side pane command runs a bounded Python capture over the existing
  host transport and therefore works with an older host runtime. Content an
  alternate-screen program never placed in tmux history cannot be reconstructed
  retroactively; the client must not substitute structured conversation cards.
- Image upload repairs the verified local tool set before sending, retries
  exactly once for exit 127, SSH exit 255, timeout, Broken pipe, reset, refused,
  or unreachable failures, then reports whether the command is unavailable or
  the image connection was lost. The retry uses the same bounded source and
  never loops in the background.

## Silent Client-Only Terminal Attachment

- Permanent-ID Agent Fleet is a wtmux client and is not required to register
  itself as a host machine. A noninteractive Terminal attachment with an
  explicit host, project, and session must therefore produce no local-machine
  registration warning before the remote tmux screen takes over.
- Interactive wtmux use continues to offer local-machine registration. The
  correction changes no registry, transport, pairing, session, or pane
  protocol and does not suppress genuine host, SSH, or tmux errors.
- Permanent-ID `.53` embeds exact wtmux `git-bdc19c0`. Its API 36 acceptance
  test installs the packaged Termux and wtmux runtimes, uses a deliberately
  non-local machine registry, performs a real noninteractive attachment
  through deterministic OpenSSH, and asserts that the startup stream reaches
  the remote session without the obsolete warning.

## Local Reply Suggestions

- Native conversations expose an opt-in Suggest action after completed
  assistant messages and in structured free-text answer fields. It is hidden
  while a draft exists and is unavailable for approvals and choice questions.
  Suggestions are generated only on tap; choosing one fills but never submits.
- Inference receives only the newest 12 visible user/assistant text messages,
  newest-first bounded to 12 KiB UTF-8, excluding tools, terminal output,
  attachments, hidden details, approvals, and choices. It produces one to three
  concise, conservative, distinct first-person reply drafts.
- The first supported model is a release-pinned, checksum-verified Gemma 4 E2B
  Instruct LiteRT-LM artifact. Setup supports direct download and local import,
  warns before metered transfer, and exposes progress, cancellation, verification,
  retry, and removal. The feature and model are off by default.
- Inference runs in a non-exported `:local_llm` process with GPU then CPU
  fallback. Backgrounding or disabling the feature terminates that process;
  60 seconds without a request also exits it. The app does not retain prompts or
  results and excludes model state/content from diagnostics and exports.
- API 36 tests use a fake engine and small fixtures; the large model is not
  downloaded in routine automation. Manual S23FE acceptance targets at most
  15 seconds cold and 5 seconds median warm plus one usable draft on at least
  80% of a 30-prompt benchmark.
