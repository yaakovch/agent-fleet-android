# S23FE Migration and Rollback

The installed S23FE Termux and Termux:Widget packages are F-Droid-signed. Agent
Fleet uses a different private key and therefore requires a one-time uninstall.

## Hard preconditions

Do not uninstall either package until all conditions pass:

1. A complete archive of `/data/data/com.termux/files` exists on the controller
   PC and in Android Downloads and both SHA-256 checksums match.
2. A portable archive, package inventory, permissions/settings manifest, and
   installed APK copies with certificate fingerprints exist in both locations.
3. A sanitized archive has completed an emulator restore, Agent Fleet migration,
   F-Droid rollback, and file/package comparison.
4. A privately signed Agent Fleet APK, matching rollback instructions, and the
   original F-Droid APKs are available offline.

## Cutover

After the preconditions pass, stop Termux sessions, uninstall Widget and
Termux, install Agent Fleet, restore the archive from inside the new Termux
environment, validate package state, repair wtmux, and run all doctor checks.

## Rollback

Stop the fork, export any newer user files, uninstall it, reinstall the captured
F-Droid Termux and Widget APKs, restore the verified full archive, restore the
recorded permissions, and compare the manifest before resuming normal use.

No migration command in this repository performs the uninstall automatically.

