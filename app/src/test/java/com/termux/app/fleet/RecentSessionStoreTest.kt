package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RecentSessionStoreTest {
    private lateinit var store: RecentSessionStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("agent_fleet_terminal_tabs", 0).edit().clear().commit()
        store = RecentSessionStore(context)
    }

    @Test
    fun recordsMostRecentSessionFirstAndDeduplicates() {
        store.record(session("one"))
        store.record(session("two"))
        store.record(session("one"))
        assertEquals(listOf("one", "two"), store.load().map { it.id })
    }

    @Test
    fun boundsPersistedTabDescriptors() {
        repeat(20) { store.record(session("session-$it")) }
        assertEquals(12, store.load().size)
        assertEquals("session-19", store.load().first().id)
    }

    private fun session(id: String) = FleetSession(
        id, "host", "internal-$id", id, "", "project", "shell", "linux", "idle", false, null, 0
    )
}
