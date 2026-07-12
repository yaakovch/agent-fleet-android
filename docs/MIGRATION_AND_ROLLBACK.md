# Migration and rollback

Do not uninstall the S23FE's F-Droid Termux until every gate below passes.

1. Copy `scripts/migration/termux-backup.sh` into the existing Termux and run it.
   It writes a complete encrypted `home` + `usr` archive and a portable encrypted
   `home` archive under `~/storage/downloads/AgentFleetBackup`.
2. From the controller, pull that directory and every installed Termux-family APK
   with `scripts/migration/capture-device-backup.sh SERIAL PHONE_DIR LOCAL_DIR`.
3. Set `AGENT_FLEET_BACKUP_PASSWORD` and run
   `scripts/migration/verify-backup.sh BACKUP_DIRECTORY` on both the Downloads
   copy and controller copy. Their `SHA256SUMS` files must match.
4. Run `scripts/migration/rehearse-backup-rollback.sh`, then restore a sanitized
   copy of the real archive on the Android 16 emulator. Confirm shell, packages,
   SSH, wtmux configuration, and file counts.
5. Reinstall the captured F-Droid APK on the emulator and restore the complete
   archive with `termux-restore.sh BACKUP_DIRECTORY complete`. Compare the state
   to the pre-migration manifest.

Only then may the phone cutover uninstall all F-Droid-signed Termux apps, install
the private Agent Fleet APK, and restore. Rollback reverses that sequence using
the captured F-Droid APKs and complete archive. Stop and roll back for data loss,
credential exposure, unusable terminal/SSH, or an update that cannot install.
