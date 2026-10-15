package com.termux.app.fleet

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class SessionWorkStatusTest {
    @Test fun neverInfersWorkFromOutputRecencyOrConnection() {
        assertEquals("unknown", sessionWorkPresentation(null).state)
        assertEquals("offline", sessionWorkPresentation(null, false).state)
    }
    @Test fun cachedObservationExpiresAtOriginalDeadline() {
        val observation = SessionWorkStatus("working", "2026-10-15T00:00:00Z")
        val now = Instant.parse(observation.observedAt).toEpochMilli()
        assertEquals("working", sessionWorkPresentation(observation, true, now + 9_999).state)
        assertEquals("unknown", sessionWorkPresentation(observation, true, now + 10_000).state)
        assertEquals("unknown", sessionWorkPresentation(observation, true, now - 2_001).state)
        assertEquals("offline", sessionWorkPresentation(observation, false, now).state)
    }
    @Test fun sharedFixturesDecodeAllStatesAndRejectInvalidMetadata() {
        fun fixture(name: String) = javaClass.classLoader!!.getResourceAsStream("contracts/$name")!!.bufferedReader().use { it.readText() }
        val snapshot = FleetSnapshotParser.parse(fixture("fleet-snapshot-work-status-v1.json"))
        assertEquals(SESSION_WORK_STATES, snapshot.sessions.map { it.workStatus!!.state }.toSet())
        for (name in listOf("fleet-snapshot-work-state-v1.json", "fleet-snapshot-work-time-v1.json")) {
            assertThrows(Exception::class.java) { FleetSnapshotParser.parse(fixture(name)) }
        }
        assertNull(FleetSnapshotParser.parse(fixture("fleet-snapshot-base-v1.json")).sessions.first().workStatus)
        assertEquals(listOf("--stdio", "--identity-graph", "--work-status", "--session-titles"), stdioBridgeArguments(true, true, true, true))
    }
}
