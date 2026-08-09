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
    fun usesSharedGeneratedStructuralCatalog() {
        val catalog = JSONObject(fixture("structural-models-v1.json"))
        val protocols = catalog.getJSONObject("protocolVersions")
        assertEquals(GeneratedAgentFleetContracts.protocolVersions.keys, protocols.keys().asSequence().toSet())
        GeneratedAgentFleetContracts.protocolVersions.forEach { (id, version) -> assertEquals(version, protocols.getInt(id)) }
        val control = catalog.getJSONObject("controlRequestShapes")
        assertEquals(GeneratedAgentFleetContracts.controlRequestShapes.keys, control.keys().asSequence().toSet())
        GeneratedAgentFleetContracts.controlRequestShapes.forEach { (method, shape) ->
            val value = control.getJSONObject(method)
            assertEquals(shape.required, value.getJSONArray("required").let { array -> (0 until array.length()).map(array::getString) })
            assertEquals(shape.optional, value.getJSONArray("optional").let { array -> (0 until array.length()).map(array::getString) })
        }
    }

    @Test
    fun acceptsSharedCompatibilityMatrix() {
        val matrix = CompatibilityContract.parse(fixture("compatibility-v1.json"))
        assertEquals("1.10.0", matrix.contractPackageVersion)
        assertTrue(matrix.components.values.all(CompatibilityContract::supportsCurrentContracts))
    }

    @Test
    fun rejectsSharedInvalidCompatibilityMatrices() {
        listOf("compatibility-unknown-field-v1.json", "compatibility-content-field-v1.json").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { CompatibilityContract.parse(fixture(name)) }
        }
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
        assertTrue(
            report.getJSONArray("components").getJSONObject(0).getJSONArray("capabilities")
                .let { values -> (0 until values.length()).map(values::getString) }
                .contains("host-runtime.v1")
        )
        assertTrue(
            report.getJSONArray("components").getJSONObject(0).getJSONArray("capabilities")
                .let { values -> (0 until values.length()).map(values::getString) }
                .contains("provider-confidence.v1")
        )
    }
}
