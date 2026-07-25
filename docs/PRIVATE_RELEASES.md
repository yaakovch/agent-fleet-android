# Private releases

Public CI produces unsigned review artifacts. Daily-driver APKs are signed only
on a controller with the private Agent Fleet key.

## Controller setup

The controller uses its existing JDK 17 and stores the release password beside
the keystore as `agent-fleet-release.pass`, mode `0600`. Configure the password,
served URLs, and publication destination once without printing the password:

```bash
scripts/release/configure-local-release.sh /path/to/password.txt https://gaming-desktop-1.tail51b214.ts.net/agent-fleet/fleet/latest local:/home/sapir_cz/.local/share/agent-fleet/public https://gaming-desktop-1.tail51b214.ts.net/agent-fleet/runtime/runtime-manifest.json
```

The source file is not used again or deleted automatically. Override the
default signing/config paths only with `AGENT_FLEET_SIGNING_DIR`,
`AGENT_FLEET_STORE_PASSWORD_FILE`, or `AGENT_FLEET_RELEASE_CONFIG`.

## Default rollout policy

After a permanent-ID Android release passes its documented JVM, API 36,
lint, signing, certificate, embedded-runtime, identity, and checksum gates,
publish that verified artifact to the `fleet/latest` in-app update lane as part
of the same release task. A separate publication approval is not required.
Retain the previous release for rollback and verify the HTTPS-served manifest
and APK bytes after switching `latest`. Stop before publication only when the
user explicitly requests a hold, a required gate is incomplete, or publication
would target a different application ID or release lane.

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
   custom bootstrap must keep remote APT sources disabled. A release that
   carries a fleet registry must build it deterministically with
   `wtmux-runtime build-registry`, record its exact file, size, and SHA-256 in
   `embedded-runtime-v1.json`, and pass both source and packaged-APK registry
   verification. The app installs that data archive separately from runtime
   code and repairs stale migration bindings without clearing phone data.
4. Change `app/version.properties` to a version code greater than both the
   published APK code and runtime sequence. Complete the focused regression,
   commit every tracked release change, push it, and ensure the branch is clean
   and synchronized with its upstream.
5. Run `scripts/release/app-release.sh`. It performs the sequence and credential
   preflight, protected Windows-AVD full suite, release lint, one signed build,
   artifact verification, publication, and HTTPS served-byte verification.
   Use `--hold` to stop after verification or `--preflight-only` to check a
   prepared next version without building. Stage timings are stored under
   `build/reports/agent-fleet/release/`.
6. The lower-level `build-signed-release.sh`, `verify-release.sh`, and
   `publish-release.sh` commands remain available for recovery. The signed
   builder requires arguments that exactly match `app/version.properties` and
   refuses invalid credentials, dirty source, or an unpushed commit before
   Gradle starts. It keeps the Windows Gradle child noninteractive with a plain
   console and redirected stdin.
7. For a remote primary use `user@gaming-desktop:/srv/agent-fleet`; optionally
   configure `AGENT_FLEET_PUBLISH_FALLBACK=user@work-m:/srv/agent-fleet`.
   The local path is the filesystem root seen by the HTTP backend after any
   reverse-proxy mount prefix is removed. For the current Tailscale Serve
   `/agent-fleet` proxy, publish to
   `/home/sapir_cz/.local/share/agent-fleet/public`, not a nested
   `public/agent-fleet` directory. The publisher writes the permanent-ID lane
   under `fleet/latest`; the existing top-level `latest` remains the
   `com.termux` legacy lane. Verify both `manifest.json` and the APK URL
   through the externally served URL before announcing the release.
8. Put the primary/fallback app and runtime manifest URLs plus their approved
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

Release preflight intentionally runs before embedded-runtime verification or
Gradle. A wrong password, unsafe password-file mode, dirty/unpushed source,
source/argument version mismatch, or reused monotonic sequence must fail in a
few seconds and must not create or publish an APK.
