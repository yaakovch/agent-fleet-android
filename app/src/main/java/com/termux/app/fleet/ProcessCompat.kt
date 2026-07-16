package com.termux.app.fleet

import android.os.Build
import java.util.concurrent.TimeUnit

internal fun Process.isAliveCompat(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return isAlive
    return try {
        exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }
}

internal fun Process.waitForCompat(timeout: Long, unit: TimeUnit): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return waitFor(timeout, unit)
    val timeoutNanos = unit.toNanos(timeout).coerceAtLeast(0)
    val started = System.nanoTime()
    while (isAliveCompat()) {
        if (System.nanoTime() - started >= timeoutNanos) return false
        Thread.sleep(25)
    }
    return true
}

internal fun Process.destroyForciblyCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        destroyForcibly()
    } else {
        destroy()
    }
}
