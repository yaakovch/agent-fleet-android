package com.termux.app.fleet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HostRuntimeContractTest {
    private fun fixture(): JSONObject =
        JSONObject(requireNotNull(
            javaClass.classLoader?.getResource("contracts/host-runtime-conformance-v1.json")
        ).readText())

    @Test
    fun usesOnePublicEntrypointWithIsolatedBoundedChannels() {
        val value = fixture()
        assertEquals("wtmux-host-runtime", value.getString("entrypoint"))
        assertEquals(1, value.getInt("apiVersion"))
        val channels = value.getJSONArray("channels")
        assertEquals(
            listOf("control", "conversation", "terminal", "transfer", "repository"),
            (0 until channels.length()).map { channels.getJSONObject(it).getString("id") }
        )
        val control = (0 until channels.length())
            .map(channels::getJSONObject)
            .single { it.getString("id") == "control" }
        assertEquals("metadata-only", control.getString("privacy"))
        assertEquals(1, control.getInt("maxConcurrent"))
        val budgets = value.getJSONObject("resourceBudgets")
        assertEquals(1, budgets.getInt("maxInFlightControl"))
        assertEquals(1, budgets.getInt("maxChildProcessesPerControl"))
        assertEquals(262_144, budgets.getInt("maxHelperOutputBytes"))
        assertEquals(8_192, budgets.getInt("maxHelperErrorBytes"))
    }

    @Test
    fun implementsEveryCanonicalErrorWithTheSamePublicRecovery() {
        val errors = fixture().getJSONArray("errors")
        val codes = (0 until errors.length()).map {
            errors.getJSONObject(it).getString("code")
        }.toSet()
        assertEquals(HostRuntimeContract.recovery.keys, codes)
        repeat(errors.length()) { index ->
            val error = errors.getJSONObject(index)
            val recovery = HostRuntimeContract.recoveryFor(error.getString("code"))
            assertNotNull(recovery)
            assertEquals(error.getString("publicTitle"), recovery?.title)
        }
    }

    @Test
    fun bindsMutationsAndRejectsTheSessionIndexPrototype() {
        val value = fixture()
        val operations = value.getJSONArray("operations")
        val bindings = (0 until operations.length()).associate {
            val operation = operations.getJSONObject(it)
            operation.getString("id") to operation.getString("mutationBinding")
        }
        assertEquals("revision-and-idempotency", bindings["session"])
        assertEquals("config-revision-and-idempotency", bindings["model-control"])
        assertEquals("provider-revision-and-idempotency", bindings["conversation"])
        val sessionIndex = value.getJSONObject("sessionIndex")
        assertEquals("rejected", sessionIndex.getString("state"))
        assertEquals("tmux", sessionIndex.getString("authority"))
        assertEquals("NO_MATERIAL_RECOVERY_VALUE", sessionIndex.getString("reason"))
        assertEquals("WTMUX_SESSION_INDEX", sessionIndex.getString("killSwitch"))
    }
}
