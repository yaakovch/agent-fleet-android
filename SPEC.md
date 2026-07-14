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
  Python, OpenSSH, tmux, Git, fzf, certificates, core terminal tools, and the
  exact compatible wtmux runtime. AI CLIs, Node, credentials, fleet topology,
  and Tailscale remain outside the APK.
- A universal recovery/testing APK is also produced. Android 16/arm64 remains
  the supported private daily-driver target; unsupported ABIs retain the stock
  terminal bootstrap and report that the offline fleet payload is unavailable.
- The offline package set is resolved from pinned official Termux packages.
  Every artifact has a URL, version, size, and SHA-256 lock plus license/source
  metadata and an SBOM. Repair installs only missing packages or packages below
  the compatible floor and never downgrades newer user packages or performs a
  full package upgrade.
- Clean first launch automatically shows one `Preparing terminal` surface and
  provisions entirely offline before pairing. Existing/restored prefixes keep
  home, SSH keys, package state, shell history, Termux properties, and wtmux
  configuration; they start immediately when healthy and otherwise offer an
  explicit one-tap offline Repair.
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
