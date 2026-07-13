package com.termux.app.fleet

import android.content.Context
import org.json.JSONArray

class RecentLocationStore(context: Context) {
    private val preferences = context.getSharedPreferences("agent_fleet_locations", Context.MODE_PRIVATE)

    fun load(hostId: String, backend: String): List<String> = runCatching {
        val values = JSONArray(preferences.getString(key(hostId, backend), "[]"))
        (0 until minOf(values.length(), MAX_LOCATIONS)).mapNotNull { index ->
            values.optString(index).takeIf { it.isNotBlank() && it.length <= 2_048 && it.none(Char::isISOControl) }
        }
    }.getOrDefault(emptyList())

    fun record(hostId: String, backend: String, path: String) {
        val values = listOf(path) + load(hostId, backend).filterNot { it == path }
        preferences.edit().putString(key(hostId, backend), JSONArray(values.take(MAX_LOCATIONS)).toString()).apply()
    }

    fun remove(hostId: String, backend: String, path: String) {
        preferences.edit().putString(key(hostId, backend), JSONArray(load(hostId, backend).filterNot { it == path }).toString()).apply()
    }

    fun clear(hostId: String, backend: String) {
        preferences.edit().remove(key(hostId, backend)).apply()
    }

    private fun key(hostId: String, backend: String): String = "${hostId.take(160)}:${backend.take(16)}"

    companion object { private const val MAX_LOCATIONS = 10 }
}
