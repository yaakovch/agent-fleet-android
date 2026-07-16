package com.termux.app.fleet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class WorkspacePresentationMode { Auto, Phone, Desktop }
enum class WorkspaceDirection { Row, Column }
enum class WorkspaceViewMode { Native, Terminal }
enum class WorkspacePreset { Single, TwoColumns, TwoRows, MainSide, Grid }

sealed interface WorkspaceNode { val id: String }

data class WorkspacePane(
    override val id: String,
    val sessionId: String? = null,
    val viewMode: WorkspaceViewMode = WorkspaceViewMode.Native
) : WorkspaceNode

data class WorkspaceSplit(
    override val id: String,
    val direction: WorkspaceDirection,
    val ratio: Float,
    val first: WorkspaceNode,
    val second: WorkspaceNode
) : WorkspaceNode

data class WorkspaceLayout(
    val schemaVersion: Int = 1,
    val root: WorkspaceNode,
    val focusedPaneId: String,
    val sessionMru: List<String> = emptyList()
)

data class AndroidWorkspaceState(
    val layout: WorkspaceLayout,
    val railCollapsed: Boolean = false,
    val railWidthDp: Int = 244,
    val hiddenUnavailableSessionIds: Set<String> = emptySet()
)

fun emptyWorkspaceLayout(): WorkspaceLayout {
    val pane = WorkspacePane(newWorkspaceId("pane"))
    return WorkspaceLayout(root = pane, focusedPaneId = pane.id)
}

fun workspacePanes(node: WorkspaceNode): List<WorkspacePane> = when (node) {
    is WorkspacePane -> listOf(node)
    is WorkspaceSplit -> workspacePanes(node.first) + workspacePanes(node.second)
}

fun isDesktopPresentation(mode: WorkspacePresentationMode, widthDp: Int): Boolean = when (mode) {
    WorkspacePresentationMode.Auto -> widthDp >= 840
    WorkspacePresentationMode.Phone -> false
    WorkspacePresentationMode.Desktop -> true
}

fun isFleetSessionAvailable(snapshot: FleetSnapshot, session: FleetSession): Boolean {
    return snapshot.hosts.any { it.id == session.hostId && it.status == "healthy" }
}

object WorkspaceReducer {
    fun focus(layout: WorkspaceLayout, paneId: String): WorkspaceLayout {
        val pane = workspacePanes(layout.root).firstOrNull { it.id == paneId } ?: return layout
        val mru = pane.sessionId?.let { listOf(it) + layout.sessionMru.filterNot { old -> old == it } }
            ?: layout.sessionMru
        return layout.copy(focusedPaneId = paneId, sessionMru = mru.take(64))
    }

    fun assign(layout: WorkspaceLayout, paneId: String, sessionId: String): WorkspaceLayout {
        if (!safeWorkspaceId(sessionId)) return layout
        val existing = workspacePanes(layout.root).firstOrNull { it.sessionId == sessionId }
        if (existing != null) return focus(layout, existing.id)
        if (workspacePanes(layout.root).none { it.id == paneId }) return layout
        val root = mapNode(layout.root) { pane ->
            if (pane.id == paneId) pane.copy(sessionId = sessionId, viewMode = WorkspaceViewMode.Native) else pane
        }
        return focus(layout.copy(root = root), paneId)
    }

    fun clear(layout: WorkspaceLayout, paneId: String): WorkspaceLayout {
        val root = mapNode(layout.root) { pane -> if (pane.id == paneId) pane.copy(sessionId = null) else pane }
        return layout.copy(root = root)
    }

    fun setView(layout: WorkspaceLayout, paneId: String, viewMode: WorkspaceViewMode): WorkspaceLayout {
        val root = mapNode(layout.root) { pane -> if (pane.id == paneId) pane.copy(viewMode = viewMode) else pane }
        return layout.copy(root = root, focusedPaneId = paneId.takeIf { id -> workspacePanes(root).any { it.id == id } } ?: layout.focusedPaneId)
    }

    fun split(layout: WorkspaceLayout, paneId: String, direction: WorkspaceDirection): WorkspaceLayout {
        if (workspacePanes(layout.root).size >= 4) return layout
        val target = workspacePanes(layout.root).firstOrNull { it.id == paneId } ?: return layout
        val created = WorkspacePane(newWorkspaceId("pane"))
        val replacement = WorkspaceSplit(newWorkspaceId("split"), direction, 0.5f, target, created)
        return layout.copy(root = replaceNode(layout.root, paneId, replacement), focusedPaneId = created.id)
    }

    fun close(layout: WorkspaceLayout, paneId: String): WorkspaceLayout {
        if (workspacePanes(layout.root).size == 1) return clear(layout, paneId)
        val collapsed = removePane(layout.root, paneId) ?: return layout
        val panes = workspacePanes(collapsed)
        val focused = if (panes.any { it.id == layout.focusedPaneId }) layout.focusedPaneId else panes.first().id
        return layout.copy(root = collapsed, focusedPaneId = focused)
    }

    fun resize(layout: WorkspaceLayout, splitId: String, ratio: Float): WorkspaceLayout {
        fun resizeNode(node: WorkspaceNode): WorkspaceNode = when (node) {
            is WorkspacePane -> node
            is WorkspaceSplit -> if (node.id == splitId) node.copy(ratio = ratio.coerceIn(0.2f, 0.8f))
                else node.copy(first = resizeNode(node.first), second = resizeNode(node.second))
        }
        return layout.copy(root = resizeNode(layout.root))
    }

    fun swap(layout: WorkspaceLayout, firstPaneId: String, secondPaneId: String): WorkspaceLayout {
        if (firstPaneId == secondPaneId) return layout
        val panes = workspacePanes(layout.root)
        val first = panes.firstOrNull { it.id == firstPaneId } ?: return layout
        val second = panes.firstOrNull { it.id == secondPaneId } ?: return layout
        val root = mapNode(layout.root) { pane ->
            when (pane.id) {
                firstPaneId -> pane.copy(sessionId = second.sessionId, viewMode = second.viewMode)
                secondPaneId -> pane.copy(sessionId = first.sessionId, viewMode = first.viewMode)
                else -> pane
            }
        }
        return layout.copy(root = root, focusedPaneId = secondPaneId)
    }

    fun preset(layout: WorkspaceLayout, preset: WorkspacePreset): WorkspaceLayout {
        val focused = workspacePanes(layout.root).firstOrNull { it.id == layout.focusedPaneId }?.sessionId
        val sessions = (listOfNotNull(focused) + layout.sessionMru + workspacePanes(layout.root).mapNotNull { it.sessionId })
            .distinct()
        val modes = workspacePanes(layout.root).associate { it.sessionId to it.viewMode }
        val count = when (preset) {
            WorkspacePreset.Single -> 1
            WorkspacePreset.TwoColumns, WorkspacePreset.TwoRows, WorkspacePreset.MainSide -> 2
            WorkspacePreset.Grid -> 4
        }
        val panes = List(count) { index -> WorkspacePane(newWorkspaceId("pane"), sessions.getOrNull(index), modes[sessions.getOrNull(index)] ?: WorkspaceViewMode.Native) }
        val root = when (preset) {
            WorkspacePreset.Single -> panes[0]
            WorkspacePreset.TwoColumns -> WorkspaceSplit(newWorkspaceId("split"), WorkspaceDirection.Row, 0.5f, panes[0], panes[1])
            WorkspacePreset.TwoRows -> WorkspaceSplit(newWorkspaceId("split"), WorkspaceDirection.Column, 0.5f, panes[0], panes[1])
            WorkspacePreset.MainSide -> WorkspaceSplit(newWorkspaceId("split"), WorkspaceDirection.Row, 0.67f, panes[0], panes[1])
            WorkspacePreset.Grid -> WorkspaceSplit(
                newWorkspaceId("split"), WorkspaceDirection.Column, 0.5f,
                WorkspaceSplit(newWorkspaceId("split"), WorkspaceDirection.Row, 0.5f, panes[0], panes[1]),
                WorkspaceSplit(newWorkspaceId("split"), WorkspaceDirection.Row, 0.5f, panes[2], panes[3])
            )
        }
        return WorkspaceLayout(root = root, focusedPaneId = panes.first().id, sessionMru = sessions.take(64))
    }
}

class WorkspacePresentationStore(context: Context) {
    private val preferences = context.getSharedPreferences("agent_fleet_workspace_presentation", Context.MODE_PRIVATE)

    fun load(): WorkspacePresentationMode = runCatching {
        WorkspacePresentationMode.valueOf(preferences.getString("mode", WorkspacePresentationMode.Auto.name).orEmpty())
    }.getOrDefault(WorkspacePresentationMode.Auto)

    fun save(mode: WorkspacePresentationMode) {
        preferences.edit().putString("mode", mode.name).apply()
    }
}

class AndroidWorkspaceStore(context: Context) {
    private val preferences = context.getSharedPreferences("agent_fleet_workspace_v1", Context.MODE_PRIVATE)

    fun load(): AndroidWorkspaceState = runCatching {
        val layout = decodeWorkspaceLayout(JSONObject(preferences.getString("layout", "")!!))
        val hidden = preferences.getStringSet("hidden-unavailable", emptySet()).orEmpty()
            .filter(::safeWorkspaceId).take(64).toSet()
        AndroidWorkspaceState(
            layout = layout,
            railCollapsed = preferences.getBoolean("rail-collapsed", false),
            railWidthDp = preferences.getInt("rail-width", 244).coerceIn(188, 360),
            hiddenUnavailableSessionIds = hidden
        )
    }.getOrElse { AndroidWorkspaceState(emptyWorkspaceLayout()) }

    fun save(state: AndroidWorkspaceState) {
        preferences.edit()
            .putString("layout", encodeWorkspaceLayout(state.layout).toString())
            .putBoolean("rail-collapsed", state.railCollapsed)
            .putInt("rail-width", state.railWidthDp.coerceIn(188, 360))
            .putStringSet("hidden-unavailable", state.hiddenUnavailableSessionIds.filter(::safeWorkspaceId).take(64).toSet())
            .apply()
    }

    fun reconcile(snapshot: FleetSnapshot, state: AndroidWorkspaceState): AndroidWorkspaceState {
        val retained = snapshot.sessions.filter { session ->
            session.id in state.hiddenUnavailableSessionIds && !isFleetSessionAvailable(snapshot, session)
        }.map { it.id }.take(64).toSet()
        return if (retained == state.hiddenUnavailableSessionIds) state else state.copy(hiddenUnavailableSessionIds = retained)
    }
}

fun encodeWorkspaceLayout(layout: WorkspaceLayout): JSONObject = JSONObject()
    .put("schemaVersion", 1)
    .put("root", encodeWorkspaceNode(layout.root))
    .put("focusedPaneId", layout.focusedPaneId)
    .put("sessionMru", JSONArray(layout.sessionMru.take(64)))

fun decodeWorkspaceLayout(value: JSONObject): WorkspaceLayout {
    require(value.optInt("schemaVersion") == 1)
    val ids = mutableSetOf<String>()
    val sessions = mutableSetOf<String>()
    val root = decodeWorkspaceNode(value.getJSONObject("root"), ids, sessions, 0)
    require(workspacePanes(root).size in 1..4)
    val focused = value.getString("focusedPaneId")
    require(workspacePanes(root).any { it.id == focused })
    val mru = value.optJSONArray("sessionMru") ?: JSONArray()
    val recent = (0 until minOf(mru.length(), 64)).mapNotNull { index ->
        mru.optString(index).takeIf(::safeWorkspaceId)
    }.distinct()
    return WorkspaceLayout(root = root, focusedPaneId = focused, sessionMru = recent)
}

private fun encodeWorkspaceNode(node: WorkspaceNode): JSONObject = when (node) {
    is WorkspacePane -> JSONObject()
        .put("kind", "pane").put("id", node.id)
        .put("sessionId", node.sessionId ?: JSONObject.NULL)
        .put("viewMode", node.viewMode.name.lowercase())
    is WorkspaceSplit -> JSONObject()
        .put("kind", "split").put("id", node.id)
        .put("direction", node.direction.name.lowercase())
        .put("ratio", node.ratio.toDouble())
        .put("first", encodeWorkspaceNode(node.first)).put("second", encodeWorkspaceNode(node.second))
}

private fun decodeWorkspaceNode(
    value: JSONObject,
    ids: MutableSet<String>,
    sessions: MutableSet<String>,
    depth: Int
): WorkspaceNode {
    require(depth <= 7)
    val id = value.getString("id")
    require(safeWorkspaceId(id) && ids.add(id))
    return when (value.getString("kind")) {
        "pane" -> {
            val session = value.optString("sessionId").takeIf { value.opt("sessionId") != JSONObject.NULL && it.isNotBlank() }
            require(session == null || safeWorkspaceId(session) && sessions.add(session))
            val mode = when (value.optString("viewMode")) {
                "terminal" -> WorkspaceViewMode.Terminal
                "native" -> WorkspaceViewMode.Native
                else -> error("Invalid view mode")
            }
            WorkspacePane(id, session, mode)
        }
        "split" -> {
            val direction = when (value.getString("direction")) {
                "row" -> WorkspaceDirection.Row
                "column" -> WorkspaceDirection.Column
                else -> error("Invalid split direction")
            }
            val ratio = value.getDouble("ratio").toFloat()
            require(ratio in 0.2f..0.8f)
            WorkspaceSplit(
                id, direction, ratio,
                decodeWorkspaceNode(value.getJSONObject("first"), ids, sessions, depth + 1),
                decodeWorkspaceNode(value.getJSONObject("second"), ids, sessions, depth + 1)
            )
        }
        else -> error("Invalid workspace node")
    }
}

private fun mapNode(node: WorkspaceNode, transform: (WorkspacePane) -> WorkspacePane): WorkspaceNode = when (node) {
    is WorkspacePane -> transform(node)
    is WorkspaceSplit -> node.copy(first = mapNode(node.first, transform), second = mapNode(node.second, transform))
}

private fun replaceNode(node: WorkspaceNode, id: String, replacement: WorkspaceNode): WorkspaceNode {
    if (node.id == id) return replacement
    return when (node) {
        is WorkspacePane -> node
        is WorkspaceSplit -> node.copy(first = replaceNode(node.first, id, replacement), second = replaceNode(node.second, id, replacement))
    }
}

private fun removePane(node: WorkspaceNode, paneId: String): WorkspaceNode? = when (node) {
    is WorkspacePane -> if (node.id == paneId) null else node
    is WorkspaceSplit -> {
        val first = removePane(node.first, paneId)
        val second = removePane(node.second, paneId)
        when {
            first == null -> second
            second == null -> first
            else -> node.copy(first = first, second = second)
        }
    }
}

private fun newWorkspaceId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
private fun safeWorkspaceId(value: String): Boolean = value.length in 1..180 && value.all {
    it.isLetterOrDigit() || it in setOf('-', '_', ':', '.', ' ')
}
