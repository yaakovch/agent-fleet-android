package com.termux.app.fleet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class RecentSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("agent_fleet_terminal_tabs", Context.MODE_PRIVATE)

    fun record(session: FleetSession) {
        val updated = listOf(session) + load().filterNot { it.id == session.id }
        preferences.edit().putString(KEY, encode(updated.take(MAX_TABS))).apply()
    }

    fun load(): List<FleetSession> = runCatching {
        val array = JSONArray(preferences.getString(KEY, "[]"))
        (0 until minOf(array.length(), MAX_TABS)).map { index -> decode(array.getJSONObject(index)) }
    }.getOrDefault(emptyList())

    private fun encode(sessions: List<FleetSession>): String = JSONArray().apply {
        sessions.forEach { session ->
            put(JSONObject()
                .put("id", session.id)
                .put("hostId", session.hostId)
                .put("internalName", session.internalName)
                .put("name", session.name)
                .put("title", session.title)
                .put("project", session.project)
                .put("projectPath", session.projectPath)
                .put("locationKind", session.locationKind)
                .put("tool", session.tool)
                .put("backend", session.backend))
        }
    }.toString()

    private fun decode(value: JSONObject) = FleetSession(
        id = value.getString("id").safe(320),
        hostId = value.getString("hostId").safe(160),
        internalName = value.getString("internalName").safe(96),
        name = value.getString("name").safe(128),
        title = value.getString("title").safe(128, allowEmpty = true),
        project = value.getString("project").safe(128, allowEmpty = true),
        tool = value.getString("tool").safe(32),
        backend = value.getString("backend").safe(32),
        activity = "idle",
        attached = false,
        updatedAt = null,
        pendingScheduleCount = 0,
        projectPath = value.optString("projectPath").safe(2048, allowEmpty = true),
        locationKind = value.optString("locationKind", "project").safe(16)
    )

    private fun String.safe(max: Int, allowEmpty: Boolean = false): String {
        require(length <= max && (allowEmpty || isNotBlank()) && none { it.isISOControl() })
        return this
    }

    companion object {
        private const val KEY = "recent_sessions_v1"
        private const val MAX_TABS = 12
    }
}
