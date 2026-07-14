# Security

## Reporting

Do not open a public issue containing credentials, private hostnames, terminal
content, pairing invitations, or logs with personal paths. Report sensitive
issues privately to the repository owner.

## Alpha security boundaries

- Releases use a private signing key kept outside this public repository.
- Public CI artifacts are unsigned and are never offered as phone updates.
- Updates require a trusted fleet transport plus manifest, SHA-256, version,
  and signing-certificate verification followed by Android user confirmation.
- The APK-authenticated offline runtime is built from a pinned Termux package
  lock and deterministic wtmux archive. Repair verifies every asset, installs
  only missing/below-floor packages, and never evaluates package metadata as
  shell input.
- Runtime hotfixes use a separate offline Ed25519 key. The APK pins public key
  IDs and enforces canonical manifests, monotonic sequence, HTTPS policy
  origins, size/hash, app/protocol compatibility, doctor gating, and rollback.
- Pairing installs update endpoints only as a strict mode-0600 data policy;
  credentials, redirects, userinfo URLs, HTTP, unknown fields, and policy
  downgrade are rejected.
- Fleet mutations are typed, size-bounded, revision-checked, and idempotent.
- The app exposes no arbitrary remote command API.
- Host profile aliases resolve through untracked local configuration; synced
  data contains no credential paths or secrets.
- Pairing invitations are single-use and short-lived and still require approval
  on an existing controller.

The internal package ID remains `com.termux`, so APKs signed by another source
cannot update this app. Never mix this private build with F-Droid or upstream
Termux plugins.
