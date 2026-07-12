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
- Fleet mutations are typed, size-bounded, revision-checked, and idempotent.
- The app exposes no arbitrary remote command API.
- Host profile aliases resolve through untracked local configuration; synced
  data contains no credential paths or secrets.
- Pairing invitations are single-use and short-lived and still require approval
  on an existing controller.

The internal package ID remains `com.termux`, so APKs signed by another source
cannot update this app. Never mix this private build with F-Droid or upstream
Termux plugins.

