package com.termux.app.fleet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ControlResultContractTest {
    private fun fixture(name: String): String = requireNotNull(javaClass.classLoader?.getResource("contracts/$name")).readText()

    @Test
    fun acceptsEverySharedResultFamily() {
        val results = ControlResultContract.parseFixture(fixture("control-results-v1.json"))
        assertEquals(14, results.size)
        results.forEach { ControlResultContract.requireValidResult(JSONObject(it.toString())) }
    }

    @Test
    fun rejectsSharedInvalidResults() {
        listOf("control-result-unknown-field-v1.json", "control-result-content-field-v1.json").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                ControlResultContract.requireValidResult(JSONObject(fixture(name)))
            }
        }
    }

    @Test
    fun rejectsDeterministicFieldSizeAndNestingMutations() {
        val results = JSONObject(fixture("control-results-v1.json")).getJSONArray("results")
        repeat(results.length()) { index ->
            val mutated = JSONObject(results.getJSONObject(index).toString()).put("unexpected", true)
            assertThrows(IllegalArgumentException::class.java) { ControlResultContract.requireValidResult(mutated) }
        }
        val oversized = JSONObject()
            .put("rootName", "x".repeat(256 * 1024)).put("relativePath", "").put("parentPath", JSONObject.NULL)
            .put("entries", org.json.JSONArray()).put("nextCursor", JSONObject.NULL).put("truncated", false)
        assertThrows(IllegalArgumentException::class.java) { ControlResultContract.requireValidResult(oversized) }
        var nested = JSONObject().put("unexpected", true)
        repeat(18) { nested = JSONObject().put("nested", nested) }
        assertThrows(IllegalArgumentException::class.java) { ControlResultContract.requireValidResult(nested) }
    }

    @Test
    fun requiresTheStableHostRuntimeBeforeSessionDiscovery() {
        val results = JSONObject(fixture("control-results-v1.json")).getJSONArray("results")
        val agent = JSONObject(results.getJSONObject(0).toString())
        agent.getJSONObject("hostRuntime").put("entrypoint", "private-helper")
        assertThrows(IllegalArgumentException::class.java) {
            ControlResultContract.requireValidResult(agent)
        }

        val bridge = JSONObject(results.getJSONObject(1).toString())
            .put("hostRuntime", JSONObject(results.getJSONObject(0).getJSONObject("hostRuntime").toString()))
        assertThrows(IllegalArgumentException::class.java) {
            ControlResultContract.requireValidResult(bridge)
        }
    }
}
