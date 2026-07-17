package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleSessionRecoveryTest {
    @Test
    fun cachedSessionIsUnavailableUntilItsOwningHostIsHealthy() {
        val offline = snapshot("offline")
        assertFalse(isFleetSessionAvailable(offline, offline.sessions.single()))
        val healthy = snapshot("healthy")
        assertTrue(isFleetSessionAvailable(healthy, healthy.sessions.single()))
    }

    @Test
    fun killRefreshesAndRetriesOnceWithTheSameLogicalOperation() {
        val initial = snapshot("healthy", "old")
        val fresh = snapshot("healthy", "new")
        val revisions = mutableListOf<String>()
        val result = killSessionWithStaleRetry(
            initial,
            initial.sessions.single(),
            execute = { current, _ ->
                revisions += current.revision
                if (current.revision == "old") throw FleetUnavailableException("changed", "stale_revision")
                current.copy(sessions = emptyList())
            },
            refresh = { fresh }
        )
        assertEquals(listOf("old", "new"), revisions)
        assertTrue(result.sessions.isEmpty())
    }

    @Test
    fun missingSessionAfterStaleRefreshMeansAlreadyStopped() {
        val initial = snapshot("healthy", "old")
        var executions = 0
        val result = killSessionWithStaleRetry(
            initial,
            initial.sessions.single(),
            execute = { _, _ -> executions++; throw FleetUnavailableException("changed", "stale_revision") },
            refresh = { snapshot("healthy", "new").copy(sessions = emptyList()) }
        )
        assertEquals(1, executions)
        assertTrue(result.sessions.isEmpty())
    }

    @Test
    fun hostDoctorRefreshesAndRetriesOnceWhenTheUiSnapshotChanged() {
        val initial = snapshot("healthy", "old")
        val fresh = snapshot("healthy", "new")
        val revisions = mutableListOf<String>()

        val result = doctorHostWithStaleRetry(
            initial,
            "gaming",
            execute = { current, hostId ->
                revisions += current.revision
                if (current.revision == "old") throw FleetUnavailableException("changed", "stale_revision")
                FleetDoctorResult(
                    hostId, "2026-07-17T00:00:00Z", "healthy",
                    listOf(FleetDoctorCheck("runtime", "healthy", "Runtime ready", ""))
                )
            },
            refresh = { fresh }
        )

        assertEquals(listOf("old", "new"), revisions)
        assertEquals("healthy", result.status)
    }

    private fun snapshot(status: String, revision: String = "one"): FleetSnapshot = FleetSnapshot(
        revision = revision,
        generatedAt = "",
        hosts = listOf(FleetHost("gaming", "Gaming", status, "wsl", null, emptySet())),
        sessions = listOf(FleetSession(
            "gaming:one", "gaming", "one", "One", "codex", "project", "codex", "linux",
            "active", false, null, 0
        )),
        schedules = emptyList(), attention = emptyList()
    )
}
