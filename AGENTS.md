# Agent Fleet cross-platform development policy

- Design shared user-facing workflows once across the wtmux protocol, Windows Agent Fleet, and Android Agent Fleet.
- Implement and validate Windows first when it provides the faster feedback loop, then complete Android parity before calling the workflow done.
- Sessions, Native conversation, limits, schedules, attachments, and settings require equivalent Windows and Android behavior unless a documented platform constraint prevents it.
- Any shared protocol or fixture change must be validated by both client repositories before rollout.
- Keep platform-specific internals independent, but document every intentional user-visible deviation and its reason in the relevant specification and implementation plan.
- After a permanent-ID Android version passes every documented release gate,
  publish the exact verified artifact to the `fleet/latest` in-app update lane
  by default unless the user explicitly requests a hold. Retain the previous
  version for rollback and verify the HTTPS-served manifest and APK checksum.
- Run focused Gradle work through `scripts/debug/android-gradle.sh`; never rely
  on the ambient machine Java. For a release-bound change, run the focused
  regression and one `android-check.sh full`, not a redundant fast/full pair.
- Under WSL, keep the protected Windows API 36 AVD as the normal full backend;
  use the managed backend explicitly for CI parity or runner changes. Never
  weaken the isolated-ADB or physical-device refusal checks.
- Prepare releases as clean pushed commits with version data in
  `app/version.properties`, then use `scripts/release/app-release.sh`. Do not
  hand-assemble the lint/build/sign/publish sequence or build before committing.
