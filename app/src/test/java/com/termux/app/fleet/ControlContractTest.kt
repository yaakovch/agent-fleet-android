package com.termux.app.fleet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ControlContractTest {
    private fun fixture(name: String): String = requireNotNull(javaClass.classLoader?.getResource("contracts/$name")).readText()

    @Test
    fun acceptsExactlyOneSharedRequestForEveryMethod() {
        val frames = JSONObject(fixture("control-frames-v1.json")).getJSONArray("frames")
        val methods = mutableSetOf<String>()
        repeat(frames.length()) { index ->
            val frame = frames.getJSONObject(index)
            if (frame.optString("type") == "request") {
                ControlContract.requireValidRequest(frame)
                ControlContract.requireValidRequest(JSONObject(frame.toString()))
                methods += frame.getString("method")
            }
        }
        assertEquals(ControlContract.methods(), methods)
    }

    @Test
    fun rejectsSharedInvalidRequestsAndDeterministicMutations() {
        listOf("control-unknown-field-v1.json", "control-content-field-v1.json").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                ControlContract.requireValidRequest(JSONObject(fixture(name)))
            }
        }
        val frames = JSONObject(fixture("control-frames-v1.json")).getJSONArray("frames")
        (0 until frames.length()).map { frames.getJSONObject(it) }
            .filter { it.optString("type") == "request" }
            .forEach { request ->
                val unknown = JSONObject(request.toString()).put("unexpected", true)
                assertThrows(IllegalArgumentException::class.java) { ControlContract.requireValidRequest(unknown) }
            }
        val directory = (0 until frames.length()).map { frames.getJSONObject(it) }
            .first { it.optString("method") == "directory.list" }
        directory.getJSONObject("params").remove("idempotencyKey")
        assertThrows(IllegalArgumentException::class.java) { ControlContract.requireValidRequest(directory) }
        val oversized = JSONObject()
            .put("protocolVersion", 1).put("type", "request").put("requestId", "large")
            .put("method", "fleet.snapshot").put("timestamp", "2026-07-22T12:00:00Z")
            .put("params", JSONObject().put("unexpected", "x".repeat(256 * 1024)))
        assertThrows(IllegalArgumentException::class.java) { ControlContract.requireValidRequest(oversized) }
        var nested = JSONObject()
        repeat(18) { nested = JSONObject().put("nested", nested) }
        assertThrows(IllegalArgumentException::class.java) { ControlContract.requireValidRequest(nested) }
    }
}
