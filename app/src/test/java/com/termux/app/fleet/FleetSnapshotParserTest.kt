package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
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
    fun parsesQuotaProfilesFromNewerBridge() {
        val fixture = requireNotNull(javaClass.classLoader?.getResource("fleet_snapshot_v1.json")).readText()
        val limit = FleetSnapshotParser.parse(fixture).limits.single()
        assertEquals("Codex 2", limit.profileAlias)
        assertEquals(76.0, limit.primary?.remainingPercent)
        assertEquals(10080, limit.secondary?.windowMinutes)
    }

    @Test
    fun rejectsSessionForUnknownHost() {
        val invalid = validSnapshot.replace("\"hostId\":\"gaming\"", "\"hostId\":\"unknown\"", ignoreCase = false)
        val error = runCatching { FleetSnapshotParser.parse(invalid) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun acceptsAdditiveSessionPathMetadata() {
        val updated = validSnapshot.replace(
            "\"pendingScheduleCount\":1",
            "\"pendingScheduleCount\":1,\"projectPath\":\"/srv/work\",\"locationKind\":\"custom\""
        )
        val session = FleetSnapshotParser.parse(updated).sessions.single()
        assertEquals("/srv/work", session.projectPath)
        assertEquals("custom", session.locationKind)
        assertEquals("wtmux", session.name)
    }

    @Test
    fun keepsOnlyActiveAttentionStates() {
        val states = listOf("detected", "offering", "offered", "scheduled", "resolved", "expired", "future")
        val items = JSONArray()
        states.forEachIndexed { index, state ->
            items.put(JSONObject()
                .put("id", "limit-$index")
                .put("hostId", "gaming")
                .put("kind", "hard-limit")
                .put("sessionId", "gaming:wtmux-main")
                .put("agent", "codex")
                .put("resetAt", "2026-07-12T06:00:00Z")
                .put("state", state)
                .put("detectedAt", "2026-07-12T05:00:00Z")
                .put("updatedAt", "2026-07-12T05:00:00Z"))
        }
        val updated = JSONObject(validSnapshot).put("attention", items).toString()
        assertEquals(listOf("detected", "offering", "offered"), FleetSnapshotParser.parse(updated).attention.map { it.state })
    }
}
