package com.termux.app.fleet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SupervisorContractTest {
    private fun fixture(): JSONObject = JSONObject(
        requireNotNull(javaClass.classLoader?.getResource("contracts/supervisor-conformance-v1.json")).readText()
    )

    @Test
    fun implementsEveryCanonicalLifecycleScenario() {
        val root = fixture()
        val contract = root.getJSONObject("contract")
        assertEquals(SUPERVISOR_MAX_FRAME_BYTES, contract.getInt("maxFrameBytes"))
        assertEquals(SUPERVISOR_MAX_QUEUED_CONTROL, contract.getInt("maxQueuedControl"))
        assertEquals(SUPERVISOR_MAX_IN_FLIGHT_CONTROL, contract.getInt("maxInFlightControl"))
        assertEquals(SUPERVISOR_REQUEST_DEADLINE_MS, contract.getLong("requestDeadlineMs"))
        assertEquals(SUPERVISOR_HEARTBEAT_TIMEOUT_MS, contract.getLong("heartbeatTimeoutMs"))
        assertEquals(
            SUPERVISOR_RECONNECT_DELAYS_MS.toList(),
            contract.getJSONArray("reconnectDelaysMs").let { array -> (0 until array.length()).map(array::getLong) }
        )
        val scenarios = root.getJSONArray("scenarios")
        repeat(scenarios.length()) { scenarioIndex ->
            val scenario = scenarios.getJSONObject(scenarioIndex)
            var state = state(scenario.getJSONObject("initial"))
            val steps = scenario.getJSONArray("steps")
            repeat(steps.length()) { stepIndex ->
                val step = steps.getJSONObject(stepIndex)
                val action = step.getJSONObject("action")
                state = reduceSupervisorState(
                    state,
                    SupervisorAction(action.getString("type"), action.optString("channel"))
                )
                assertEquals(
                    "${scenario.getString("id")}:${action.getString("type")}",
                    state(step.getJSONObject("expected")),
                    state
                )
            }
        }
    }

    @Test
    fun foregroundOwnersStartOnceAndStopAfterTheLastContinuousSurface() {
        val owners = ForegroundSupervisorOwners()
        val launcher = Any()
        val native = Any()
        assertEquals(true, owners.set(launcher, true))
        assertNull(owners.set(native, true))
        assertNull(owners.remove(launcher))
        assertTrue(owners.isActive())
        assertEquals(false, owners.remove(native))
        assertFalse(owners.isActive())
    }

    private fun state(value: JSONObject) = SupervisorState(
        phase = value.getString("phase"),
        foreground = value.getBoolean("foreground"),
        connectionGeneration = value.getInt("connectionGeneration"),
        controlProcessCount = value.getInt("controlProcessCount"),
        health = value.getString("health"),
        lastError = value.getString("lastError")
    )
}
