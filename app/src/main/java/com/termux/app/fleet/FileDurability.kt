package com.termux.app.fleet

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Persists directory-entry changes after an atomic rename or delete.
 *
 * Robolectric's ShadowLinux returns EIO for opening any directory, even though
 * the same Os.open/fsync sequence is supported by Android's Linux runtime. Keep
 * that JVM-only limitation from disabling transaction tests without weakening
 * failure handling on an Android build.
 */
internal fun fsyncDirectoryCompat(directory: File) {
    val descriptor = try {
        Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    } catch (error: ErrnoException) {
        if (Build.FINGERPRINT == "robolectric" && error.errno == OsConstants.EIO) return
        throw error
    }
    try {
        Os.fsync(descriptor)
    } finally {
        Os.close(descriptor)
    }
}

/**
 * Uses the kernel rename primitive on Android. Robolectric's Os.rename shadow
 * reports success without moving the host file, so JVM transaction tests use
 * the host File implementation for the same-directory rename.
 */
internal fun atomicRenameCompat(source: File, destination: File) {
    if (Build.FINGERPRINT == "robolectric") {
        check(source.renameTo(destination)) {
            "Unable to atomically replace ${destination.name}"
        }
        return
    }
    Os.rename(source.absolutePath, destination.absolutePath)
}
