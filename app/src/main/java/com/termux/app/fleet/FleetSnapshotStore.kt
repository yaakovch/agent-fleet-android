package com.termux.app.fleet

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One foreground-only snapshot owner shared by the launcher, limits, and native
 * session surfaces. A bridge refresh is never left running just because the app
 * moved to the background.
 */
object FleetSnapshotStore {
    private const val REFRESH_INTERVAL_MS = 3_000L

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val refreshing = AtomicBoolean(false)
    private val observers = linkedMapOf<Any, (FleetLoadState) -> Unit>()
    private var contextReference: WeakReference<Context>? = null
    private var state: FleetLoadState = FleetLoadState.Loading

    private val refreshRunnable = Runnable {
        synchronized(this) {
            if (observers.isEmpty()) return@Runnable
        }
        refresh()
    }

    fun observe(context: Context, owner: Any, observer: (FleetLoadState) -> Unit) {
        val current: FleetLoadState
        synchronized(this) {
            contextReference = WeakReference(context.applicationContext)
            observers[owner] = observer
            current = state
            main.removeCallbacks(refreshRunnable)
            main.post(refreshRunnable)
        }
        main.post { observer(current) }
    }

    fun removeObserver(owner: Any) {
        synchronized(this) {
            observers.remove(owner)
            if (observers.isEmpty()) main.removeCallbacks(refreshRunnable)
        }
    }

    fun refresh(showLoading: Boolean = false) {
        val active: Boolean
        val context: Context
        synchronized(this) {
            active = observers.isNotEmpty()
            context = contextReference?.get() ?: return
        }
        if (!active || !refreshing.compareAndSet(false, true)) return
        if (showLoading && state !is FleetLoadState.Ready) publishState(FleetLoadState.Loading)
        executor.execute {
            val result = try {
                FleetLoadState.Ready(FleetRuntime(context).loadSnapshot())
            } catch (error: Exception) {
                FleetLoadState.Unavailable(error.message ?: "Fleet refresh failed.")
            }
            refreshing.set(false)
            main.post {
                publishState(result)
                synchronized(this) {
                    if (observers.isNotEmpty()) {
                        main.removeCallbacks(refreshRunnable)
                        main.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
                    }
                }
            }
        }
    }

    fun publish(snapshot: FleetSnapshot) = publishState(FleetLoadState.Ready(snapshot))

    @Synchronized
    fun latestSnapshot(): FleetSnapshot? = (state as? FleetLoadState.Ready)?.snapshot

    private fun publishState(value: FleetLoadState) {
        val callbacks: List<(FleetLoadState) -> Unit>
        synchronized(this) {
            state = value
            callbacks = observers.values.toList()
        }
        callbacks.forEach { it(value) }
    }
}
