package com.termux.app.fleet

import android.util.Base64
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RuntimeUpdateManifestVerifierTest {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val keyId = MessageDigest.getInstance("SHA-256").digest(pair.public.encoded).toHex().take(32)

    private fun envelope(sequence: Long = 42, origin: String = "controller.tailnet.ts.net", minimum: Long = 1020): String {
        val payload = buildString {
            append('{')
            append("\"artifactUrl\":\"https://$origin/runtime/wtmux.tar\",")
            append("\"createdAt\":\"2026-07-14T08:00:00Z\",")
            append("\"minAppVersionCode\":$minimum,")
            append("\"protocolVersion\":2,")
            append("\"schemaVersion\":1,")
            append("\"sequence\":$sequence,")
            append("\"sha256\":\"${"ab".repeat(32)}\",")
            append("\"size\":12345,")
            append("\"version\":\"git-deadbee\"")
            append('}')
        }
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(pair.private)
        signer.update(payload.toByteArray())
        return JSONObject()
            .put("schemaVersion", 1)
            .put("keyId", keyId)
            .put("payload", payload.toByteArray().base64Url())
            .put("signature", signer.sign().base64Url())
            .toString()
    }

    @Test
    fun verifiesPinnedCanonicalEd25519Update() {
        val update = RuntimeUpdateManifestVerifier.verify(
            envelope(), mapOf(keyId to pair.public.encoded), 2, 1020,
            setOf("https://controller.tailnet.ts.net")
        )
        assertEquals(42, update.sequence)
        assertEquals("git-deadbee", update.version)
        assertEquals(keyId, update.keyId)
    }

    @Test
    fun rejectsUnknownKeyOriginIncompatibleAppAndTamper() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeUpdateManifestVerifier.verify(envelope(), emptyMap(), 2, 1020, setOf("https://controller.tailnet.ts.net"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeUpdateManifestVerifier.verify(envelope(origin = "other.tailnet.ts.net"), mapOf(keyId to pair.public.encoded), 2, 1020,
                setOf("https://controller.tailnet.ts.net"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeUpdateManifestVerifier.verify(envelope(minimum = 1021), mapOf(keyId to pair.public.encoded), 2, 1020,
                setOf("https://controller.tailnet.ts.net"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            val changed = JSONObject(envelope()).let { value ->
                val signature = value.getString("signature")
                value.put("signature", signature.dropLast(1) + if (signature.last() == 'A') "B" else "A").toString()
            }
            RuntimeUpdateManifestVerifier.verify(changed, mapOf(keyId to pair.public.encoded), 2, 1020,
                setOf("https://controller.tailnet.ts.net"))
        }
    }

    @Test
    fun recordsTheInstalledAppAsTheMinimumHealthyRuntimeSequence() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("agent-fleet-runtime-updates", 0).edit().clear().commit()
        val manager = RuntimeUpdateManager(context)
        val versionCode = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val status = EmbeddedRuntimeStatus(
            supported = true, usable = true, repairNeeded = false,
            embeddedBaseline = "git-baseline", baseline = "git-baseline", current = "git-baseline", previous = "",
            missingOrOldPackages = 0, packageCount = 70, trustedKeyIds = emptyList(), detail = "ready"
        )
        assertEquals(status, manager.reconcileRuntimeFloor(status))
        assertEquals(versionCode, manager.acceptedSequence())
        assertEquals(versionCode, manager.healthySequence())
    }
}

private fun ByteArray.base64Url(): String = Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
