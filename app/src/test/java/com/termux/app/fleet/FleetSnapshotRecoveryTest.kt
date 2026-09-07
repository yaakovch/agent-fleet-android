package com.termux.app.fleet

import org.junit.Assert.*
import org.junit.Test

class FleetSnapshotRecoveryTest {
    private fun snapshot() = FleetSnapshot(
        "old", "2026-09-07T00:00:00Z",
        listOf(FleetHost("one", "One", "healthy", "wsl", null, emptySet()),
            FleetHost("two", "Two", "healthy", "wsl", null, emptySet())),
        listOf(FleetSession("one:a", "one", "a", "A", "", "", "shell", "linux", "idle", false, null, 0),
            FleetSession("two:b", "two", "b", "B", "", "", "shell", "linux", "idle", false, null, 0)),
        emptyList(), emptyList()
    )

    @Test fun failedRefreshRetainsVisibleSessionsButDisablesActions() {
        val previous = snapshot()
        val cached = staleFleetSnapshot(previous, "NETWORK_UNREACHABLE")
        assertEquals(previous.sessions, cached.sessions)
        assertTrue(cached.isStale)
        assertEquals(previous.receivedAtMillis, cached.receivedAtMillis)
        assertTrue(cached.hosts.all { it.errorCode == "NETWORK_UNREACHABLE" && it.status == "offline" })
        assertTrue(cached.sessions.none { isFleetSessionAvailable(cached, it) })
        assertTrue(cached.physicalHosts.all { it.status == "offline" })
        assertTrue(cached.executionTargets.all { it.status == "unknown" })
    }

    @Test fun successfulEmptyHostIsNeverRepopulatedFromAnotherConnectingHost() {
        val previous = snapshot()
        val fresh = previous.copy(revision = "new", sessions = emptyList(),
            hosts = listOf(previous.hosts[0], previous.hosts[1].copy(status = "connecting")))
        val reconciled = reconcileFleetSnapshot(previous, fresh)
        assertEquals("new", reconciled.revision)
        assertEquals(listOf("two:b"), reconciled.sessions.map { it.id })
        assertFalse(isFleetSessionAvailable(reconciled, reconciled.sessions.single()))
        assertTrue(reconcileFleetSnapshot(reconciled, fresh.copy(hosts = previous.hosts)).sessions.isEmpty())
    }

    @Test fun cacheDoesNotCrossFleetIdentityOrOutliveItsDisplayBudget() {
        val previous = snapshot()
        val fresh = previous.copy(fleetId = "replacement", sessions = emptyList(),
            hosts = previous.hosts.map { it.copy(status = "connecting") })
        assertTrue(reconcileFleetSnapshot(previous, fresh).sessions.isEmpty())
        val expired = previous.copy(receivedAtMillis = 1)
        assertTrue(reconcileFleetSnapshot(expired, fresh.copy(fleetId = previous.fleetId), now = 600_001).sessions.isEmpty())
    }
}
