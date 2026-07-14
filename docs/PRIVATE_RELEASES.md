# Private releases

Public CI produces unsigned review artifacts. Daily-driver APKs are signed only
on a controller with the private Agent Fleet key.

1. Create the key once with two distinct offline backup destinations:
   `scripts/release/init-signing-key.sh /media/backup-a /media/backup-b`.
   Keep its password separately from all three keystore copies.
2. Create the dedicated Ed25519 runtime key with
   `wtmux-runtime-release init-key`, then run
   `scripts/release/backup-runtime-signing-key.sh /media/backup-a /media/backup-b`.
   The APK contains only its public key. Runtime hotfixes use
   `scripts/release/build-runtime-hotfix.sh` and never use the APK keystore.
3. Regenerate and review the pinned Termux package lock only when intentionally
   raising package floors. `scripts/runtime/verify-embedded-runtime.py
   app/src/main/agent-fleet` must pass before every build.
4. Build with a monotonically increasing version code and the HTTPS directory
   that will host the APK:
   `scripts/release/build-signed-release.sh 0.118.4-agentfleet.1 1003 https://host.example/agent-fleet/latest`.
   The release contains an arm64 daily-driver APK plus a universal recovery APK.
5. Verify with `scripts/release/verify-release.sh dist/0.118.4-agentfleet.1`.
6. On the primary controller, set
   `AGENT_FLEET_PUBLISH_PRIMARY=local:/absolute/private/serve/path`. For a
   remote primary use `user@gaming-desktop:/srv/agent-fleet`; optionally set
   `AGENT_FLEET_PUBLISH_FALLBACK=user@work-m:/srv/agent-fleet`. Then run
   `scripts/release/publish-release.sh DIST_DIRECTORY`.
7. Put the primary/fallback app and runtime manifest URLs plus their approved
   artifact origins in a strict `client-policy-v1` file. Pass it to
   `wtmux-pairing prepare-artifacts --client-policy FILE`; pairing installs it
   on Android. Update sources are no longer entered manually in the app.

For a development phone already running Agent Fleet, install a preview only with
`scripts/release/install-device-update.sh ADB_SERIAL APK`. The script uses Android's
replace-in-place mode and verifies that `firstInstallTime` did not change. Never
uninstall Agent Fleet to apply an update: uninstalling also deletes the Termux home,
packages, keys, and wtmux runtime.

The app refuses redirects, HTTP, unapproved origins, oversized files, wrong
checksums, mismatched package/version metadata, and any APK not signed by the
certificate already installed on the phone. Android still presents its normal
installer confirmation. Runtime updates additionally require the pinned Ed25519
key, a new monotonic sequence, a compatible protocol/app version, and a passing
doctor check; failure rolls back automatically.
