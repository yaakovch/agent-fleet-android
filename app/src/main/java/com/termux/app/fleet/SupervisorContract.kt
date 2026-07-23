package com.termux.app.fleet

internal const val SUPERVISOR_MAX_FRAME_BYTES = 256 * 1024
internal const val SUPERVISOR_MAX_QUEUED_CONTROL = 16
internal const val SUPERVISOR_MAX_IN_FLIGHT_CONTROL = 1
internal const val SUPERVISOR_REQUEST_DEADLINE_MS = 20_000L
internal const val SUPERVISOR_HEARTBEAT_TIMEOUT_MS = 30_000L
internal val SUPERVISOR_RECONNECT_DELAYS_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)

internal data class SupervisorState(
    val phase: String = "stopped",
    val foreground: Boolean = false,
    val connectionGeneration: Int = 0,
    val controlProcessCount: Int = 0,
    val health: String = "stopped",
    val lastError: String = ""
)

internal data class SupervisorAction(val type: String, val channel: String = "")

internal fun reduceSupervisorState(state: SupervisorState, action: SupervisorAction): SupervisorState = when (action.type) {
    "foreground-start" -> state.startConnection()
    "foreground-resume" -> if (state.phase == "stopped") state.startConnection() else state.copy(foreground = true)
    "ready" -> state.copy(phase = "ready", controlProcessCount = 1, health = "healthy", lastError = "")
    "heartbeat-missed" -> state.copy(phase = "degraded", health = "degraded", lastError = "heartbeat_late")
    "heartbeat-expired" -> state.failed("heartbeat_timeout")
    "process-exited" -> state.failed("process_exit")
    "request-timed-out" -> state.failed("request_timeout")
    "retry-elapsed" -> if (state.foreground) state.startConnection() else state
    "background-retain" -> state.copy(foreground = false)
    "background-stop" -> state.copy(
        phase = "shutting-down",
        foreground = false,
        health = "degraded",
        lastError = ""
    )
    "shutdown-complete" -> state.copy(
        phase = "stopped",
        controlProcessCount = 0,
        health = "stopped",
        lastError = ""
    )
    "channel-failed" -> if (action.channel == "control") state.failed("control_failure") else state
    "queue-saturated" -> state.copy(lastError = "backpressure")
    else -> throw IllegalArgumentException("Unknown supervisor action")
}

private fun SupervisorState.startConnection() = SupervisorState(
    phase = "initializing",
    foreground = true,
    connectionGeneration = connectionGeneration + 1,
    controlProcessCount = 1,
    health = "connecting",
    lastError = ""
)

private fun SupervisorState.failed(error: String) = copy(
    phase = "backoff",
    controlProcessCount = 0,
    health = "unhealthy",
    lastError = error
)

internal class ForegroundSupervisorOwners {
    private val continuous = mutableSetOf<Any>()

    @Synchronized
    fun set(owner: Any, enabled: Boolean): Boolean? {
        val wasActive = continuous.isNotEmpty()
        if (enabled) continuous.add(owner) else continuous.remove(owner)
        val active = continuous.isNotEmpty()
        return when {
            !wasActive && active -> true
            wasActive && !active -> false
            else -> null
        }
    }

    @Synchronized
    fun remove(owner: Any): Boolean? = set(owner, false)

    @Synchronized
    fun isActive(): Boolean = continuous.isNotEmpty()
}
