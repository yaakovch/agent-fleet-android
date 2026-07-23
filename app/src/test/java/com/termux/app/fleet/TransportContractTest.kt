package com.termux.app.fleet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransportContractTest {
    private fun fixture(): JSONObject =
        JSONObject(requireNotNull(
            javaClass.classLoader?.getResource("contracts/transport-conformance-v1.json")
        ).readText())

    @Test fun everyCanonicalFailureHasTheSameVisibleRecovery() {
        val value = fixture()
        assertEquals("openssh", value.getString("defaultEngine"))
        assertEquals("tailscale-cli", value.getString("fallbackEngine"))
        val failures = value.getJSONArray("failures")
        val codes = (0 until failures.length()).map {
            failures.getJSONObject(it).getString("code")
        }.toSet()
        assertEquals(TransportContract.recovery.keys, codes)
        codes.forEach { assertNotNull(TransportContract.recoveryFor(it)) }
    }

    @Test fun unprovenOptimizationsRemainDisabled() {
        val value = fixture()
        assertEquals("disabled", value.getString("connectionReuseDefault"))
        assertEquals("stream", value.getString("transferModeDefault"))
        assertEquals(
            "disabled",
            value.getJSONObject("decisions").getJSONObject("connectionReuse").getString("state")
        )
        assertEquals(
            "rejected",
            value.getJSONObject("decisions").getJSONObject("sftp").getString("state")
        )
    }

    @Test fun legacyFailuresUseStableRecoveryCodes() {
        assertEquals("SSH_AUTH_REQUIRED", TransportContract.stableCode("auth_failure"))
        assertEquals(
            "Endpoint identity changed",
            TransportContract.recoveryFor("HOST_KEY_CHANGED")?.title
        )
    }
}
