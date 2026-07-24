package com.termux.app.fleet

import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class ReleaseSetContractTest {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("contracts/$name")).readText()

    @Test
    fun matchesCanonicalFixtureLockByteForByte() {
        val lock = JSONObject(fixture("contract-lock-v1.json"))
        assertEquals(1, lock.getInt("schemaVersion"))
        assertEquals("1.6.0", lock.getString("packageVersion"))
        assertEquals("sha256", lock.getString("algorithm"))
        val files = lock.getJSONObject("files")
        val fixtures = mapOf(
            "fixtures/invalid/activation-journal-content-field-v1.json" to "activation-journal-content-field-v1.json",
            "fixtures/invalid/activation-journal-unknown-field-v1.json" to "activation-journal-unknown-field-v1.json",
            "fixtures/valid/activation-journal-v1.json" to "activation-journal-v1.json",
            "fixtures/invalid/compatibility-content-field-v1.json" to "compatibility-content-field-v1.json",
            "fixtures/invalid/compatibility-unknown-field-v1.json" to "compatibility-unknown-field-v1.json",
            "fixtures/valid/control-frames-v1.json" to "control-frames-v1.json",
            "fixtures/valid/control-results-v1.json" to "control-results-v1.json",
            "fixtures/valid/conversation-frames-v2.json" to "conversation-frames-v2.json",
            "fixtures/valid/conversation-structured-work-v2.json" to "conversation-structured-work-v2.json",
            "fixtures/valid/diagnostics-v1.json" to "diagnostics-v1.json",
            "fixtures/valid/fleet-snapshot-base-v1.json" to "fleet-snapshot-base-v1.json",
            "fixtures/valid/host-runtime-conformance-v1.json" to "host-runtime-conformance-v1.json",
            "fixtures/valid/release-set-v1.json" to "release-set-v1.json",
            "fixtures/valid/workspace-layout-v1.json" to "workspace-layout-v1.json",
            "fixtures/invalid/control-content-field-v1.json" to "control-content-field-v1.json",
            "fixtures/invalid/control-unknown-field-v1.json" to "control-unknown-field-v1.json",
            "fixtures/invalid/control-result-content-field-v1.json" to "control-result-content-field-v1.json",
            "fixtures/invalid/control-result-unknown-field-v1.json" to "control-result-unknown-field-v1.json",
            "fixtures/invalid/conversation-item-unknown-field-v2.json" to "conversation-item-unknown-field-v2.json",
            "fixtures/invalid/conversation-unknown-field-v2.json" to "conversation-unknown-field-v2.json",
            "fixtures/invalid/diagnostics-content-field-v1.json" to "diagnostics-content-field-v1.json",
            "fixtures/invalid/diagnostics-unknown-field-v1.json" to "diagnostics-unknown-field-v1.json",
            "fixtures/invalid/fleet-snapshot-content-field-v1.json" to "fleet-snapshot-content-field-v1.json",
            "fixtures/invalid/fleet-snapshot-unknown-field-v1.json" to "fleet-snapshot-unknown-field-v1.json",
            "fixtures/invalid/host-runtime-content-field-v1.json" to "host-runtime-content-field-v1.json",
            "fixtures/invalid/host-runtime-unknown-field-v1.json" to "host-runtime-unknown-field-v1.json",
            "fixtures/invalid/release-set-content-field-v1.json" to "release-set-content-field-v1.json",
            "fixtures/invalid/release-set-unknown-field-v1.json" to "release-set-unknown-field-v1.json",
            "fixtures/valid/transport-conformance-v1.json" to "transport-conformance-v1.json",
            "fixtures/invalid/transport-content-field-v1.json" to "transport-content-field-v1.json",
            "fixtures/invalid/transport-unknown-field-v1.json" to "transport-unknown-field-v1.json",
            "fixtures/invalid/workspace-layout-unknown-field-v1.json" to "workspace-layout-unknown-field-v1.json",
            "generated/structural-models-v1.json" to "structural-models-v1.json"
        )
        fixtures.forEach { (canonicalPath, localName) ->
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(fixture(localName).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals(canonicalPath, files.getString(canonicalPath), digest)
        }
    }

    @Test
    fun acceptsSharedReleaseSetAndBaseFleetSnapshot() {
        val release = ReleaseSetContract.parse(fixture("release-set-v1.json"))
        assertEquals(1083L, release.releaseSetSequence)
        assertEquals(
            listOf("windowsApp", "androidApp", "clientRuntime", "clientRuntime"),
            release.artifacts.map { it.component }
        )
        assertEquals(
            "fixture-revision",
            FleetSnapshotParser.parse(fixture("fleet-snapshot-base-v1.json")).revision
        )
    }

    @Test
    fun rejectsSharedInvalidFixtures() {
        listOf("release-set-unknown-field-v1.json", "release-set-content-field-v1.json").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                ReleaseSetContract.parse(fixture(name))
            }
        }
    }

    @Test
    fun rejectsNestedUnknownRollbackDuplicateArtifactsAndCredentialedUrls() {
        val nestedUnknown = JSONObject(fixture("release-set-v1.json")).also {
            it.getJSONObject("protocols").put("extra", 1)
        }
        assertThrows(IllegalArgumentException::class.java) { ReleaseSetContract.parse(nestedUnknown) }

        val rollback = JSONObject(fixture("release-set-v1.json")).also {
            it.getJSONObject("rollbackFloor").put("releaseSetSequence", 1084)
        }
        assertThrows(IllegalArgumentException::class.java) { ReleaseSetContract.parse(rollback) }

        val duplicate = JSONObject(fixture("release-set-v1.json")).also {
            val artifact = it.getJSONArray("artifacts").getJSONObject(0)
            it.getJSONArray("artifacts").put(JSONObject(artifact.toString()))
        }
        assertThrows(IllegalArgumentException::class.java) { ReleaseSetContract.parse(duplicate) }

        val incompatible = JSONObject(fixture("release-set-v1.json")).also {
            it.getJSONObject("components").getJSONObject("clientRuntime").put("sequence", 46)
        }
        assertThrows(IllegalArgumentException::class.java) { ReleaseSetContract.parse(incompatible) }

        val mismatchedArtifact = JSONObject(fixture("release-set-v1.json")).also {
            val artifact = it.getJSONArray("artifacts").getJSONObject(0)
            artifact.put("componentSequence", artifact.getLong("componentSequence") + 1)
        }
        assertThrows(IllegalArgumentException::class.java) { ReleaseSetContract.parse(mismatchedArtifact) }

        val credentialed = JSONObject(fixture("release-set-v1.json")).also {
            it.getJSONArray("artifacts").getJSONObject(0)
                .put("url", "https://user:secret@updates.example.invalid/app")
        }
        assertThrows(IllegalArgumentException::class.java) { ReleaseSetContract.parse(credentialed) }
    }

    @Test
    fun verifiesSignatureExpiryAntiRollbackOriginsAndInstalledAppCompatibility() {
        val signed = signedFixture()
        val keyId = MessageDigest.getInstance("SHA-256").digest(keyPair.public.encoded)
            .joinToString("") { "%02x".format(it) }.take(32)
        val trusted = mapOf(keyId to keyPair.public.encoded)
        val origins = setOf("https://updates.example.invalid", "https://github.com")
        val verified = ReleaseSetContract.verify(
            signed.toString(), trusted, Instant.parse("2026-07-24T00:00:00Z"),
            origins, "0.118.4-agentfleet.76", 1081
        )
        assertEquals(1083L, verified.releaseSetSequence)

        assertThrows(IllegalArgumentException::class.java) {
            ReleaseSetContract.verify(
                signed.toString(), trusted, Instant.parse("2026-09-01T00:00:00Z"),
                origins, "0.118.4-agentfleet.76"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseSetContract.verify(
                signed.toString(), trusted, Instant.parse("2026-07-24T00:00:00Z"),
                origins, "0.118.4-agentfleet.76", 1084
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseSetContract.verify(
                signed.toString(), trusted, Instant.parse("2026-07-24T00:00:00Z"),
                origins, "different-app-version"
            )
        }
        val tampered = JSONObject(signed.toString()).also {
            it.getJSONObject("components").getJSONObject("hostRuntime").put("version", "git-tampered")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseSetContract.verify(
                tampered.toString(), trusted, Instant.parse("2026-07-24T00:00:00Z"),
                origins, "0.118.4-agentfleet.76"
            )
        }
    }

    private fun signedFixture(): JSONObject {
        val root = JSONObject(fixture("release-set-v1.json"))
        val keyId = MessageDigest.getInstance("SHA-256").digest(keyPair.public.encoded)
            .joinToString("") { "%02x".format(it) }.take(32)
        root.getJSONObject("signature")
            .put("algorithm", "ed25519")
            .put("keyId", keyId)
            .put("value", "A".repeat(86))
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(ReleaseSetContract.canonicalSignaturePayload(root))
        root.getJSONObject("signature").put(
            "value",
            Base64.encodeToString(signer.sign(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        )
        return root
    }
}
