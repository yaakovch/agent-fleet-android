package com.termux.app

import com.termux.app.fleet.FleetHost
import com.termux.app.fleet.FleetAlert
import com.termux.app.fleet.FleetAlertCategory
import com.termux.app.fleet.FleetAlertTarget
import com.termux.app.fleet.FleetPhysicalHost
import com.termux.app.fleet.FleetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetAlertUiRoutingTest {
    @Test
    fun hostAlertResolvesPhysicalAndLegacyIdentities() {
        val snapshot = snapshotWith(
            FleetPhysicalHost(
                id = "gaming-pc",
                name = "Gaming PC",
                platform = "wsl",
                status = "healthy",
                lastSeenAt = null,
                errorCode = "",
                endpointIds = emptyList(),
                executionTargetIds = listOf("linux", "windows"),
                legacyHostIds = listOf("gaming", "gaming_windows")
            )
        )

        assertEquals("gaming-pc", physicalHostIdForFleetAlert(snapshot, "gaming-pc"))
        assertEquals("gaming-pc", physicalHostIdForFleetAlert(snapshot, "gaming"))
        assertEquals("gaming-pc", physicalHostIdForFleetAlert(snapshot, "gaming_windows"))
        assertNull(physicalHostIdForFleetAlert(snapshot, "removed-host"))
        assertNull(physicalHostIdForFleetAlert(null, "gaming"))
    }

    @Test
    fun hostScrollIndexAccountsForDynamicPairingAndScheduleRows() {
        assertEquals(11, moreHostLazyListIndex(0, 0, 0))
        assertEquals(23, moreHostLazyListIndex(4, 3, 5))
        assertThrows(IllegalArgumentException::class.java) {
            moreHostLazyListIndex(-1, 0, 0)
        }
    }

    @Test
    fun fullAlertQueuePreservesUnseenRoutesAndMaintainsOneOverflowSummary() {
        val unseen = List(MAX_QUEUED_FLEET_ALERT_ROUTES) { index -> route("unseen-$index") }
        val firstOverflow = enqueueFleetAlerts(unseen, listOf(route("next-update")))

        assertEquals(unseen, firstOverflow.take(MAX_QUEUED_FLEET_ALERT_ROUTES))
        assertEquals(MAX_QUEUED_FLEET_ALERT_ROUTES + 1, firstOverflow.size)
        assertEquals(1, firstOverflow.count { it.category == null })
        assertEquals(FleetAlertTarget.Dashboard, firstOverflow.last().target)

        val laterOverflow = enqueueFleetAlerts(firstOverflow, listOf(route("later-update")))
        assertEquals(unseen, laterOverflow.take(MAX_QUEUED_FLEET_ALERT_ROUTES))
        assertEquals(1, laterOverflow.count { it.category == null })
        assertEquals(firstOverflow.last(), laterOverflow.last())
        assertTrue(laterOverflow.last().body.contains("Existing unseen alerts were preserved"))

        val afterDismiss = enqueueFleetAlerts(firstOverflow.drop(1), listOf(route("newly-admitted")))
        assertEquals("newly-admitted", afterDismiss[MAX_QUEUED_FLEET_ALERT_ROUTES - 1].title)
        assertEquals(1, afterDismiss.count { it.category == null })
    }

    private fun snapshotWith(vararg hosts: FleetPhysicalHost) = FleetSnapshot(
        revision = "revision-1",
        generatedAt = "2026-08-09T00:00:00Z",
        hosts = listOf(FleetHost("gaming", "Gaming", "healthy", "wsl", null, emptySet())),
        sessions = emptyList(),
        schedules = emptyList(),
        attention = emptyList(),
        physicalHosts = hosts.toList()
    )

    private fun route(id: String) = FleetAlert(
        category = FleetAlertCategory.DeliveryFailures,
        title = id,
        body = "Delivery failed",
        target = FleetAlertTarget.Session(id)
    )
}
