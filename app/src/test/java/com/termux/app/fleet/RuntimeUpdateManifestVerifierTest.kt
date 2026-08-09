package com.termux.app.fleet

import android.util.Base64
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import java.io.File

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
        val manager = RuntimeUpdateManager(context, installedRuntimeVerifier = { true })
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

    @Test
    fun promotesTheAppFloorWithoutReplacingAVerifiedHotfixWhenTheBaselineIsUnchanged() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("agent-fleet-runtime-updates", 0)
        preferences.edit().clear().commit()
        val manager = RuntimeUpdateManager(context, installedRuntimeVerifier = { true })
        val versionCode = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        preferences.edit()
            .putLong("accepted-sequence", versionCode - 1)
            .putLong("healthy-sequence", versionCode - 1)
            .putString("last-healthy-version", "git-hotfix")
            .putString("last-healthy-channel", "legacy")
            .commit()
        val status = EmbeddedRuntimeStatus(
            supported = true, usable = true, repairNeeded = false,
            embeddedBaseline = "git-baseline", baseline = "git-baseline", current = "git-hotfix", previous = "git-baseline",
            missingOrOldPackages = 0, packageCount = 70, trustedKeyIds = emptyList(), detail = "ready"
        )

        assertTrue(manager.shouldPreserveCurrentRuntime(status))
        assertEquals(status, manager.reconcileRuntimeFloor(status))
        assertEquals(versionCode, manager.acceptedSequence())
        assertEquals(versionCode, manager.healthySequence())
        assertFalse(manager.shouldPreserveCurrentRuntime(status.copy(baseline = "git-older-baseline")))
    }

    @Test
    fun refusesToAdvanceTheHealthyFloorForUnverifiedInstalledBytes() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("agent-fleet-runtime-updates", 0)
        preferences.edit().clear().commit()
        val manager = RuntimeUpdateManager(context, installedRuntimeVerifier = { false })
        val status = EmbeddedRuntimeStatus(
            supported = true, usable = true, repairNeeded = false,
            embeddedBaseline = "git-baseline", baseline = "git-baseline", current = "git-baseline", previous = "",
            missingOrOldPackages = 0, packageCount = 70, trustedKeyIds = emptyList(), detail = "ready"
        )

        assertThrows(IllegalArgumentException::class.java) {
            manager.reconcileRuntimeFloor(status)
        }
        assertEquals(0L, manager.acceptedSequence())
        assertEquals(0L, manager.healthySequence())
    }

    @Test
    fun releaseSetSequencesRemainIndependentFromLegacyAppRuntimeCounters() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("agent-fleet-runtime-updates", 0)
        preferences.edit().clear()
            .putLong("accepted-release-set-sequence", 7)
            .putLong("healthy-release-set-sequence", 7)
            .putLong("component-floor-clientRuntime", 44)
            .putString("last-healthy-version", "git-release-set-hotfix")
            .putString("last-healthy-channel", "release-set")
            .commit()
        val manager = RuntimeUpdateManager(context)
        val status = EmbeddedRuntimeStatus(
            supported = true, usable = true, repairNeeded = false,
            embeddedBaseline = "git-baseline", baseline = "git-baseline",
            current = "git-release-set-hotfix", previous = "git-baseline",
            missingOrOldPackages = 0, packageCount = 70, trustedKeyIds = emptyList(), detail = "ready"
        )
        assertEquals(0L, manager.acceptedSequence())
        assertEquals(7L, manager.acceptedReleaseSetSequence())
        assertTrue(manager.shouldPreserveCurrentRuntime(status))

        preferences.edit()
            .putLong("accepted-sequence", 1096)
            .putLong("healthy-sequence", 1096)
            .putLong("accepted-release-set-sequence", 8)
            .putLong("healthy-release-set-sequence", 7)
            .commit()
        assertFalse(manager.shouldPreserveCurrentRuntime(status))
    }

    @Test
    fun expectedHostRuntimeVersionIsAlwaysBoundToVerifiedMetadata() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("agent-fleet-runtime-updates", 0)
        preferences.edit().clear().commit()
        val manager = RuntimeUpdateManager(context)

        assertEquals(
            EmbeddedRuntimeManager(context).descriptor().components.getValue("hostRuntime").version,
            manager.verifiedExpectedHostRuntimeVersion()?.value
        )

        preferences.edit()
            .putLong("healthy-release-set-sequence", 8)
            .putString("healthy-host-runtime-version", "git-signed-host")
            .commit()
        assertEquals("git-signed-host", manager.verifiedExpectedHostRuntimeVersion()?.value)

        preferences.edit().remove("healthy-host-runtime-version").commit()
        assertEquals(null, manager.verifiedExpectedHostRuntimeVersion())
        preferences.edit().putString("healthy-host-runtime-version", "../../untrusted").commit()
        assertEquals(null, manager.verifiedExpectedHostRuntimeVersion())
    }

    @Test
    fun anAcceptedButUnhealthySequenceRemainsRetryable() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("agent-fleet-runtime-updates", 0)
        preferences.edit().clear()
            .putLong("accepted-sequence", 52)
            .putLong("healthy-sequence", 51)
            .putLong("accepted-release-set-sequence", 12)
            .putLong("healthy-release-set-sequence", 11)
            .commit()
        val manager = RuntimeUpdateManager(context)

        assertTrue(manager.runtimeSequenceNeedsActivation(sequence = 52, builtInFloor = 50))
        assertTrue(manager.releaseSetSequenceNeedsActivation(sequence = 12))
        assertFalse(manager.runtimeSequenceNeedsActivation(sequence = 51, builtInFloor = 50))
        assertFalse(manager.releaseSetSequenceNeedsActivation(sequence = 11))
    }

    @Test
    fun selectsTheActualAndroidAbiAndRejectsEqualRankAmbiguity() {
        val arm64 = artifact("arm64", "termux", "arm64")
        val x86 = artifact("x86", "termux", "x86_64")
        val universal = artifact("universal", "termux", "universal")
        val any = artifact("any", "any", "any")

        assertEquals(x86, selectAndroidClientRuntimeArtifact(listOf(arm64, universal, x86, any), listOf("x86_64")))
        assertEquals(
            arm64,
            selectAndroidClientRuntimeArtifact(listOf(x86, arm64, universal), listOf("arm64-v8a", "x86_64"))
        )
        assertEquals(universal, selectAndroidClientRuntimeArtifact(listOf(universal, any), listOf("armeabi-v7a")))
        assertThrows(IllegalArgumentException::class.java) {
            selectAndroidClientRuntimeArtifact(
                listOf(arm64, artifact("arm64-copy", "termux", "arm64")),
                listOf("arm64-v8a")
            )
        }
    }

    @Test
    fun acquisitionAndActivationMustBothSucceedBeforeTheCallerCanAdvanceItsFloor() {
        val update = RuntimeUpdate(
            sequence = 52,
            version = "git-healthy",
            protocolVersion = 2,
            artifactUrl = "https://controller.tailnet.ts.net/runtime/wtmux.tar",
            sha256 = "ab".repeat(32),
            size = 10,
            minAppVersionCode = 1,
            createdAt = "2026-07-14T08:00:00Z",
            keyId = keyId
        )
        val artifact = File.createTempFile("runtime-update", ".tar")
        val healthy = status(current = update.version, usable = true)
        val calls = mutableListOf<String>()
        assertEquals(
            healthy,
            acquireAndActivateRuntime(
                update,
                acquire = { calls += "acquire"; artifact },
                activate = { file, digest ->
                    assertEquals(artifact, file)
                    assertEquals(update.sha256, digest)
                    calls += "activate"
                    healthy
                }
            )
        )
        assertEquals(listOf("acquire", "activate"), calls)

        var activated = false
        assertThrows(IllegalStateException::class.java) {
            acquireAndActivateRuntime(
                update,
                acquire = { throw IllegalStateException("transient download failure") },
                activate = { _, _ -> activated = true; healthy }
            )
        }
        assertFalse(activated)
        assertThrows(IllegalArgumentException::class.java) {
            acquireAndActivateRuntime(update, acquire = { artifact }, activate = { _, _ ->
                status(current = "git-wrong", usable = true)
            })
        }
        artifact.delete()
    }

    private fun artifact(id: String, platform: String, architecture: String) = ReleaseArtifact(
        id = id,
        component = "clientRuntime",
        componentSequence = 52,
        version = "git-healthy",
        platform = platform,
        architecture = architecture,
        url = "https://controller.tailnet.ts.net/runtime/$id.tar",
        sha256 = "ab".repeat(32),
        size = 10,
        sourceRepository = "https://controller.tailnet.ts.net/source",
        sourceCommit = "cd".repeat(20),
        contractPackageVersion = "contract-v1",
        sbomSha256 = "ef".repeat(32),
        licenseSha256 = "12".repeat(32)
    )

    private fun status(current: String, usable: Boolean) = EmbeddedRuntimeStatus(
        supported = true,
        usable = usable,
        repairNeeded = !usable,
        embeddedBaseline = "git-baseline",
        baseline = "git-baseline",
        current = current,
        previous = "",
        missingOrOldPackages = 0,
        packageCount = 70,
        trustedKeyIds = emptyList(),
        detail = if (usable) "ready" else "broken"
    )
}

private fun ByteArray.base64Url(): String = Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
