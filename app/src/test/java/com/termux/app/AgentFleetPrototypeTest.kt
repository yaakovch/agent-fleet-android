package com.termux.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFleetPrototypeTest {
    @Test
    fun emptySearchPreservesFixtureOrder() {
        assertEquals(AgentFleetFixtures.sessions, filterSessions(AgentFleetFixtures.sessions, "  "))
    }

    @Test
    fun searchMatchesHostProjectTitleAndTool() {
        assertEquals(2, filterSessions(AgentFleetFixtures.sessions, "Work M").size)
        assertEquals(listOf("wtmux"), filterSessions(AgentFleetFixtures.sessions, "Android companion").map { it.project })
        assertTrue(filterSessions(AgentFleetFixtures.sessions, "Claude").all { it.tool == "Claude" })
        assertEquals(2, filterSessions(AgentFleetFixtures.sessions, "agent-fleet").size)
    }
}

