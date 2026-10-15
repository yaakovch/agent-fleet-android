package com.termux.app

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.termux.app.fleet.FleetLimitWindow
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun quotaWindowLabel(minutes: Int): String = when (minutes) {
    300 -> "5h"
    10080 -> "Weekly"
    else -> if (minutes % 60 == 0) "${minutes / 60}h" else "${minutes}m"
}

internal fun quotaResetLabel(
    window: FleetLimitWindow,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): String {
    val label = quotaWindowLabel(window.windowMinutes)
    val reset = runCatching { OffsetDateTime.parse(window.resetsAt).toInstant() }.getOrNull()
        ?: return "$label reset time unknown"
    val resetMillis = runCatching { reset.toEpochMilli() }.getOrNull() ?: return "$label reset time unknown"
    val diff = resetMillis - nowMillis
    val remaining = when {
        diff <= 0 -> "reset due"
        diff < 60_000 -> "resets in <1m"
        diff < 3_600_000 -> "resets in ${diff / 60_000}m"
        diff < 86_400_000 -> "resets in ${diff / 3_600_000}h ${(diff / 60_000) % 60}m"
        else -> "resets in ${diff / 86_400_000}d ${(diff / 3_600_000) % 24}h"
    }
    val localTime = DateTimeFormatter.ofPattern("EEE, d MMM HH:mm", locale).withZone(zone).format(reset)
    return "$label $remaining · $localTime"
}

/** UI clock only: entering foreground updates immediately; no hidden/background timer. */
internal class QuotaResetTicker(
    private val lifecycle: Lifecycle,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val changed: (Long) -> Unit
) : DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val now = nowMillis()
            changed(now)
            handler.postDelayed(this, 60_000L - now % 60_000L)
        }
    }
    init { lifecycle.addObserver(this) }
    override fun onStart(owner: LifecycleOwner) { handler.removeCallbacks(tick); tick.run() }
    override fun onStop(owner: LifecycleOwner) { handler.removeCallbacks(tick) }
    fun close() { handler.removeCallbacks(tick); lifecycle.removeObserver(this) }
}

@Composable
internal fun rememberQuotaNowMillis(): Long {
    val owner = LocalLifecycleOwner.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    DisposableEffect(owner) {
        val ticker = QuotaResetTicker(owner.lifecycle) { now = it }
        onDispose { ticker.close() }
    }
    return now
}
