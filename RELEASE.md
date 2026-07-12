# Private Alpha Releases

Public CI builds and tests unsigned APKs. A controller performs release signing
with an offline key, verifies the resulting certificate, and publishes a
versioned APK plus a signed metadata manifest and SHA-256 checksum to
gaming-desktop. Work-m is the documented manual fallback.

The Android client checks only while visible or after an explicit refresh. It
downloads over the existing trusted fleet transport, verifies the manifest,
checksum, version, and pinned certificate, and then opens Android's normal
user-confirmed package installer. Silent installation and signing keys in CI
are forbidden.

