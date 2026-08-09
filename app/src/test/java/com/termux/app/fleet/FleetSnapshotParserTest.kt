package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
          "presentationRevision":"fedcba9876543210",
          "generatedAt":"2026-07-12T05:00:00Z",
          "hosts":[{"id":"gaming","name":"Gaming desktop","platform":"wsl","transport":"tailscale","status":"healthy","lastSeenAt":null,"errorCode":"","capabilities":["sessions.read"],"wtmuxVersion":"test","agentVersion":"0.1.0","protocolVersion":1,"timeZone":"Asia/Jerusalem"}],
          "sessions":[{"id":"gaming:wtmux-main","hostId":"gaming","internalName":"wtmux-main","name":"wtmux:1","title":"Android companion","nameMode":"automatic","project":"wtmux","tool":"codex","backend":"linux","activity":"active","attached":true,"updatedAt":"2026-07-12T05:00:00Z","pendingScheduleCount":1}],
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
        assertEquals("automatic", snapshot.sessions.single().nameMode)
        assertEquals("fedcba9876543210", snapshot.presentationRevision)
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
    fun limitIdMatchesTheCanonical320CharacterBound() {
        val fixture = requireNotNull(javaClass.classLoader?.getResource("fleet_snapshot_v1.json")).readText()
        val atLimit = JSONObject(fixture)
        atLimit.getJSONArray("limits").getJSONObject(0).put("id", "x".repeat(320))
        assertEquals(320, FleetSnapshotParser.parse(atLimit.toString()).limits.single().id.length)

        val overLimit = JSONObject(fixture)
        overLimit.getJSONArray("limits").getJSONObject(0).put("id", "x".repeat(321))
        assertThrows(IllegalArgumentException::class.java) { FleetSnapshotParser.parse(overLimit.toString()) }
    }

    @Test
    fun parsesNegotiatedCanonicalIdentityWithoutChangingLegacySessionId() {
        val fixture = requireNotNull(javaClass.classLoader?.getResource("contracts/fleet-snapshot-identity-v1.json")).readText()
        val snapshot = FleetSnapshotParser.parse(fixture)
        assertEquals("agent-fleet", snapshot.fleetId)
        assertEquals("gaming-desktop", snapshot.physicalHosts.single().id)
        assertEquals(listOf("linux", "windows"), snapshot.physicalHosts.single().executionTargetIds)
        assertEquals("verified", snapshot.endpoints.single().identityState)
        assertEquals("gaming-desktop-ubuntu:wtmux-project-1", snapshot.sessions.single().id)
        assertEquals("gaming-desktop", snapshot.sessions.single().physicalHostId)
        assertEquals("linux", snapshot.sessions.single().executionTargetId)
    }

    @Test
    fun rejectsDuplicateOrInconsistentCanonicalIdentityMappings() {
        val fixture = requireNotNull(javaClass.classLoader?.getResource("contracts/fleet-snapshot-identity-v1.json")).readText()
        val duplicate = JSONObject(fixture)
        duplicate.getJSONArray("executionTargets").put(
            JSONObject(duplicate.getJSONArray("executionTargets").getJSONObject(0).toString())
        )
        assertThrows(IllegalArgumentException::class.java) { FleetSnapshotParser.parse(duplicate.toString()) }

        val inconsistent = JSONObject(fixture)
        inconsistent.getJSONArray("sessions").getJSONObject(0).put("executionTargetId", "windows")
        assertThrows(IllegalArgumentException::class.java) { FleetSnapshotParser.parse(inconsistent.toString()) }

        val collidingNamespace = JSONObject(fixture)
        val hosts = collidingNamespace.getJSONArray("physicalHosts")
        hosts.put(JSONObject(hosts.getJSONObject(0).toString())
            .put("id", hosts.getJSONObject(0).getJSONArray("legacyHostIds").getString(0))
            .put("legacyHostIds", JSONArray().put("other-legacy-host")))
        assertThrows(IllegalArgumentException::class.java) {
            FleetSnapshotParser.parse(collidingNamespace.toString())
        }
    }

    @Test
    fun rejectsDuplicateAlertSourceIds() {
        listOf("hosts", "schedules", "attention").forEach { field ->
            val duplicate = JSONObject(validSnapshot)
            val values = duplicate.getJSONArray(field)
            val second = JSONObject(values.getJSONObject(0).toString())
            when (field) {
                "hosts" -> second.put("name", "Other display name")
                "schedules" -> second.put("status", "delivered")
                "attention" -> second.put("state", "resolved")
            }
            values.put(second)
            assertThrows(IllegalArgumentException::class.java) { FleetSnapshotParser.parse(duplicate.toString()) }
        }

        val pairing = JSONObject()
            .put("id", "pair")
            .put("deviceName", "Review phone")
            .put("platform", "Android")
            .put("peer", "phone.example.ts.net")
            .put("requestedAt", "2026-07-12T05:00:00Z")
            .put("expiresAt", "2026-07-12T05:10:00Z")
            .put("status", "awaiting-review")
        val duplicatePairing = JSONObject(validSnapshot).put(
            "pairingRequests",
            JSONArray().put(pairing).put(JSONObject(pairing.toString()).put("deviceName", "Other phone"))
        )
        assertThrows(IllegalArgumentException::class.java) { FleetSnapshotParser.parse(duplicatePairing.toString()) }
    }

    @Test
    fun rejectsDuplicateLimitAndPresetIds() {
        val quotaFixture = requireNotNull(javaClass.classLoader?.getResource("fleet_snapshot_v1.json")).readText()
        val duplicateLimit = JSONObject(quotaFixture)
        val limits = duplicateLimit.getJSONArray("limits")
        limits.put(JSONObject(limits.getJSONObject(0).toString()).put("profileAlias", "Other profile"))
        assertThrows(IllegalArgumentException::class.java) { FleetSnapshotParser.parse(duplicateLimit.toString()) }

        val preset = JSONObject()
            .put("id", "favorite-duplicate")
            .put("name", "Demo Codex")
            .put("hostId", "gaming")
            .put("project", "wtmux")
            .put("backend", "linux")
            .put("tool", "codex")
            .put("profileAlias", "")
        val duplicatePreset = JSONObject(validSnapshot).put(
            "presets",
            JSONArray().put(preset).put(JSONObject(preset.toString()).put("name", "Other preset"))
        )
        assertThrows(IllegalArgumentException::class.java) { FleetSnapshotParser.parse(duplicatePreset.toString()) }

        val unknownHostPreset = JSONObject(validSnapshot).put(
            "presets",
            JSONArray().put(JSONObject(preset.toString()).put("id", "favorite-unknown").put("hostId", "unknown"))
        )
        assertThrows(IllegalArgumentException::class.java) { FleetSnapshotParser.parse(unknownHostPreset.toString()) }
    }

    @Test
    fun rejectsSharedUnknownAndPrivateSnapshotFields() {
        listOf("fleet-snapshot-unknown-field-v1.json", "fleet-snapshot-content-field-v1.json").forEach { name ->
            val fixture = requireNotNull(javaClass.classLoader?.getResource("contracts/$name")).readText()
            assertThrows(IllegalArgumentException::class.java) { FleetSnapshotParser.parse(fixture) }
        }
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
        assertEquals("wtmux:1", session.name)
    }

    @Test
    fun rejectsNonEmptyTitleWithoutNegotiationMetadata() {
        val root = JSONObject(validSnapshot)
        root.remove("presentationRevision")
        root.getJSONArray("sessions").getJSONObject(0).remove("nameMode")
        assertTrue(runCatching { FleetSnapshotParser.parse(root.toString()) }.exceptionOrNull() is IllegalArgumentException)
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

    @Test
    fun retainsOnlyBoundedCanonicalAlertSourceFields() {
        val root = JSONObject(validSnapshot)
        root.getJSONArray("schedules").getJSONObject(0).put("status", "failed").put("outcomeCode", "host_unavailable")
        root.put(
            "pairingRequests",
            JSONArray().put(
                JSONObject()
                    .put("id", "pair-1")
                    .put("deviceName", "Review phone")
                    .put("platform", "Android")
                    .put("peer", "phone.example.ts.net")
                    .put("requestedAt", "2026-07-12T05:00:00Z")
                    .put("expiresAt", "2026-07-12T05:10:00Z")
                    .put("status", "awaiting-review")
            )
        )

        val snapshot = FleetSnapshotParser.parse(root.toString())
        assertEquals("test", snapshot.hosts.single().wtmuxVersion)
        assertEquals("host_unavailable", snapshot.schedules.single().outcomeCode)
        assertEquals("Host unavailable", snapshot.schedules.single().detail)
        assertEquals("hard-limit", snapshot.attention.single().kind)
        assertEquals("Codex usage limit detected", snapshot.attention.single().title)
        assertEquals("gaming · resets 2026-07-12T06:00:00Z", snapshot.attention.single().detail)
        assertEquals("pair-1", snapshot.pairingRequests.single().id)
        assertEquals("awaiting-review", snapshot.pairingRequests.single().status)
    }

    @Test
    fun alertParsingKeepsHardLimitKindClosedAndPairingBounded() {
        val wrongAttentionKind = JSONObject(validSnapshot)
        wrongAttentionKind.getJSONArray("attention").getJSONObject(0).put("kind", "delivery")
        assertThrows(IllegalArgumentException::class.java) {
            FleetSnapshotParser.parse(wrongAttentionKind.toString())
        }

        val pairing = JSONObject()
            .put("id", "pair")
            .put("deviceName", "Review phone")
            .put("platform", "Android")
            .put("peer", "phone.example.ts.net")
            .put("requestedAt", "2026-07-12T05:00:00Z")
            .put("expiresAt", "2026-07-12T05:10:00Z")
            .put("status", "awaiting-review")
        val tooMany = JSONArray()
        repeat(MAX_FLEET_ALERT_PAIRING_STATES + 1) { index ->
            tooMany.put(JSONObject(pairing.toString()).put("id", "pair-$index"))
        }
        val oversizedPairing = JSONObject(validSnapshot).put("pairingRequests", tooMany)
        assertThrows(IllegalArgumentException::class.java) {
            FleetSnapshotParser.parse(oversizedPairing.toString())
        }
    }
}
