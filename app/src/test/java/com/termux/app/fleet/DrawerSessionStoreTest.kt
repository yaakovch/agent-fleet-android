package com.termux.app.fleet

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DrawerSessionStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clear()
    }

    @After
    fun tearDown() = clear()

    @Test
    fun migratesLegacyRecentsWithoutChangingMruOrder() {
        val legacy = RecentSessionStore(context)
        legacy.record(session("one"))
        legacy.record(session("two"))

        val rows = DrawerSessionStore(context).rows(snapshot("healthy", session("one"), session("two")))

        assertEquals(listOf("two", "one"), rows.map { it.session.internalName })
        assertTrue(rows.none { it.pinned })
        assertTrue(rows.all { it.surface == DrawerSessionSurface.Native })
    }

    @Test
    fun favoritesSortBeforePhoneMruAndSurfacePersists() {
        val store = DrawerSessionStore(context)
        val first = session("first")
        val second = session("second")
        store.recordOpened(first)
        store.recordOpened(second, DrawerSessionSurface.Terminal)
        store.setPinned(first, true)

        val rows = store.rows(snapshot("healthy", first, second))

        assertEquals(listOf("first", "second"), rows.map { it.session.internalName })
        assertTrue(rows.first().pinned)
        assertEquals(DrawerSessionSurface.Terminal, rows.last().surface)
        assertEquals(DrawerSessionSurface.Terminal, store.surfaceFor(second.id))
        assertEquals(second.id, store.sessionFor(second.id)?.id)
        assertEquals(second.hostId, store.sessionFor(second.id)?.hostId)
        assertEquals(second.internalName, store.sessionFor(second.id)?.internalName)
    }

    @Test
    fun healthyHostPurgesMissingSessionButOfflineHostKeepsDisabledCache() {
        val store = DrawerSessionStore(context)
        val remembered = session("remembered")
        store.recordOpened(remembered)

        val offlineRows = store.rows(snapshot("offline"))
        assertEquals(1, offlineRows.size)
        assertFalse(offlineRows.single().available)
        assertTrue(offlineRows.single().cached)

        assertTrue(store.rows(snapshot("healthy")).isEmpty())
        assertTrue(store.recordsForTest().isEmpty())
    }

    @Test
    fun liveUnseenSessionsAppearAfterMruAndRemovingLocalMetadataDoesNotHideLiveSession() {
        val store = DrawerSessionStore(context)
        val recent = session("recent")
        val unseen = session("unseen")
        store.recordOpened(recent)

        assertEquals(
            listOf("recent", "unseen"),
            store.rows(snapshot("healthy", unseen, recent)).map { it.session.internalName }
        )
        store.remove(recent.id)
        assertEquals(setOf("recent", "unseen"), store.rows(snapshot("healthy", unseen, recent)).map { it.session.internalName }.toSet())
    }

    private fun clear() {
        context.getSharedPreferences("agent_fleet_terminal_drawer", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("agent_fleet_terminal_tabs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun session(name: String) = FleetSession(
        id = "gaming:$name",
        hostId = "gaming",
        internalName = name,
        name = name.replaceFirstChar(Char::uppercase),
        title = "Codex",
        project = "wtmux",
        tool = "codex",
        backend = "linux",
        activity = "active",
        attached = false,
        updatedAt = null,
        pendingScheduleCount = 0
    )

    private fun snapshot(status: String, vararg sessions: FleetSession) = FleetSnapshot(
        revision = "revision",
        generatedAt = "",
        hosts = listOf(FleetHost("gaming", "Gaming", status, "wsl", null, emptySet())),
        sessions = sessions.toList(),
        schedules = emptyList(),
        attention = emptyList()
    )
}
