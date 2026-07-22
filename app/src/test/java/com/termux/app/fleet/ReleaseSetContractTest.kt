package com.termux.app.fleet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class ReleaseSetContractTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("contracts/$name")).readText()

    @Test
    fun matchesCanonicalFixtureLockByteForByte() {
        val lock = JSONObject(fixture("contract-lock-v1.json"))
        assertEquals(1, lock.getInt("schemaVersion"))
        assertEquals("1.0.0", lock.getString("packageVersion"))
        assertEquals("sha256", lock.getString("algorithm"))
        val files = lock.getJSONObject("files")
        val fixtures = mapOf(
            "fixtures/valid/control-frames-v1.json" to "control-frames-v1.json",
            "fixtures/valid/conversation-structured-work-v2.json" to "conversation-structured-work-v2.json",
            "fixtures/valid/diagnostics-v1.json" to "diagnostics-v1.json",
            "fixtures/valid/fleet-snapshot-base-v1.json" to "fleet-snapshot-base-v1.json",
            "fixtures/valid/release-set-v1.json" to "release-set-v1.json",
            "fixtures/valid/workspace-layout-v1.json" to "workspace-layout-v1.json",
            "fixtures/invalid/control-content-field-v1.json" to "control-content-field-v1.json",
            "fixtures/invalid/control-unknown-field-v1.json" to "control-unknown-field-v1.json",
            "fixtures/invalid/conversation-item-unknown-field-v2.json" to "conversation-item-unknown-field-v2.json",
            "fixtures/invalid/conversation-unknown-field-v2.json" to "conversation-unknown-field-v2.json",
            "fixtures/invalid/diagnostics-content-field-v1.json" to "diagnostics-content-field-v1.json",
            "fixtures/invalid/diagnostics-unknown-field-v1.json" to "diagnostics-unknown-field-v1.json",
            "fixtures/invalid/fleet-snapshot-content-field-v1.json" to "fleet-snapshot-content-field-v1.json",
            "fixtures/invalid/fleet-snapshot-unknown-field-v1.json" to "fleet-snapshot-unknown-field-v1.json",
            "fixtures/invalid/release-set-content-field-v1.json" to "release-set-content-field-v1.json",
            "fixtures/invalid/release-set-unknown-field-v1.json" to "release-set-unknown-field-v1.json",
            "fixtures/invalid/workspace-layout-unknown-field-v1.json" to "workspace-layout-unknown-field-v1.json"
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
        assertEquals(1082L, release.releaseSetSequence)
        assertEquals(listOf("windowsApp", "androidApp"), release.artifacts.map { it.component })
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
            it.getJSONObject("rollbackFloor").put("releaseSetSequence", 1083)
        }
        assertThrows(IllegalArgumentException::class.java) { ReleaseSetContract.parse(rollback) }

        val duplicate = JSONObject(fixture("release-set-v1.json")).also {
            val artifact = it.getJSONArray("artifacts").getJSONObject(0)
            it.getJSONArray("artifacts").put(JSONObject(artifact.toString()))
        }
        assertThrows(IllegalArgumentException::class.java) { ReleaseSetContract.parse(duplicate) }

        val credentialed = JSONObject(fixture("release-set-v1.json")).also {
            it.getJSONArray("artifacts").getJSONObject(0)
                .put("url", "https://user:secret@updates.example.invalid/app")
        }
        assertThrows(IllegalArgumentException::class.java) { ReleaseSetContract.parse(credentialed) }
    }
}
