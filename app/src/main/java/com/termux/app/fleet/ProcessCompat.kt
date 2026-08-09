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

internal fun Process.closePipesCompat() {
    runCatching { outputStream.close() }
    runCatching { inputStream.close() }
    runCatching { errorStream.close() }
}

internal fun Process.terminateAndReapCompat(
    gracefulWaitMillis: Long = 200,
    forcedWaitSeconds: Long = 2
) {
    var interrupted = false
    fun waitForExit(value: Long, unit: TimeUnit): Boolean = try {
        waitForCompat(value, unit)
    } catch (_: InterruptedException) {
        interrupted = true
        false
    }

    runCatching { destroy() }
    if (isAliveCompat() && gracefulWaitMillis > 0) {
        waitForExit(gracefulWaitMillis, TimeUnit.MILLISECONDS)
    }
    if (isAliveCompat()) runCatching { destroyForciblyCompat() }
    closePipesCompat()
    if (isAliveCompat() && forcedWaitSeconds > 0) {
        waitForExit(forcedWaitSeconds, TimeUnit.SECONDS)
    }
    if (interrupted) Thread.currentThread().interrupt()
}
