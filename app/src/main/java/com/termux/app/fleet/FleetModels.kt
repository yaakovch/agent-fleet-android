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
    val locationKind: String = "project",
    val nameMode: String = "automatic"
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

data class FleetRepositoryEntry(
    val name: String,
    val relativePath: String,
    val kind: String,
    val size: Long?,
    val modifiedAt: String,
    val hidden: Boolean,
    val isLink: Boolean
)

data class FleetRepositoryPage(
    val rootName: String,
    val relativePath: String,
    val parentPath: String?,
    val entries: List<FleetRepositoryEntry>,
    val nextCursor: String?,
    val truncated: Boolean
)

data class FleetModelEffortOption(val id: String, val label: String)
data class FleetModelOption(
    val id: String,
    val label: String,
    val description: String,
    val isDefault: Boolean,
    val efforts: List<FleetModelEffortOption>,
    val defaultEffort: String
)
data class FleetModelSelection(
    val modelId: String,
    val modelLabel: String,
    val effortId: String,
    val effortLabel: String
)
data class FleetPendingModelChange(
    val operationId: String,
    val modelId: String,
    val effortId: String,
    val custom: Boolean,
    val requestedAt: String,
    val expiresAt: String
)
data class FleetModelControlState(
    val sessionId: String,
    val configRevision: String,
    val tool: String,
    val status: String,
    val selected: FleetModelSelection,
    val effective: FleetModelSelection?,
    val pending: FleetPendingModelChange?,
    val catalog: List<FleetModelOption>?,
    val customAllowed: Boolean,
    val detail: String
)

data class FleetDownloadState(
    val name: String,
    val relativePath: String,
    val status: String,
    val received: Long,
    val total: Long,
    val path: String? = null,
    val message: String
)

class FleetDownloadCancellation {
    @Volatile private var cancelled = false
    @Volatile private var process: Process? = null

    fun cancel() {
        cancelled = true
        process?.destroy()
    }

    internal fun bind(value: Process) {
        process = value
        if (cancelled) value.destroy()
    }

    internal fun isCancelled(): Boolean = cancelled
    fun isCancelledForUi(): Boolean = cancelled
}

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
    val limits: List<FleetLimit> = emptyList(),
    val presentationRevision: String? = null
)

data class FleetSessionIdentity(val primary: String, val secondary: String, val stableName: String)

internal const val MAX_INHERITED_SESSION_TITLE_CHARS = 48

internal fun inheritedSessionTitle(value: String): String {
    val title = value.trim()
    if (title.codePointCount(0, title.length) <= MAX_INHERITED_SESSION_TITLE_CHARS) return title
    val available = MAX_INHERITED_SESSION_TITLE_CHARS - 1
    val prefix = title.substring(0, title.offsetByCodePoints(0, available)).trimEnd()
    val wordBoundary = prefix.lastIndexOf(' ').takeIf { it >= available * 2 / 3 }
    return prefix.take(wordBoundary ?: prefix.length).trimEnd() + "…"
}

fun sessionIdentityPresentation(session: FleetSession): FleetSessionIdentity {
    val automatic = session.nameMode != "manual"
    val primary = if (automatic && session.title.isNotBlank()) inheritedSessionTitle(session.title) else session.name
    val secondary = if (automatic && session.title.isNotBlank()) {
        listOf(session.name, session.hostId, session.project).filter(String::isNotBlank).joinToString(" · ")
    } else {
        listOf(session.hostId, session.project).filter(String::isNotBlank).joinToString(" · ")
    }
    return FleetSessionIdentity(primary, secondary, session.name)
}

data class FleetDoctorCheck(
    val id: String,
    val status: String,
    val summary: String,
    val detail: String
)

data class FleetDoctorResult(
    val hostId: String,
    val checkedAt: String,
    val status: String,
    val checks: List<FleetDoctorCheck>
)

sealed interface FleetLoadState {
    object Loading : FleetLoadState
    data class Ready(val snapshot: FleetSnapshot) : FleetLoadState
    data class Unavailable(val reason: String) : FleetLoadState
}
