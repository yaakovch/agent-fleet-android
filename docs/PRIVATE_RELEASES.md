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
   From version code 1035 onward, use the same monotonic number space for APK
   version codes and runtime sequences; the runtime sequence must be at least
   its declared minimum app version code.
3. Build and publish a fixed-prefix runtime release from the public Agent Fleet
   Termux fork only when intentionally raising package floors. Commit both
   immutable release descriptors under `app/runtime-pins` plus the matching
   arm64 lock/SBOM, then run `scripts/runtime/verify-embedded-runtime.py
   app/src/main/agent-fleet`. App builds verify both complete public bundles;
   never substitute an official-Termux bootstrap or direct package URL. The
   custom bootstrap must keep remote APT sources disabled.
4. Build with a monotonically increasing version code and the HTTPS directory
   that will host the APK:
   `scripts/release/build-signed-release.sh 0.118.4-agentfleet.43 1045 https://host.example/agent-fleet/fleet/latest`.
   The release contains an arm64 daily-driver APK plus a universal recovery APK.
5. Verify with `scripts/release/verify-release.sh dist/0.118.4-agentfleet.43`.
6. On the primary controller, set
   `AGENT_FLEET_PUBLISH_PRIMARY=local:/absolute/private/serve/path`. For a
   remote primary use `user@gaming-desktop:/srv/agent-fleet`; optionally set
   `AGENT_FLEET_PUBLISH_FALLBACK=user@work-m:/srv/agent-fleet`. Then run
   `scripts/release/publish-release.sh DIST_DIRECTORY`.
   The local path is the filesystem root seen by the HTTP backend after any
   reverse-proxy mount prefix is removed. For the current Tailscale Serve
   `/agent-fleet` proxy, publish to
   `/home/sapir_cz/.local/share/agent-fleet/public`, not a nested
   `public/agent-fleet` directory. The publisher writes the permanent-ID lane
   under `fleet/latest`; the existing top-level `latest` remains the
   `com.termux` legacy lane. Verify both `manifest.json` and the APK URL
   through the externally served URL before announcing the release.
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
