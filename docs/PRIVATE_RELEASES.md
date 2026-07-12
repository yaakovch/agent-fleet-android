# Private releases

Public CI produces unsigned review artifacts. Daily-driver APKs are signed only
on a controller with the private Agent Fleet key.

1. Create the key once with two distinct offline backup destinations:
   `scripts/release/init-signing-key.sh /media/backup-a /media/backup-b`.
   Keep its password separately from all three keystore copies.
2. Build with a monotonically increasing version code and the HTTPS directory
   that will host the APK:
   `scripts/release/build-signed-release.sh 0.118.4-agentfleet.1 1003 https://host.example/agent-fleet/latest`.
3. Verify with `scripts/release/verify-release.sh dist/0.118.4-agentfleet.1`.
4. Set `AGENT_FLEET_PUBLISH_PRIMARY=user@gaming-desktop:/srv/agent-fleet`
   and optionally `AGENT_FLEET_PUBLISH_FALLBACK=user@work-m:/srv/agent-fleet`,
   then run `scripts/release/publish-release.sh DIST_DIRECTORY`.
5. In the app, open More → App updates → Source and save the HTTPS URL ending
   in `manifest.json`.

The app refuses redirects, HTTP, oversized files, wrong checksums, mismatched
package/version metadata, and any APK not signed by the certificate already
installed on the phone. Android still presents its normal installer confirmation.
