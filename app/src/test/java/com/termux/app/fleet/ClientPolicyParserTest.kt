package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClientPolicyParserTest {
    private val valid = """
        {
          "schemaVersion": 1,
          "policyRevision": 7,
          "apkManifestUrls": ["https://controller.tailnet.ts.net/app/manifest.json"],
          "runtimeManifestUrls": ["https://controller.tailnet.ts.net/runtime/manifest.json"],
          "artifactOrigins": ["https://controller.tailnet.ts.net"],
          "checkIntervalSeconds": 21600
        }
    """.trimIndent()

    @Test
    fun parsesStrictPairingPolicy() {
        val policy = ClientPolicyParser.parse(valid)
        assertEquals(7, policy.policyRevision)
        assertEquals(21600, policy.checkIntervalSeconds)
        assertEquals(setOf("https://controller.tailnet.ts.net"), policy.artifactOrigins)
    }

    @Test
    fun rejectsCredentialsHttpAndUnknownFields() {
        assertThrows(IllegalArgumentException::class.java) {
            ClientPolicyParser.parse(valid.replace("https://", "http://"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClientPolicyParser.parse(valid.replace("controller.tailnet", "user:password@controller.tailnet"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClientPolicyParser.parse(valid.replace("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"prompt\": \"private\","))
        }
    }
}
