package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentFleetUpdateManifestParserTest {
    private val valid = """{
      "schemaVersion": 1,
      "applicationId": "com.yaakovch.fleet",
      "versionCode": 1003,
      "versionName": "0.118.4-agentfleet.1",
      "apkUrl": "https://fleet.example/agent-fleet.apk",
      "apkSha256": "${"ab".repeat(32)}",
      "certificateSha256": "${"cd".repeat(32)}",
      "size": 123456
    }"""

    @Test fun parsesStrictManifest() {
        val update = AgentFleetUpdateManifestParser.parse(valid)
        assertEquals(1003, update.versionCode)
        assertEquals("com.yaakovch.fleet", update.applicationId)
        assertEquals("0.118.4-agentfleet.1", update.versionName)
    }

    @Test fun rejectsInsecureApkUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentFleetUpdateManifestParser.parse(valid.replace("https://", "http://"))
        }
    }

    @Test fun rejectsMalformedChecksum() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentFleetUpdateManifestParser.parse(valid.replace("${"ab".repeat(32)}", "abc"))
        }
    }

    @Test fun rejectsTheLegacyUpdateLane() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentFleetUpdateManifestParser.parse(valid.replace("com.yaakovch.fleet", "com.termux"))
        }
    }
}
