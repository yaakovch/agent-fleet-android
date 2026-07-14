package com.termux.app.fleet

import java.io.File

/** Enables Termux's exec compatibility layer for app-owned child processes. */
internal fun enableTermuxExec(environment: MutableMap<String, String>, prefix: File) {
    val library = File(prefix, "lib/libtermux-exec.so")
    if (library.isFile) environment["LD_PRELOAD"] = library.absolutePath
}
