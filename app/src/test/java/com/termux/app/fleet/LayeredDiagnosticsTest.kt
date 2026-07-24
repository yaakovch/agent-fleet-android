package com.termux.app.fleet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LayeredDiagnosticsTest {
    @Test
    fun acceptsCanonicalFixtureWithEveryReadOnlyLayer() {
        val root = JSONObject(fixture("diagnostics-v2.json"))
        LayeredDiagnostics.requireValid(root)
        val checks = root.getJSONArray("checks")
        assertEquals(LayeredDiagnostics.layers, List(checks.length()) { checks.getJSONObject(it).getString("layer") })
        assertTrue((0 until checks.length()).all { checks.getJSONObject(it).getBoolean("readOnly") })
    }

    @Test
    fun rejectsContentFieldsAndPrivatePaths() {
        assertThrows(IllegalArgumentException::class.java) {
            LayeredDiagnostics.requireValid(JSONObject(fixture("diagnostics-content-field-v2.json")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LayeredDiagnostics.requireValid(JSONObject(fixture("diagnostics-private-path-v2.json")))
        }
    }

    @Test
    fun reportDoesNotCopyLegacyIdentityOrEventContent() {
        val secret = "secret-canary-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val report = AgentFleetDiagnosticReport(
            generatedAt = "2026-07-24T12:00:00Z",
            appVersion = "test", androidVersion = "16", device = "/private/device",
            abi = "x86_64", runtimeVersion = "runtime", fleetRevision = "private-host-id",
            checks = listOf(
                AgentFleetDiagnosticCheck("app", "App", DiagnosticStatus.Healthy, "Ready"),
                AgentFleetDiagnosticCheck("runtime", "Runtime", DiagnosticStatus.Healthy, "Ready"),
                AgentFleetDiagnosticCheck("downloads", "Downloads", DiagnosticStatus.Healthy, "Ready")
            ),
            events = listOf(AgentFleetDiagnosticEvent(
                "event", "2026-07-24T12:00:00Z", 0, "failure", "failure", 0,
                "failed", secret, "private-host", "private-session"
            )),
            correlationId = "diag-0123456789abcdef0123456789abcdef"
        )
        val output = report.diagnosticsJson()
        assertFalse(output.contains(secret))
        assertFalse(output.contains("private-host"))
        assertFalse(output.contains("private-session"))
        assertFalse(output.contains("/private"))
        LayeredDiagnostics.requireValid(JSONObject(output))
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.classLoader)
        .getResourceAsStream("contracts/$name")!!.bufferedReader().readText()
}
