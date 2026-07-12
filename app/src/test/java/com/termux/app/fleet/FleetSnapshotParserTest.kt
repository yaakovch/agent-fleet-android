package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FleetSnapshotParserTest {
    private val validSnapshot = """
        {
          "revision":"0123456789abcdef",
          "generatedAt":"2026-07-12T05:00:00Z",
          "hosts":[{"id":"gaming","name":"Gaming desktop","platform":"wsl","transport":"tailscale","status":"healthy","lastSeenAt":null,"errorCode":"","capabilities":["sessions.read"],"wtmuxVersion":"test","agentVersion":"0.1.0","protocolVersion":1,"timeZone":"Asia/Jerusalem"}],
          "sessions":[{"id":"gaming:wtmux-main","hostId":"gaming","internalName":"wtmux-main","name":"wtmux","title":"Android companion","project":"wtmux","tool":"codex","backend":"linux","activity":"active","attached":true,"updatedAt":"2026-07-12T05:00:00Z","pendingScheduleCount":1}],
          "schedules":[{"id":"schedule-1","hostId":"gaming","sessionId":"gaming:wtmux-main","kind":"scheduled-message","backend":"linux","agent":"codex","deliverAt":"2026-07-12T06:00:00Z","status":"pending","createdAt":"2026-07-12T05:00:00Z","updatedAt":"2026-07-12T05:00:00Z","completedAt":null,"outcomeCode":""}],
          "attention":[{"id":"limit-1","hostId":"gaming","kind":"hard-limit","sessionId":"gaming:wtmux-main","agent":"codex","resetAt":"2026-07-12T06:00:00Z","state":"detected","detectedAt":"2026-07-12T05:00:00Z","updatedAt":"2026-07-12T05:00:00Z"}],
          "presets":[]
        }
    """.trimIndent()

    @Test
    fun parsesMetadataSnapshot() {
        val snapshot = FleetSnapshotParser.parse(validSnapshot)
        assertEquals("Gaming desktop", snapshot.hosts.single().name)
        assertEquals("wtmux-main", snapshot.sessions.single().internalName)
        assertEquals(1, snapshot.sessions.single().pendingScheduleCount)
        assertEquals("2026-07-12T06:00:00Z", snapshot.attention.single().resetAt)
    }

    @Test
    fun rejectsSessionForUnknownHost() {
        val invalid = validSnapshot.replace("\"hostId\":\"gaming\"", "\"hostId\":\"unknown\"", ignoreCase = false)
        val error = runCatching { FleetSnapshotParser.parse(invalid) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
