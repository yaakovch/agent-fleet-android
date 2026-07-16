# Permanent Android identity and terminal runtime

Agent Fleet now owns `com.yaakovch.fleet`. Its fixed terminal prefix is
`/data/data/com.yaakovch.fleet/files/usr`; every executable and library in the
bootstrap and offline package set must be built for that exact prefix. The
`com.termux` Java source namespace is an internal implementation detail and does
not grant compatibility with binaries built for official Termux.

The custom package source and immutable artifacts are public at
`https://github.com/yaakovch/agent-fleet-termux-packages`. Builds pin the exact
Termux-packages commit and builder image, and publish arm64 production plus
x86_64 emulator bundles with their package lock, SBOM, source/license metadata,
and checksums. Package versions are refreshed only as part of a planned Agent
Fleet app release.

## Two installable lanes

- `com.yaakovch.fleet`: permanent daily-driver app, published below
  `agent-fleet/fleet/latest`.
- `com.termux`: temporary `Agent Fleet Legacy` bridge, published through the
  existing top-level `agent-fleet/latest` lane.

Both use the existing Agent Fleet signing certificate. The legacy lane keeps
the old prefix only for rollback and transfer. The permanent app has no shared
UID, exported `RUN_COMMAND`, generic document provider, local shell launcher, or
official Termux plugin coupling. Its internal terminal renderer and PTY service
remain because remote wtmux Terminal view depends on them.

## Transfer and rollback

More → Move Fleet state launches the other same-signed app. The source shows a
confirmation dialog and grants a bounded private archive only to its counterpart.
The archive includes Fleet layouts/recent sessions/appearance, wtmux config and
client policy, the current verified registry release, and bounded regular `.ssh`
files. It excludes the package prefix, arbitrary home files, shell history,
caches, downloads, runtime releases, images, attachments, and transcripts.

The importer verifies every declared path, size, and SHA-256, rejects unknown or
duplicate data, rewrites only the managed app-private prefix and APK update lane,
and activates regular files atomically. The same flow works in reverse. Keep the
signed `.40` artifacts and `.41-legacy` installed or available until at least 90
days and two successful permanent-ID releases have passed.

## Release acceptance

Automated validation uses only the isolated API 36 emulator. Final S23FE checks
are manual: install/update the legacy bridge without data reset, migrate forward,
coinstall official Termux, verify Native and Terminal sessions through reconnect
and window/DeX resize, check the permanent update lane, migrate back, and prove
rollback before returning to `com.yaakovch.fleet`.
