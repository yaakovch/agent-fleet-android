package com.termux.app.fleet

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object FleetSnapshotParser {
    private const val MAX_SNAPSHOT_BYTES = 256 * 1024
    private const val MAX_COLLECTION_ITEMS = 2_000

    fun parse(json: String): FleetSnapshot {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_SNAPSHOT_BYTES) { "Fleet snapshot is too large" }
        val root = try {
            JSONObject(json)
        } catch (error: JSONException) {
            throw IllegalArgumentException("Fleet snapshot is not valid JSON", error)
        }

        return FleetSnapshot(
            revision = root.requiredString("revision", 64),
            generatedAt = root.requiredString("generatedAt", 40),
            hosts = root.requiredArray("hosts").mapObjects { host ->
                FleetHost(
                    id = host.requiredString("id", 160),
                    name = host.requiredString("name", 128),
                    status = host.requiredString("status", 32),
                    platform = host.requiredString("platform", 32),
                    lastSeenAt = host.optionalString("lastSeenAt", 40),
                    capabilities = host.requiredArray("capabilities").mapStrings(128).toSet()
                )
            },
            sessions = root.requiredArray("sessions").mapObjects { session ->
                FleetSession(
                    id = session.requiredString("id", 320),
                    hostId = session.requiredString("hostId", 160),
                    internalName = session.requiredString("internalName", 96),
                    name = session.requiredString("name", 128),
                    title = session.requiredString("title", 128, allowEmpty = true),
                    project = session.requiredString("project", 128, allowEmpty = true),
                    tool = session.requiredString("tool", 32),
                    backend = session.requiredString("backend", 32),
                    activity = session.requiredString("activity", 32),
                    attached = session.requiredBoolean("attached"),
                    updatedAt = session.optionalString("updatedAt", 40),
                    pendingScheduleCount = session.requiredInt("pendingScheduleCount", 0, 10_000)
                )
            },
            schedules = root.requiredArray("schedules").mapObjects { schedule ->
                FleetSchedule(
                    id = schedule.requiredString("id", 160),
                    hostId = schedule.requiredString("hostId", 160),
                    sessionId = schedule.requiredString("sessionId", 320),
                    deliverAt = schedule.requiredString("deliverAt", 40),
                    status = schedule.requiredString("status", 32)
                )
            },
            attention = root.requiredArray("attention").mapObjects { attention ->
                FleetAttention(
                    id = attention.requiredString("id", 160),
                    hostId = attention.requiredString("hostId", 160),
                    sessionId = attention.requiredString("sessionId", 320),
                    agent = attention.requiredString("agent", 32),
                    resetAt = attention.optionalString("resetAt", 40),
                    state = attention.requiredString("state", 32)
                )
            },
            limits = root.optionalArray("limits").mapObjects { limit ->
                FleetLimit(
                    id = limit.requiredString("id", 160),
                    hostId = limit.requiredString("hostId", 160),
                    provider = limit.requiredString("provider", 32),
                    profileAlias = limit.requiredString("profileAlias", 64),
                    status = limit.requiredString("status", 32),
                    primary = limit.optionalWindow("primary"),
                    secondary = limit.optionalWindow("secondary"),
                    updatedAt = limit.requiredString("updatedAt", 40)
                )
            }
        ).also { snapshot ->
            val hostIds = snapshot.hosts.map { it.id }.toSet()
            require(snapshot.sessions.all { it.hostId in hostIds }) { "Session references an unknown host" }
            require(snapshot.sessions.map { it.id }.toSet().size == snapshot.sessions.size) { "Duplicate session id" }
            require(snapshot.limits.all { it.hostId in hostIds }) { "Limit profile references an unknown host" }
        }
    }

    private fun JSONObject.requiredArray(name: String): JSONArray =
        try {
            getJSONArray(name).also { require(it.length() <= MAX_COLLECTION_ITEMS) { "$name has too many entries" } }
        } catch (error: JSONException) {
            throw IllegalArgumentException("Missing or invalid $name", error)
        }

    private fun JSONObject.optionalArray(name: String): JSONArray = if (has(name)) requiredArray(name) else JSONArray()

    private fun JSONObject.optionalWindow(name: String): FleetLimitWindow? {
        if (isNull(name)) return null
        val value = try { getJSONObject(name) } catch (error: JSONException) {
            throw IllegalArgumentException("Invalid $name window", error)
        }
        return FleetLimitWindow(
            usedPercent = value.requiredDouble("usedPercent", 0.0, 100.0),
            remainingPercent = value.requiredDouble("remainingPercent", 0.0, 100.0),
            resetsAt = value.requiredString("resetsAt", 40),
            windowMinutes = value.requiredInt("windowMinutes", 1, 525_600)
        )
    }

    private fun JSONObject.requiredString(name: String, max: Int, allowEmpty: Boolean = false): String {
        val value = try { getString(name) } catch (error: JSONException) {
            throw IllegalArgumentException("Missing or invalid $name", error)
        }
        require(value.length <= max && (allowEmpty || value.isNotBlank()) && value.none { it.isISOControl() }) {
            "Invalid $name"
        }
        return value
    }

    private fun JSONObject.optionalString(name: String, max: Int): String? {
        if (isNull(name)) return null
        return requiredString(name, max, allowEmpty = true)
    }

    private fun JSONObject.requiredBoolean(name: String): Boolean = try {
        getBoolean(name)
    } catch (error: JSONException) {
        throw IllegalArgumentException("Missing or invalid $name", error)
    }

    private fun JSONObject.requiredInt(name: String, min: Int, max: Int): Int {
        val value = try { getInt(name) } catch (error: JSONException) {
            throw IllegalArgumentException("Missing or invalid $name", error)
        }
        require(value in min..max) { "Invalid $name" }
        return value
    }

    private fun JSONObject.requiredDouble(name: String, min: Double, max: Double): Double {
        val value = try { getDouble(name) } catch (error: JSONException) {
            throw IllegalArgumentException("Missing or invalid $name", error)
        }
        require(value in min..max) { "Invalid $name" }
        return value
    }

    private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        (0 until length()).map { index ->
            val value = try { getJSONObject(index) } catch (error: JSONException) {
                throw IllegalArgumentException("Collection entry is not an object", error)
            }
            transform(value)
        }

    private fun JSONArray.mapStrings(max: Int): List<String> = (0 until length()).map { index ->
        val value = try { getString(index) } catch (error: JSONException) {
            throw IllegalArgumentException("Collection entry is not a string", error)
        }
        require(value.isNotBlank() && value.length <= max && value.none { it.isISOControl() }) { "Invalid string entry" }
        value
    }
}
