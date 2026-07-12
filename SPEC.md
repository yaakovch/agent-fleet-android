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

- Use a Kotlin/Material 3 shell embedding the existing Java `TerminalView` and
  bound `TermuxService` rather than rewriting the emulator.
- Run a verified repo-less wtmux runtime inside Termux and strictly validate its
  JSONL protocol, revisions, idempotency keys, capabilities, and safe aliases.
- Add no arbitrary command endpoint. Credentials, auth files, prompts,
  responses, transcripts, and terminal contents never enter bridge frames,
  caches, logs, or diagnostics.
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
- Backup, emulator rollback rehearsal, and phone rollback instructions pass
  before cutover.
- Critical soak defects are hotfixed in place and restart the clock; corruption,
  security exposure, unusable terminals, or inability to hotfix trigger rollback.

