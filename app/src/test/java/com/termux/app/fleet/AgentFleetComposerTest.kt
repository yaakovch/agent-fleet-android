package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFleetComposerTest {
    @Test
    fun contentIsTrimmedAndAttachmentsAreSeparated() {
        assertEquals(
            "continue\n\n.wtmux/images/one.png\n.wtmux/images/two.png",
            buildAgentFleetComposerText(
                "continue  \n",
                listOf(".wtmux/images/one.png", ".wtmux/images/two.png")
            )
        )
    }

    @Test
    fun attachmentsWorkWithoutMessageText() {
        assertEquals(".wtmux/images/one.png", buildAgentFleetComposerText("  ", listOf(".wtmux/images/one.png")))
    }

    @Test
    fun emptyPrimaryActionBecomesEnter() {
        assertEquals("Enter", agentFleetPrimaryActionLabel(false))
        assertEquals("Send", agentFleetPrimaryActionLabel(true))
    }

    @Test
    fun lateUploadsCanOnlyCompleteTheirExactSessionGeneration() {
        val owner = AgentFleetComposerUploadOwner()
        val sessionA = owner.begin("host:project:session-a")
        val sessionB = owner.begin("host:project:session-b")
        val newerA = owner.begin("host:project:session-a")

        assertFalse(owner.finish(sessionA))
        assertTrue(owner.finish(sessionB))
        assertTrue(owner.finish(newerA))
        assertFalse(owner.finish(newerA))

        val nextA = owner.begin("host:project:session-a")
        assertFalse(owner.finish(newerA))
        assertTrue(owner.finish(nextA))

        val invalidatedA = owner.begin("host:project:session-a")
        owner.invalidate(invalidatedA.target)
        val afterInvalidationA = owner.begin("host:project:session-a")
        assertFalse(owner.finish(invalidatedA))
        assertTrue(owner.finish(afterInvalidationA))
    }

    @Test
    fun completedUploadTicketsDoNotRetainPerTargetHistory() {
        val owner = AgentFleetComposerUploadOwner()

        repeat(10_000) { index ->
            assertTrue(owner.finish(owner.begin("host:project:session-$index")))
        }

        assertEquals(0, owner.activeUploadCountForTest())
    }

    @Test
    fun composerRetentionNeverEvictsAnActiveOrExternallyOwnedTarget() {
        val states = listOf(
            AgentFleetComposerRetentionState("active-a", uploading = true, hasAttachments = false),
            AgentFleetComposerRetentionState("active-b", uploading = true, hasAttachments = true),
            AgentFleetComposerRetentionState("picker", uploading = false, hasAttachments = false)
        )

        assertEquals(
            null,
            agentFleetComposerTargetToEvict(states, protectedTargets = setOf("picker"))
        )
    }

    @Test
    fun composerRetentionPrefersAnEmptyInactiveTarget() {
        val states = listOf(
            AgentFleetComposerRetentionState("draft", uploading = false, hasAttachments = true),
            AgentFleetComposerRetentionState("empty", uploading = false, hasAttachments = false),
            AgentFleetComposerRetentionState("active", uploading = true, hasAttachments = false)
        )

        assertEquals(
            "empty",
            agentFleetComposerTargetToEvict(states, protectedTargets = emptySet())
        )
    }

    @Test
    fun composerTargetEncodingCannotConfuseColonsAcrossFields() {
        assertFalse(
            agentFleetComposerTarget("host", "project:branch", "session") ==
                agentFleetComposerTarget("host", "project", "branch:session")
        )
        assertEquals(
            agentFleetComposerTarget("host", "project:branch", "session"),
            agentFleetComposerTarget("host", "project:branch", "session")
        )
    }

    @Test
    fun workspaceTargetChangesWhenAReusedSessionIdMovesRoutes() {
        val first = testSession(id = "reused", host = "host-a", project = "project-a")
        val movedProject = testSession(id = "reused", host = "host-a", project = "project-b")
        val movedHost = testSession(id = "reused", host = "host-b", project = "project-a")

        assertFalse(agentFleetWorkspaceTarget(first) == agentFleetWorkspaceTarget(movedProject))
        assertFalse(agentFleetWorkspaceTarget(first) == agentFleetWorkspaceTarget(movedHost))
        assertEquals(agentFleetWorkspaceTarget(first), agentFleetWorkspaceTarget(first.copy()))
    }

    @Test
    fun staleExternalImageResultsCannotConsumeOrCancelANewerSessionRequest() {
        val owner = AgentFleetExternalImageRequestOwner()
        val oldCamera = owner.begin("host-a", camera = true)
        val picker = owner.begin("host-a", camera = false)
        assertEquals(null, owner.begin("host-b", camera = true))
        assertTrue(owner.cancel(oldCamera))
        val newCamera = owner.begin("host-b", camera = true)

        assertFalse(owner.cancel(oldCamera))
        assertEquals(null, owner.consume(oldCamera))
        assertEquals("host-a", owner.consume(picker))
        assertEquals("host-b", owner.consume(newCamera))
        assertEquals(0, owner.activeRequestCountForTest())
    }

    @Test
    fun externalImageRequestStorageStaysBoundedByRequestKind() {
        val owner = AgentFleetExternalImageRequestOwner()

        repeat(10_000) { index ->
            owner.begin("target-$index", camera = index % 2 == 0)
        }

        assertEquals(2, owner.activeRequestCountForTest())
    }

    @Test
    fun restoredExternalImageRequestKeepsItsOriginalTargetAndGeneration() {
        val owner = AgentFleetExternalImageRequestOwner()
        val request = AgentFleetExternalImageRequest("host-a", camera = true, generation = 42L)

        assertTrue(owner.restore(request))
        assertTrue(owner.restore(request))
        assertFalse(owner.restore(request.copy(target = "host-b")))
        assertEquals("host-a", owner.consume(request))

        val next = owner.begin("host-b", camera = true)
        assertEquals(43L, next?.generation)
    }

    @Test
    fun workspaceUploadsPreserveCompletedPathsWhenALaterUploadFails() {
        val result = agentFleetUploadSequentially(listOf("one", "two", "three"), maxCount = 3) { source ->
            if (source == "two") error("upload failed")
            "remote/$source"
        }

        assertEquals(listOf("remote/one"), result.completed)
        assertEquals("upload failed", result.error?.message)
        assertFalse(result.cancelled)
    }

    @Test
    fun workspaceUploadCancellationStopsBeforeProducingMoreRemoteOrphans() {
        var active = true
        val result = agentFleetUploadSequentially(
            listOf("one", "two", "three"),
            maxCount = 3,
            shouldContinue = { active }
        ) { source ->
            active = false
            "remote/$source"
        }

        assertEquals(listOf("remote/one"), result.completed)
        assertTrue(result.cancelled)
    }

    @Test
    fun workspaceUploadCapacityNeverExceedsAttachmentLimit() {
        assertEquals(8, agentFleetWorkspaceImageCapacity(0))
        assertEquals(3, agentFleetWorkspaceImageCapacity(5))
        assertEquals(0, agentFleetWorkspaceImageCapacity(8))
        assertEquals(0, agentFleetWorkspaceImageCapacity(99))
    }

    private fun testSession(id: String, host: String, project: String) = FleetSession(
        id = id,
        hostId = host,
        internalName = "session",
        name = "Session",
        title = "Session",
        project = project,
        tool = "codex",
        backend = "linux",
        activity = "working",
        attached = true,
        updatedAt = null,
        pendingScheduleCount = 0
    )
}
