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

## Verification

- Preserve upstream terminal/service/file-provider/package tests.
- Add Compose navigation, screenshot, accessibility, and lifecycle tests.
- Add protocol/schema/framing/revision/idempotency/injection tests.
- Add provisioning, pairing, reconnect, alias, quota, schedule, image, update,
  migration-manifest, and rollback tests.
- Exercise clean/restored onboarding, process death, reboot, offline cache,
  Tailscale loss, multi-image share, quota failure, and incompatible hosts on
  Android 16 before phone cutover.
