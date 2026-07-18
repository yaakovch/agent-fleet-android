package com.termux.app.fleet

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FleetModelControlProtocolTest {
    private fun selection(model: String = "auto", effort: String = "automatic") = JSONObject()
        .put("modelId", model)
        .put("modelLabel", if (model == "auto") "Auto" else model)
        .put("effortId", effort)
        .put("effortLabel", if (effort == "automatic") "Automatic" else effort)

    private fun payload() = JSONObject()
        .put("sessionId", "gaming:managed-one")
        .put("configRevision", "0123456789abcdef")
        .put("tool", "codex")
        .put("status", "ready")
        .put("selected", selection())
        .put("effective", JSONObject.NULL)
        .put("pending", JSONObject.NULL)
        .put("catalog", JSONObject()
            .put("customAllowed", true)
            .put("models", JSONArray().put(JSONObject()
                .put("id", "provider/model-2")
                .put("label", "Provider Model 2")
                .put("description", "Discovered on the host")
                .put("isDefault", false)
                .put("efforts", JSONArray()
                    .put(JSONObject().put("id", "low").put("label", "Low"))
                    .put(JSONObject().put("id", "high").put("label", "High")))
                .put("defaultEffort", "high"))))
        .put("detail", "")

    @Test
    fun parsesBoundedCatalogWithoutAddingItToFleetSnapshot() {
        val runtime = FleetRuntime(RuntimeEnvironment.getApplication())
        val state = runtime.parseModelControl(payload(), "gaming:managed-one")
        assertEquals("codex", state.tool)
        assertEquals("provider/model-2", state.catalog?.single()?.id)
        assertEquals(listOf("low", "high"), state.catalog?.single()?.efforts?.map { it.id })
        assertNull(state.effective)
    }

    @Test
    fun rejectsUnknownFieldsAndUnsafeCustomIdentifiers() {
        val runtime = FleetRuntime(RuntimeEnvironment.getApplication())
        assertThrows(IllegalArgumentException::class.java) {
            runtime.parseModelControl(payload().put("transcript", "private"), "gaming:managed-one")
        }
        val unsafe = payload().apply {
            getJSONObject("catalog").getJSONArray("models").getJSONObject(0).put("id", "bad model; rm -rf")
        }
        assertThrows(IllegalArgumentException::class.java) {
            runtime.parseModelControl(unsafe, "gaming:managed-one")
        }
    }

    @Test
    fun rejectsCrossSessionResponses() {
        val runtime = FleetRuntime(RuntimeEnvironment.getApplication())
        assertThrows(IllegalArgumentException::class.java) {
            runtime.parseModelControl(payload(), "work:other")
        }
    }
}
