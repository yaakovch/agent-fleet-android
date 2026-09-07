package com.termux.app.fleet

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FleetHostSetupTest {
    private fun node() = JSONObject().put("nodeId", "node-new").put("name", "New host")
        .put("address", "new.tailnet.ts.net").put("ip", "100.64.0.9")
        .put("platform", "linux").put("online", true).put("hostId", "")

    @Test fun discoversCandidatesWithoutPairingThem() {
        val result = parseTailnetHosts(JSONObject().put("schemaVersion", 1).put("nodes", JSONArray().put(node())).toString())
        assertEquals("node-new", result.single().nodeId)
        assertTrue(result.single().online)
        assertEquals("", result.single().hostId)
    }

    @Test fun rejectsDuplicateIdentityAndUnprovenReachability() {
        for (nodes in listOf(JSONArray().put(node()).put(node()), JSONArray().put(node().put("online", "true")))) {
            assertTrue(runCatching { parseTailnetHosts(JSONObject().put("schemaVersion", 1).put("nodes", nodes).toString()) }.isFailure)
        }
    }

    @Test fun reviewReportsMissingRuntimeAndBindsAnOpaqueReview() {
        val review = JSONObject().put("schemaVersion", 1).put("reviewId", "a".repeat(32))
            .put("prepared", JSONObject().put("runtimePresent", false).put("tmuxPresent", true)
                .put("record", JSONObject().put("name", "New host").put("tailscaleNode", "new.tailnet.ts.net").put("linuxUsername", "tester")))
        val parsed = parseTailnetHostReview(review.toString())
        assertEquals("a".repeat(32), parsed.reviewId)
        assertFalse(parsed.runtimePresent)
        assertTrue(parsed.tmuxPresent)
        review.put("reviewId", "../../unreviewed")
        assertTrue(runCatching { parseTailnetHostReview(review.toString()) }.isFailure)
    }

    @Test fun invalidResponseHasItsOwnRecovery() {
        assertEquals("retry", TransportContract.recoveryFor("HOST_RESPONSE_INVALID")?.actionKind)
        assertTrue(TransportContract.recoveryFor("HOST_RUNTIME_INCOMPATIBLE")!!.action.contains("Diagnostics"))
    }
}
