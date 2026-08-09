package com.termux.app.fleet

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FleetPairingReviewProtocolTest {
    private val runtime by lazy { FleetRuntime(RuntimeEnvironment.getApplication()) }

    @Test
    fun parsesTheExactBoundedProposalForHumanReview() {
        val review = runtime.parsePairingReview(payload(), "pair-1")

        assertEquals("pair-1", review.requestId)
        assertEquals("Phone", review.deviceName)
        assertEquals("phone.tailnet.ts.net", review.peer)
        assertTrue(review.proposalJson.contains("\"roles\""))
        assertTrue(review.proposalJson.contains("\"hostCommand\""))

        val rtlName = "משפחה\u200D"
        val rtlPayload = payload().put("deviceName", rtlName).apply {
            getJSONObject("proposal").put("name", rtlName)
        }
        val rtlWithJoiner = runtime.parsePairingReview(rtlPayload, "pair-1")
        assertEquals("משפחה\u200D", rtlWithJoiner.deviceName)
    }

    @Test
    fun rejectsUnknownFieldsIdentityChangesAndControlContent() {
        assertThrows(IllegalArgumentException::class.java) {
            runtime.parsePairingReview(payload().put("prompt", "private"), "pair-1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            runtime.parsePairingReview(payload(), "pair-other")
        }
        assertThrows(IllegalArgumentException::class.java) {
            runtime.parsePairingReview(
                payload().apply { getJSONObject("proposal").put("hostCommand", "run\nsecret") },
                "pair-1"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            runtime.parsePairingReview(
                payload().apply { getJSONObject("proposal").put("hostCommand", "safe\u202Etxt.exe") },
                "pair-1"
            )
        }
        listOf("1", 1.5).forEach { invalidVersion ->
            assertThrows(IllegalArgumentException::class.java) {
                runtime.parsePairingReview(
                    payload().apply { getJSONObject("proposal").put("schemaVersion", invalidVersion) },
                    "pair-1"
                )
            }
        }
        assertThrows(java.time.format.DateTimeParseException::class.java) {
            runtime.parsePairingReview(payload().put("requestedAt", "not-an-instant"), "pair-1")
        }
        listOf(
            payload().put("deviceId", "other-phone"),
            payload().put("deviceName", "Other phone"),
            payload().put("platform", "Other platform"),
            payload().apply { getJSONObject("proposal").put("tailscaleNode", "other.tailnet.ts.net") }
        ).forEach { mismatched ->
            assertThrows(IllegalArgumentException::class.java) {
                runtime.parsePairingReview(mismatched, "pair-1")
            }
        }

        val fallbackOnly = payload().apply { getJSONObject("proposal").put("tailscaleNode", "") }
        assertEquals("pair-1", runtime.parsePairingReview(fallbackOnly, "pair-1").requestId)
    }

    private fun payload(): JSONObject = JSONObject()
        .put("id", "pair-1")
        .put("invitationId", "invite-1")
        .put("deviceId", "phone-1")
        .put("deviceName", "Phone")
        .put("platform", "Android")
        .put("peer", "phone.tailnet.ts.net")
        .put("peerIp", "100.64.0.10")
        .put("requestedAt", "2026-08-01T00:00:00Z")
        .put("expiresAt", "2026-08-01T01:00:00Z")
        .put("reviewedAt", JSONObject.NULL)
        .put("status", "awaiting-review")
        .put("publicationRef", JSONObject.NULL)
        .put("proposal", JSONObject()
            .put("schemaVersion", 1)
            .put("id", "phone-1")
            .put("name", "Phone")
            .put("roles", JSONArray(listOf("client")))
            .put("platform", "Android")
            .put("linuxUsername", "")
            .put("tailscaleNode", "phone.tailnet.ts.net")
            .put("projectsRoot", "")
            .put("transport", "tailscale")
            .put("wslDistro", "")
            .put("fallback", JSONObject().put("sshHost", "").put("ip", ""))
            .put("hostCommand", ""))
}
