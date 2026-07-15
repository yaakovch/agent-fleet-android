package com.termux.app

import com.termux.app.fleet.FleetHost
import com.termux.app.fleet.FleetSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFleetPrototypeTest {
    private val hosts = mapOf("gaming" to FleetHost("gaming", "Gaming desktop", "healthy", "wsl", null, emptySet()))
    private val sessions = listOf(
        FleetSession("gaming:wtmux", "gaming", "wtmux-main", "wtmux", "Android companion", "wtmux", "codex", "linux", "active", true, null, 0),
        FleetSession("gaming:agent", "gaming", "agent-main", "agent-fleet", "Terminal tabs", "agent-fleet", "claude", "linux", "idle", false, null, 0)
    )

    @Test
    fun emptySearchPreservesFixtureOrder() {
        assertEquals(sessions, filterSessions(sessions, hosts, "  "))
    }

    @Test
    fun searchMatchesHostProjectTitleAndTool() {
        assertEquals(2, filterSessions(sessions, hosts, "Gaming desktop").size)
        assertEquals(listOf("wtmux"), filterSessions(sessions, hosts, "Android companion").map { it.project })
        assertTrue(filterSessions(sessions, hosts, "Claude").all { it.tool == "claude" })
        assertEquals(1, filterSessions(sessions, hosts, "agent-fleet").size)
    }

    @Test
    fun repositoryErrorNeverAlsoShowsAnEmptyFolderMessage() {
        assertTrue(shouldShowRepositoryEmpty(loading = false, error = "", entryCount = 0))
        assertTrue(!shouldShowRepositoryEmpty(loading = false, error = "Host is offline", entryCount = 0))
        assertTrue(!shouldShowRepositoryEmpty(loading = true, error = "", entryCount = 0))
    }
}
