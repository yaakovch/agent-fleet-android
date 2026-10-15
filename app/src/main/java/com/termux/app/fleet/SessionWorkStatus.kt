package com.termux.app.fleet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.time.Instant

val SESSION_WORK_STATES = setOf("working", "waiting", "attention", "stopped", "unknown")
data class SessionWorkStatus(val state: String, val observedAt: String)
data class SessionWorkPresentation(val state: String, val label: String, val symbol: String)

fun sessionWorkPresentation(observation: SessionWorkStatus?, available: Boolean = true, now: Long = System.currentTimeMillis()): SessionWorkPresentation {
    val stamp = observation?.let { runCatching { Instant.parse(it.observedAt).toEpochMilli() }.getOrNull() }
    val state = when {
        !available -> "offline"
        observation != null && observation.state in SESSION_WORK_STATES && stamp != null && now - stamp in -2_000L until 10_000L -> observation.state
        else -> "unknown"
    }
    return when (state) {
        "working" -> SessionWorkPresentation(state, "Working", "↻")
        "waiting" -> SessionWorkPresentation(state, "Waiting for input", "○")
        "attention" -> SessionWorkPresentation(state, "Needs attention", "!")
        "stopped" -> SessionWorkPresentation(state, "Stopped", "■")
        "offline" -> SessionWorkPresentation(state, "Offline", "×")
        else -> SessionWorkPresentation("unknown", "Unknown", "?")
    }
}

@Composable
fun rememberSessionWorkPresentation(observation: SessionWorkStatus?, available: Boolean, active: Boolean = true): SessionWorkPresentation {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(observation, foreground, active) {
        now = System.currentTimeMillis()
        while (foreground && active && observation != null) { delay(1_000); now = System.currentTimeMillis() }
    }
    return sessionWorkPresentation(observation, available, now)
}
