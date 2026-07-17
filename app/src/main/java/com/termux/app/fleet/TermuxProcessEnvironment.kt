package com.termux.app.fleet

import java.io.File

/** Enables Termux's exec compatibility layer for app-owned child processes. */
internal fun enableTermuxExec(environment: MutableMap<String, String>, prefix: File) {
    // App-owned ProcessBuilder children do not pass through TermuxShellUtils.
    // Keep wtmux's platform detection on the Termux/OpenSSH transport path even
    // though the permanent application ID no longer contains "com.termux".
    environment.putIfAbsent("TERMUX_VERSION", "agent-fleet")
    val library = File(prefix, "lib/libtermux-exec.so")
    if (library.isFile) environment["LD_PRELOAD"] = library.absolutePath
}
