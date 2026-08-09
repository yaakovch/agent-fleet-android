package com.termux.app.fleet

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
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

    @Test
    fun interruptedActivationIsReplayedBeforeConfigurationIsRead() {
        val context: Context = RuntimeEnvironment.getApplication()
        val root = File(context.filesDir, "fleet-configuration")
        root.deleteRecursively()
        assertTrue(root.mkdirs())
        val content = FleetConfigurationParser.parse(fixture()).json
        File(root, ".transaction-current.json").writeText(content)
        File(root, "current.json").writeText("interrupted")
        File(root, ".transaction.json").writeText(
            JSONObject()
                .put("version", 1)
                .put("currentSha256", sha256(content))
                .put("hasPrevious", false)
                .put("previousSha256", "")
                .toString()
        )

        val recovered = FleetConfigurationStore(context).current()

        assertEquals(7L, recovered?.configurationRevision)
        assertFalse(File(root, ".transaction.json").exists())
        assertFalse(File(root, ".transaction-current.json").exists())
        assertEquals(recovered?.digest, FleetConfigurationStore(context).current()?.digest)
    }

    @Test
    fun concurrentStoreInstancesSerializeActivationWithoutCorruptingTheTransaction() {
        val context: Context = RuntimeEnvironment.getApplication()
        val root = File(context.filesDir, "fleet-configuration")
        root.deleteRecursively()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val stores = List(8) { FleetConfigurationStore(context) }
            val futures = stores.map { store ->
                executor.submit<FleetConfigurationBundle> {
                    start.await()
                    store.activate(fixture())
                }
            }
            start.countDown()
            val digests = futures.map { it.get(10, TimeUnit.SECONDS).digest }.toSet()

            assertEquals(1, digests.size)
            assertEquals(digests.single(), FleetConfigurationStore(context).current()?.digest)
            assertFalse(File(root, ".transaction.json").exists())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun oversizedPersistedConfigurationIsRejectedBeforeItIsLoaded() {
        val context: Context = RuntimeEnvironment.getApplication()
        val root = File(context.filesDir, "fleet-configuration")
        root.deleteRecursively()
        assertTrue(root.mkdirs())
        File(root, "current.json").writeBytes(ByteArray(4 * 1024 * 1024 + 1))

        assertThrows(IllegalArgumentException::class.java) {
            FleetConfigurationStore(context).current()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
