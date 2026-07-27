package com.termux.app.fleet

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * Binds an embedded Native controller to the owning activity lifecycle.
 *
 * Compose can retain a workspace after its activity is stopped, so composition
 * disposal alone is not a foreground signal. The binding keeps no controller
 * reference after detach and makes repeated stop/detach callbacks harmless.
 */
internal class NativeSessionLifecycleBinding : DefaultLifecycleObserver {
    private data class Target(
        val start: () -> Unit,
        val stop: () -> Unit,
        val close: () -> Unit
    )

    private var target: Target? = null
    private var foreground = false

    fun attach(
        lifecycleState: Lifecycle.State,
        start: () -> Unit,
        stop: () -> Unit,
        close: () -> Unit
    ) {
        target?.close?.invoke()
        target = Target(start, stop, close)
        if (foreground || lifecycleState.isAtLeast(Lifecycle.State.STARTED)) {
            foreground = true
            target?.start?.invoke()
        }
    }

    fun detach() {
        target?.close?.invoke()
        target = null
        foreground = false
    }

    override fun onStart(owner: LifecycleOwner) {
        foreground = true
        target?.start?.invoke()
    }

    override fun onStop(owner: LifecycleOwner) {
        if (!foreground) return
        foreground = false
        target?.stop?.invoke()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        detach()
    }
}
