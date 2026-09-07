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
    private val reconnectRequested = AtomicBoolean(false)
    private var networkMonitor: FleetNetworkMonitor? = null
    private var recoveryLoader: FleetRecoveryLoader? = null
    private data class Observer(
        val callback: (FleetLoadState) -> Unit,
        val continuous: Boolean
    )

    private val observers = linkedMapOf<Any, Observer>()
    private val supervisorOwners = ForegroundSupervisorOwners()
    private var contextReference: WeakReference<Context>? = null
    private var state: FleetLoadState = FleetLoadState.Loading

    private val refreshRunnable = Runnable {
        synchronized(this) {
            if (observers.isEmpty()) return@Runnable
        }
        refresh()
    }

    fun observe(context: Context, owner: Any, observer: (FleetLoadState) -> Unit) {
        observe(context, owner, continuous = true, observer)
    }

    /** Observe published state without starting the three-second foreground loop. */
    fun observePassive(context: Context, owner: Any, observer: (FleetLoadState) -> Unit) {
        observe(context, owner, continuous = false, observer)
    }

    private fun observe(
        context: Context,
        owner: Any,
        continuous: Boolean,
        observer: (FleetLoadState) -> Unit
    ) {
        val current: FleetLoadState
        var lifecycleChange: Boolean? = null
        synchronized(this) {
            contextReference = WeakReference(context.applicationContext)
            observers[owner] = Observer(observer, continuous)
            lifecycleChange = supervisorOwners.set(owner, continuous)
            current = state
            if (continuous) {
                main.removeCallbacks(refreshRunnable)
                main.post(refreshRunnable)
            }
        }
        lifecycleChange?.let { enabled ->
            FleetControlSupervisor.setForeground(context.applicationContext, enabled)
            if (enabled) {
                if (networkMonitor == null) networkMonitor = FleetNetworkMonitor(context.applicationContext, main) {
                    refresh(reconnect = true)
                }
                networkMonitor?.start()
            }
        }
        main.post { observer(current) }
    }

    fun removeObserver(owner: Any) {
        var lifecycleChange: Boolean? = null
        var context: Context? = null
        synchronized(this) {
            observers.remove(owner)
            lifecycleChange = supervisorOwners.remove(owner)
            context = contextReference?.get()
            if (observers.values.none { it.continuous }) main.removeCallbacks(refreshRunnable)
        }
        if (lifecycleChange == false) {
            networkMonitor?.stop()
            context?.let { FleetControlSupervisor.setForeground(it, false) }
        }
    }

    fun refresh(showLoading: Boolean = false, reconnect: Boolean = false) {
        if (reconnect) reconnectRequested.set(true)
        val active: Boolean
        val context: Context
        synchronized(this) {
            active = observers.isNotEmpty()
            context = contextReference?.get() ?: return
        }
        if (active && FleetRuntimePreparation.active) {
            if (latestSnapshot() == null) publishState(FleetLoadState.Loading)
            main.removeCallbacks(refreshRunnable)
            main.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
            return
        }
        if (!active || !refreshing.compareAndSet(false, true)) return
        if (showLoading && state !is FleetLoadState.Ready) publishState(FleetLoadState.Loading)
        executor.execute {
            val loader = recoveryLoader ?: FleetRecoveryLoader(
                fetch = { FleetRuntime(context).loadSnapshot() },
                repairConfiguration = {
                    val journal = AgentFleetDiagnosticJournal(context)
                    try {
                        EmbeddedRuntimeManager(context).repairFleetConfiguration()
                        journal.record("fleet.configuration_repair", "success")
                    } catch (error: Exception) {
                        journal.record("fleet.configuration_repair", "failure", code = "REGISTRY_INVALID",
                            message = error.message.orEmpty())
                        throw error
                    }
                },
                onRepair = {
                    main.post {
                        if (latestSnapshot() == null) publishState(FleetLoadState.Unavailable(
                            "Restoring your saved fleet information…", "REGISTRY_INVALID", recovering = true
                        ))
                    }
                }
            ).also { recoveryLoader = it }
            if (reconnectRequested.getAndSet(false)) {
                loader.retryNow()
                FleetControlSupervisor.restartForConfigurationChange()
            }
            val result = try {
                val fresh = loader.load()
                FleetLoadState.Ready(reconcileFleetSnapshot(latestSnapshot(), fresh))
            } catch (error: Exception) {
                val previous = latestSnapshot()
                val code = fleetFailureCode(error)
                if (previous != null) {
                    FleetLoadState.Ready(staleFleetSnapshot(previous, code))
                } else {
                    FleetLoadState.Unavailable(error.message ?: "Fleet refresh failed.", code)
                }
            }
            refreshing.set(false)
            main.post {
                if (!FleetRuntimePreparation.active) publishState(result)
                synchronized(this) {
                    if (observers.values.any { it.continuous }) {
                        main.removeCallbacks(refreshRunnable)
                        main.postDelayed(refreshRunnable, if (reconnectRequested.get()) 0 else REFRESH_INTERVAL_MS)
                    }
                }
            }
        }
    }

    fun publish(snapshot: FleetSnapshot) = publishState(FleetLoadState.Ready(snapshot))

    fun redactTitles() {
        val redacted = synchronized(this) {
            val snapshot = (state as? FleetLoadState.Ready)?.snapshot ?: return
            snapshot.copy(
                presentationRevision = null,
                sessions = snapshot.sessions.map { it.copy(title = "", nameMode = "automatic") }
            )
        }
        publishState(FleetLoadState.Ready(redacted))
    }

    @Synchronized
    fun latestSnapshot(): FleetSnapshot? = (state as? FleetLoadState.Ready)?.snapshot

    private fun publishState(value: FleetLoadState) {
        val callbacks: List<(FleetLoadState) -> Unit>
        synchronized(this) {
            state = value
            callbacks = observers.values.map(Observer::callback)
        }
        callbacks.forEach { it(value) }
    }
}
