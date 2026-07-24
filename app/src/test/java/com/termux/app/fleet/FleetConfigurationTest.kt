package com.termux.app.fleet

import android.content.Context
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FleetConfigurationTest {
    private fun fixture(): String = requireNotNull(javaClass.classLoader)
        .getResourceAsStream("contracts/pairing-bundle-v1.json")!!.bufferedReader().readText()

    @Test
    fun parsesSharedIntegrityBundleWithPrimaryAndFallbackPolicy() {
        val value = FleetConfigurationParser.parse(fixture())
        assertEquals(7L, value.configurationRevision)
        assertEquals(2, value.clientPolicy.apkManifestUrls.size)
        assertEquals(2, value.clientPolicy.runtimeManifestUrls.size)
        assertEquals("verified", value.hostTrust.single().identityState)
    }

    @Test
    fun reviewsInvitationWithoutReturningOneTimeSecret() {
        val value = FleetConfigurationParser.reviewInvitation(
            "wtmux://pair?pairingVersion=1&bootstrapPeer=controller.tailnet.ts.net&bootstrapUser=controller" +
                "&token=pAAAAAAAAAAAAAAAAAAAAAA&expiresAt=2026-07-25T12%3A00%3A00Z",
            Instant.parse("2026-07-24T12:00:00Z")
        )
        assertEquals("controller.tailnet.ts.net", value.bootstrapPeer)
        assertFalse(value.expired)
        assertFalse(value.toString().contains("pAAAAAAAA"))
    }

    @Test
    fun corruptReplacementCannotDisplaceLastHealthyConfiguration() {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = FleetConfigurationStore(context)
        val activated = store.activate(fixture())
        assertEquals(7L, activated.configurationRevision)
        assertThrows(IllegalArgumentException::class.java) {
            store.activate(fixture().replace("\"gaming\"", "\"changed\""))
        }
        assertEquals(activated.digest, store.current()?.digest)
    }

    @Test
    fun exportContainsOnlyCanonicalPairingAllowlist() {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = FleetConfigurationStore(context)
        store.activate(fixture())
        val exported = store.export()
        assertTrue(exported.contains("\"clientPolicy\""))
        assertTrue(exported.contains("\"hostTrust\""))
        assertFalse(exported.lowercase().contains("transcript"))
        assertFalse(exported.lowercase().contains("credential"))
        FleetConfigurationParser.parse(exported)
    }
}
