package com.termux.app.fleet

data class FleetHost(
    val id: String,
    val name: String,
    val status: String,
    val platform: String,
    val lastSeenAt: String?,
    val capabilities: Set<String>
)

data class FleetSession(
    val id: String,
    val hostId: String,
    val internalName: String,
    val name: String,
    val title: String,
    val project: String,
    val tool: String,
    val backend: String,
    val activity: String,
    val attached: Boolean,
    val updatedAt: String?,
    val pendingScheduleCount: Int,
    val projectPath: String = "",
    val locationKind: String = "project"
)

data class FleetDirectoryEntry(val name: String, val path: String)
data class FleetDirectoryShortcut(val id: String, val label: String, val path: String)
data class FleetDirectoryListing(
    val backend: String,
    val path: String,
    val parentPath: String?,
    val entries: List<FleetDirectoryEntry>,
    val shortcuts: List<FleetDirectoryShortcut>,
    val truncated: Boolean
)

data class FleetSchedule(
    val id: String,
    val hostId: String,
    val sessionId: String,
    val deliverAt: String,
    val status: String
)

data class FleetAttention(
    val id: String,
    val hostId: String,
    val sessionId: String,
    val agent: String,
    val resetAt: String?,
    val state: String
)

data class FleetLimitWindow(
    val usedPercent: Double,
    val remainingPercent: Double,
    val resetsAt: String,
    val windowMinutes: Int
)

data class FleetLimit(
    val id: String,
    val hostId: String,
    val provider: String,
    val profileAlias: String,
    val status: String,
    val primary: FleetLimitWindow?,
    val secondary: FleetLimitWindow?,
    val updatedAt: String
)

data class FleetSnapshot(
    val revision: String,
    val generatedAt: String,
    val hosts: List<FleetHost>,
    val sessions: List<FleetSession>,
    val schedules: List<FleetSchedule>,
    val attention: List<FleetAttention>,
    val limits: List<FleetLimit> = emptyList()
)

sealed interface FleetLoadState {
    object Loading : FleetLoadState
    data class Ready(val snapshot: FleetSnapshot) : FleetLoadState
    data class Unavailable(val reason: String) : FleetLoadState
}
