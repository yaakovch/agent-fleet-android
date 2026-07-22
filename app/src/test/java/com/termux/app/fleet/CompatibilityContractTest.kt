package com.termux.app.fleet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompatibilityContractTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("contracts/$name")).readText()

    @Test
    fun acceptsSharedCompatibilityMatrix() {
        val matrix = CompatibilityContract.parse(fixture("compatibility-v1.json"))
        assertEquals("1.0.0", matrix.contractPackageVersion)
        assertTrue(matrix.components.values.all(CompatibilityContract::supportsCurrentContracts))
    }

    @Test
    fun acceptsSharedDiagnosticsAndRejectsSameInvalidFixtures() {
        CompatibilityContract.requireValidDiagnostics(JSONObject(fixture("diagnostics-v1.json")))
        listOf("diagnostics-unknown-field-v1.json", "diagnostics-content-field-v1.json").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                CompatibilityContract.requireValidDiagnostics(JSONObject(fixture(name)))
            }
        }
    }

    @Test
    fun createsCanonicalCompatibilityStatus() {
        val report = JSONObject(CompatibilityContract.diagnosticsJson(
            "android-app", "test", DiagnosticStatus.Healthy, "2026-07-22T12:00:00Z"
        ))
        CompatibilityContract.requireValidDiagnostics(report)
        assertEquals("healthy", report.getJSONArray("checks").getJSONObject(0).getString("status"))
    }
}
