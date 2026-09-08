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

The public production signer fingerprint is independently pinned in
`app/release-signing-certificate-sha256.txt`. The controller must also keep a
separate mode-`0600` backup at `signing/certificate-sha256.txt`. Preflight
requires that protected repository pin, local backup pin, keystore certificate,
and both signed APK certificates all match. Signing passwords are passed only
to `keytool` or `apksigner`; Gradle, lint, emulator, verification, and
publication children receive an environment with those variables removed.

## Default rollout policy

When preparing an isolated release checkout, compare its user-facing behavior
with the previous delivered app and inspect pending changes in the original
checkout. Preserving those files on disk does not preserve behavior in the APK.
Carry forward approved UI changes that have already reached the user, and keep
their interaction and layout regressions in the release branch. In particular,
the compact Sessions screen must pass its four-visible-cards regression.

After a permanent-ID Android release passes its documented JVM, API 36,
lint, signing, certificate, embedded-runtime, identity, and checksum gates,
publish that verified artifact to the `fleet/latest` in-app update lane as part
of the same release task. A separate publication approval is not required.
Retain the previous release for rollback and verify the HTTPS-served manifest
and APK bytes after switching `latest`. Publication first stages a complete
version directory, refuses to replace an existing version with different
bytes, and switches `latest` with a same-filesystem rename. If served-byte
verification fails, the orchestrator restores the exact target recorded before
the compare-and-switch. The same receipt also makes an uncertain remote SSH
switch response safely rollback-capable. Stop before publication only when the
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
   That command currently builds and verifies candidates only. Production
   runtime-hotfix publication is blocked until its publisher performs
   server-side signed-envelope reproof and burns a claim in the same base-level
   sequence authority described below.
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
4. Change `app/version.properties` to a version code greater than the published
   APK code, signed runtime sequence, immutable deployed bootstrap floor `1096`,
   and any publisher high-water claim. Complete the focused regression, commit
   every tracked release change, push it, and ensure the branch is clean and
   synchronized with its upstream.
5. Run `scripts/release/app-release.sh`. It performs the sequence and credential
   preflight, protected Windows-AVD full suite, release lint, one signed build,
   artifact verification, a second live app/runtime sequence check immediately
   before publication, transactional publication, and HTTPS served-byte
   verification without following redirects. A failed served check restores
   the captured previous `latest` target while retaining the rejected immutable
   version directory for investigation.
   Use `--hold` to stop after verification or `--preflight-only` to check a
   prepared next version without building. Stage timings are stored under
   `build/reports/agent-fleet/release/`.
   Windows remains the default emulator backend. Explicit
   `AGENT_FLEET_EMULATOR_BACKEND=managed` runs the same protected API-36 suite
   for CI parity or an isolated Linux build environment, and the report records
   the selected backend. Neither backend skips device checks or test failures.
6. The lower-level `build-signed-release.sh`, `verify-release.sh`, and
   `publish-release.sh` commands remain available for recovery. The signed
   builder requires arguments that exactly match `app/version.properties` and
   refuses invalid credentials, dirty source, or an unpushed commit before
   Gradle starts. It keeps the Windows Gradle child noninteractive with a plain
   console and redirected stdin. Direct forward publication is intentionally
   unavailable until `verify-release.sh` has created
   `candidate-proof-v1.json` and a signed sequence reservation has been created
   and reproved:

   ```bash
   scripts/release/check-release-sequence.py VERSION_CODE \
     HTTPS_BASE_URL/manifest.json HTTPS_RUNTIME_MANIFEST_URL \
     --reservation-out /secure/state/sequence-reservation-v1.json
   scripts/release/publish-release.sh RELEASE_DIRECTORY [TRANSACTION_FILE] \
     --sequence-reservation /secure/state/sequence-reservation-v1.json
   ```

   Restore the captured target with
   `publish-release.sh --rollback TRANSACTION_FILE`. Omitting
   `TRANSACTION_FILE` uses
   `$XDG_STATE_HOME/agent-fleet/android-releases/VERSION.json`, or
   `~/.local/state/agent-fleet/android-releases/VERSION.json`. This mode-`0600`
   user-owned receipt is authoritative and must stay outside disposable repo
   build output. The release report contains only a redacted status copy.
   Receipts bind the publisher channel through a destination digest and never
   contain the destination or credentials.
7. For a remote primary use `user@gaming-desktop:/srv/agent-fleet`. A configured
   fallback is never selected automatically: distinct stores cannot safely
   reserve one app/runtime sequence. After primary failure, recover and audit
   its state, then explicitly configure the fallback as the sole destination
   only if it uses the same sequencer/shared filesystem. Independent roots are
   not a supported high-availability pair.
   The local path is the filesystem root seen by the HTTP backend after any
   reverse-proxy mount prefix is removed. For the current Tailscale Serve
   `/agent-fleet` proxy, publish to
   `/home/sapir_cz/.local/share/agent-fleet/public`, not a nested
   `public/agent-fleet` directory. The publisher writes the permanent-ID lane
   under `fleet/latest`; the existing top-level `latest` remains the
   `com.termux` legacy lane. Verify both `manifest.json` and the APK URL
   through the externally served URL before announcing the release. When the
   primary publisher is local and the controller cannot hairpin through its
   own Tailscale address, the release task retries through `127.0.0.1` with the
   configured HTTPS hostname supplied by curl `--resolve`. Certificate and SNI
   validation remain enabled; insecure TLS flags are forbidden.
   When that existing Tailscale endpoint forwards TLS to a local reverse proxy,
   `AGENT_FLEET_RELEASE_HTTPS_CONNECT_TO=192.168.31.207:9444` can select the
   configured local backend for the fallback. Only canonical private IPv4 or
   loopback addresses and valid ports are accepted. Curl changes the socket
   destination while retaining the public HTTPS URL, SNI, certificate
   verification and HTTP Host. This does not change the phone's update URL,
   publish to a different lane, follow redirects, or change a service binding.
8. Put the primary/fallback app and runtime manifest URLs plus their approved
   artifact origins in a strict `client-policy-v1` file. Pass it to
   `wtmux-pairing prepare-artifacts --client-policy FILE`; pairing installs it
   on Android. Update sources are no longer entered manually in the app.

## Transaction and sequence layout

The publication base is the common parent of `fleet/` and `runtime/`. One
bounded directory-descriptor lock covers both lanes. The current app pointer is
written as `fleet/latest -> activations/<32-hex-id> -> ../releases/VERSION`;
existing `fleet/latest -> releases/VERSION` pointers remain readable for
migration and rollback. Staging uses the same locally generated 128-bit ID and
is accepted only at `fleet/releases/.staging-VERSION-ID`.

Before switching `latest`, the store independently rehashes the exact proved
app tree, verifies the current pinned Ed25519 runtime envelope, and creates the
immutable claim `sequence-claims/SEQUENCE.json` with `O_EXCL`. Its canonical
record binds the component, numeric sequence, reservation token, version,
candidate manifest hash, source commit, candidate-proof hash, and reservation
hash. `sequence-high-water-v1.json` binds the newest sequence, component, token,
and claim hash. Claims are never deleted or reclaimed: a failure or rollback
after claiming permanently burns that number. A missing high-water bootstraps
from the current verified app, signed runtime sequence, and immutable deployed
floor `1096`. This floor records already-issued production history; it is not
an evergreen alias for the current app version. An orphan claim left by a crash
prevents a different retry from claiming the same sequence.

The Android app forward-publication path is enabled and performs this claim
under the base lock. There is deliberately no runtime claim command in this
repository yet. Do not publish output from `build-runtime-hotfix.sh` until the
runtime publisher can independently verify the signed envelope at the store
and use this exact shared claim/high-water namespace.

Remote SSH and SCP are batch-only with bounded connect, keepalive, command, and
transfer deadlines. Served verification accepts only canonical credential-free
standard-port HTTPS at the configured origin/path, follows no redirects, limits
the manifest to 32 KiB, requires exactly two APKs of at most 300 MiB each, and
uses one total deadline. If candidate verification fails after switching, the
orchestrator rolls back and externally verifies the exact recorded previous
manifest and both APKs (or the recorded absence). Rollback or restore-
verification uncertainty exits with reserved status `75`.

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
