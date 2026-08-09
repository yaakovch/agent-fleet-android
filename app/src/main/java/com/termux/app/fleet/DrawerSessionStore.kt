package com.termux.app.fleet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class DrawerSessionSurface { Native, Terminal }

data class DrawerSessionRecord(
    val session: FleetSession,
    val pinned: Boolean = false,
    val lastUsed: Long = 0,
    val surface: DrawerSessionSurface = DrawerSessionSurface.Native
)

data class DrawerRemoteSession(
    val session: FleetSession,
    val pinned: Boolean,
    val lastUsed: Long,
    val surface: DrawerSessionSurface,
    val available: Boolean,
    val cached: Boolean
)

class DrawerSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun recordOpened(session: FleetSession, surface: DrawerSessionSurface? = null) {
        val state = loadState()
        val existing = state.records[session.id]
        val order = state.counter + 1
        state.counter = order
        state.records[session.id] = DrawerSessionRecord(
            session = session,
            pinned = existing?.pinned == true,
            lastUsed = order,
            surface = surface ?: existing?.surface ?: DrawerSessionSurface.Native
        )
        saveState(state)
    }

    @Synchronized
    fun setPinned(session: FleetSession, pinned: Boolean) {
        val state = loadState()
        val existing = state.records[session.id]
        if (!pinned && existing?.lastUsed == 0L) {
            state.records.remove(session.id)
        } else {
            state.records[session.id] = DrawerSessionRecord(
                session = session,
                pinned = pinned,
                lastUsed = existing?.lastUsed ?: 0,
                surface = existing?.surface ?: DrawerSessionSurface.Native
            )
        }
        saveState(state)
    }

    @Synchronized
    fun setSurface(sessionId: String, surface: DrawerSessionSurface) {
        val state = loadState()
        val existing = state.records[sessionId] ?: return
        state.records[sessionId] = existing.copy(surface = surface)
        saveState(state)
    }

    @Synchronized
    fun surfaceFor(sessionId: String): DrawerSessionSurface =
        loadState().records[sessionId]?.surface ?: DrawerSessionSurface.Native

    @Synchronized
    fun sessionFor(sessionId: String): FleetSession? = loadState().records[sessionId]?.session

    @Synchronized
    fun setActiveFullscreen(sessionId: String?) {
        if (sessionId == null) {
            preferences.edit().remove(ACTIVE_FULLSCREEN_KEY).apply()
        } else if (sessionId.matches(Regex("[A-Za-z0-9._: -]{1,180}"))) {
            preferences.edit().putString(ACTIVE_FULLSCREEN_KEY, sessionId).apply()
        }
    }

    @Synchronized
    fun activeFullscreenSession(): FleetSession? =
        preferences.getString(ACTIVE_FULLSCREEN_KEY, null)?.let(::sessionFor)

    @Synchronized
    fun remove(sessionId: String) {
        val state = loadState()
        if (state.records.remove(sessionId) != null) saveState(state)
        if (preferences.getString(ACTIVE_FULLSCREEN_KEY, null) == sessionId) setActiveFullscreen(null)
    }

    @Synchronized
    fun clearCachedTitles() {
        val state = loadState()
        state.records.replaceAll { _, record ->
            record.copy(session = record.session.copy(title = "", nameMode = "automatic"))
        }
        saveState(state)
    }

    /**
     * Merge live fleet state with bounded phone-local history. A healthy host is
     * authoritative: remembered sessions that it no longer reports are ended.
     * Offline or absent hosts retain disabled last-known rows.
     */
    @Synchronized
    fun rows(snapshot: FleetSnapshot?): List<DrawerRemoteSession> {
        val state = loadState()
        var changed = false
        if (snapshot != null) {
            val liveById = snapshot.sessions.associateBy(FleetSession::id)
            val healthyHosts = snapshot.hosts.filter { it.status == "healthy" }.map(FleetHost::id).toSet()
            state.records.keys.toList().forEach { id ->
                val record = state.records[id] ?: return@forEach
                val live = liveById[id]
                when {
                    live != null && live != record.session -> {
                        state.records[id] = record.copy(session = live)
                        changed = true
                    }
                    live == null && record.session.hostId in healthyHosts -> {
                        state.records.remove(id)
                        changed = true
                    }
                }
            }
        }
        if (changed) saveState(state)

        val liveById = snapshot?.sessions.orEmpty().associateBy(FleetSession::id)
        val allIds = LinkedHashSet<String>().apply {
            addAll(liveById.keys)
            addAll(state.records.keys)
        }
        return allIds.mapNotNull { id ->
            val record = state.records[id]
            val live = liveById[id]
            val session = live ?: record?.session ?: return@mapNotNull null
            DrawerRemoteSession(
                session = session,
                pinned = record?.pinned == true,
                lastUsed = record?.lastUsed ?: 0,
                surface = record?.surface ?: DrawerSessionSurface.Native,
                available = snapshot?.let { isFleetSessionAvailable(it, session) } == true,
                cached = live == null
            )
        }.sortedWith(
            compareByDescending<DrawerRemoteSession> { it.pinned }
                .thenByDescending { it.lastUsed }
                .thenBy { it.session.name.lowercase() }
                .thenBy { it.session.id }
        )
    }

    @Synchronized
    internal fun recordsForTest(): List<DrawerSessionRecord> = loadState().records.values.toList()

    private fun loadState(): StoredState {
        val raw = preferences.getString(KEY, null)
        if (raw == null) {
            val migrated = StoredState(counter = 0, records = linkedMapOf())
            val legacy = RecentSessionStore(preferencesContext()).load()
            legacy.asReversed().forEach { session ->
                migrated.counter++
                migrated.records[session.id] = DrawerSessionRecord(session, lastUsed = migrated.counter)
            }
            saveState(migrated)
            return migrated
        }
        return runCatching { decode(JSONObject(raw)) }.getOrElse {
            val empty = StoredState(counter = 0, records = linkedMapOf())
            check(
                preferences.edit()
                    .putString(CORRUPT_BACKUP_KEY, raw.take(MAX_CORRUPT_BACKUP_CHARS))
                    .putString(KEY, encodeState(empty))
                    .commit()
            ) { "Unable to preserve corrupt drawer session state" }
            empty
        }
    }

    private fun preferencesContext(): Context = contextReference

    private fun saveState(state: StoredState) {
        val bounded = state.records.values
            .sortedWith(compareByDescending<DrawerSessionRecord> { it.pinned }.thenByDescending { it.lastUsed })
            .take(MAX_RECORDS)
        val boundedState = StoredState(
            counter = state.counter,
            records = LinkedHashMap(bounded.associateBy { it.session.id })
        )
        preferences.edit().putString(KEY, encodeState(boundedState)).apply()
        state.records.keys.retainAll(bounded.mapTo(mutableSetOf()) { it.session.id })
    }

    private fun encodeState(state: StoredState): String = JSONObject()
        .put("version", VERSION)
        .put("counter", state.counter)
        .put("records", JSONArray().apply { state.records.values.forEach { put(encodeRecord(it)) } })
        .toString()

    private fun decode(value: JSONObject): StoredState {
        require(value.getInt("version") == VERSION)
        val array = value.getJSONArray("records")
        require(array.length() <= MAX_RECORDS)
        val records = linkedMapOf<String, DrawerSessionRecord>()
        repeat(array.length()) { index ->
            val record = decodeRecord(array.getJSONObject(index))
            records[record.session.id] = record
        }
        return StoredState(value.optLong("counter").coerceAtLeast(records.maxOfOrNull { it.value.lastUsed } ?: 0), records)
    }

    private fun encodeRecord(record: DrawerSessionRecord): JSONObject = encodeSession(record.session)
        .put("pinned", record.pinned)
        .put("lastUsed", record.lastUsed)
        .put("surface", record.surface.name.lowercase())

    private fun decodeRecord(value: JSONObject): DrawerSessionRecord = DrawerSessionRecord(
        session = decodeSession(value),
        pinned = value.optBoolean("pinned"),
        lastUsed = value.optLong("lastUsed").coerceAtLeast(0),
        surface = if (value.optString("surface") == "terminal") DrawerSessionSurface.Terminal else DrawerSessionSurface.Native
    )

    private fun encodeSession(session: FleetSession) = JSONObject()
        .put("id", session.id)
        .put("hostId", session.hostId)
        .put("internalName", session.internalName)
        .put("name", session.name)
        .put("title", session.title)
        .put("nameMode", session.nameMode)
        .put("project", session.project)
        .put("projectPath", session.projectPath)
        .put("locationKind", session.locationKind)
        .put("tool", session.tool)
        .put("backend", session.backend)

    private fun decodeSession(value: JSONObject) = FleetSession(
        id = value.getString("id").safe(320),
        hostId = value.getString("hostId").safe(160),
        internalName = value.getString("internalName").safe(96),
        name = value.getString("name").safe(128),
        title = value.optString("title").safe(128, true),
        nameMode = value.optString("nameMode", "automatic").safe(16).also { require(it in setOf("automatic", "manual")) },
        project = value.optString("project").safe(128, true),
        tool = value.getString("tool").safe(32),
        backend = value.getString("backend").safe(32),
        activity = "idle",
        attached = false,
        updatedAt = null,
        pendingScheduleCount = 0,
        projectPath = value.optString("projectPath").safe(2048, true),
        locationKind = value.optString("locationKind", "project").safe(16)
    )

    private fun String.safe(maximum: Int, allowEmpty: Boolean = false): String {
        require(length <= maximum && (allowEmpty || isNotBlank()) && none(Char::isISOControl))
        return this
    }

    private data class StoredState(var counter: Long, val records: LinkedHashMap<String, DrawerSessionRecord>)

    private val contextReference = context.applicationContext

    companion object {
        private const val PREFERENCES = "agent_fleet_terminal_drawer"
        private const val KEY = "drawer_sessions_v2"
        private const val CORRUPT_BACKUP_KEY = "drawer_sessions_v2_corrupt_backup"
        private const val ACTIVE_FULLSCREEN_KEY = "active_fullscreen_session_v1"
        private const val VERSION = 2
        private const val MAX_RECORDS = 64
        private const val MAX_CORRUPT_BACKUP_CHARS = 256 * 1024
    }
}
