package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentFleetAttachmentPolicyTest {
    @Test
    fun parsesOnlyValidManagedSessionMarkers() {
        assertEquals(
            "gaming:wtmux",
            AgentFleetAttachmentPolicy.sessionId("${AgentFleetContract.WORKSPACE_SESSION_PREFIX}gaming:wtmux")
        )
        assertNull(AgentFleetAttachmentPolicy.sessionId("ordinary command"))
        assertNull(AgentFleetAttachmentPolicy.sessionId(AgentFleetContract.WORKSPACE_SESSION_PREFIX))
        assertNull(AgentFleetAttachmentPolicy.sessionId("${AgentFleetContract.WORKSPACE_SESSION_PREFIX}bad\nvalue"))
    }

    @Test
    fun keepsCurrentRunningDuplicateOtherwiseOldestRunningCopy() {
        val candidates = listOf(
            AgentFleetAttachmentCandidate("old", true, 0),
            AgentFleetAttachmentCandidate("selected", true, 1),
            AgentFleetAttachmentCandidate("ended", false, 2)
        )
        assertEquals(1, AgentFleetAttachmentPolicy.canonicalIndex(candidates, "selected"))
        assertEquals(0, AgentFleetAttachmentPolicy.canonicalIndex(candidates, "missing"))
        assertEquals(-1, AgentFleetAttachmentPolicy.canonicalIndex(candidates.map { it.copy(running = false) }, "selected"))
    }

    @Test
    fun retainsFourAndNeverEvictsActiveOrCurrentAttachments() {
        val ids = listOf("one", "two", "three", "four", "five", "six")
        val usage = ids.mapIndexed { index, id -> id to index.toLong() }.toMap()
        assertEquals(
            listOf("one", "three"),
            AgentFleetAttachmentPolicy.evictionOrder(ids, usage, setOf("two", "six"), "four")
        )
        assertEquals(emptyList<String>(), AgentFleetAttachmentPolicy.evictionOrder(ids.take(4), usage, emptySet(), null))
    }
}
