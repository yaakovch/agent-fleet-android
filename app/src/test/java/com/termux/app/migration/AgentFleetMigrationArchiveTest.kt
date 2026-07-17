package com.termux.app.migration

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentFleetMigrationArchiveTest {
    private lateinit var destination: Context
    private lateinit var sourceRoot: File
    private lateinit var source: Context

    @Before
    fun setUp() {
        destination = RuntimeEnvironment.getApplication()
        assertEquals(AgentFleetMigrationArchive.FLEET_PACKAGE, destination.packageName)
        sourceRoot = Files.createTempDirectory("agent-fleet-legacy").toFile()
        source = LegacyContext(destination, sourceRoot)
        clearDestination()
    }

    @After
    fun tearDown() {
        clearDestination()
        sourceRoot.deleteRecursively()
    }

    @Test
    fun roundTripsOnlyFleetStateAndRewritesThePrivateAppRoot() {
        source.getSharedPreferences("agent_fleet_workspace_v1", Context.MODE_PRIVATE).edit()
            .putString("layout", "{\"path\":\"/data/user/0/com.termux/files/home\"}")
            .putStringSet("hidden-unavailable", setOf("host:one"))
            .commit()
        source.getSharedPreferences("agent-fleet-native-session", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true).commit()

        val home = File(source.filesDir, "home")
        File(home, ".config/wtmux").mkdirs()
        File(home, ".config/wtmux/wtmux.conf").writeText(
            "WTMUX_SHARED_REGISTRY_DIR='/data/user/0/com.termux/files/home/.local/share/wtmux/registry/current/machines'\n"
        )
        File(home, ".config/wtmux/client-policy.json").writeText("""
            {
              "schemaVersion":1,
              "policyRevision":1,
              "apkManifestUrls":["https://fleet.example/agent-fleet/latest/manifest.json"],
              "runtimeManifestUrls":["https://fleet.example/runtime/manifest.json"],
              "artifactOrigins":["https://fleet.example"],
              "checkIntervalSeconds":21600
            }
        """.trimIndent() + "\n")
        File(home, ".ssh").mkdirs()
        File(home, ".ssh/id_ed25519").writeText("test-private-key")
        File(home, ".ssh/config").writeText(
            "IdentityFile /data/user/0/com.termux/files/home/.ssh/id_ed25519\n"
        )
        File(home, "notes.txt").writeText("must not migrate")
        File(source.filesDir, "usr/bin").mkdirs()
        File(source.filesDir, "usr/bin/bash").writeText("must not migrate")
        createRegistry(home, "0123456789abcdef")

        val archive = ByteArrayOutputStream().also { AgentFleetMigrationArchive.export(source, it) }.toByteArray()
        val result = AgentFleetMigrationArchive.import(destination, ByteArrayInputStream(archive))

        assertEquals(AgentFleetMigrationArchive.LEGACY_PACKAGE, result.sourcePackage)
        assertEquals(2, result.preferenceStores)
        assertEquals("0123456789abcdef", result.registryRelease)
        assertTrue(destination.getSharedPreferences("agent-fleet-native-session", Context.MODE_PRIVATE).getBoolean("enabled", false))
        assertEquals(
            "{\"path\":\"/data/user/0/com.yaakovch.fleet/files/home\"}",
            destination.getSharedPreferences("agent_fleet_workspace_v1", Context.MODE_PRIVATE).getString("layout", "")
        )
        val destinationHome = File(destination.filesDir, "home")
        assertTrue(File(destinationHome, ".ssh/id_ed25519").isFile)
        assertTrue(File(destinationHome, ".ssh/config").readText()
            .contains("/data/user/0/com.yaakovch.fleet/files/home/.ssh/id_ed25519"))
        assertFalse(File(destinationHome, "notes.txt").exists())
        assertFalse(File(destination.filesDir, "usr/bin/bash").exists())
        assertTrue(File(destinationHome, ".config/wtmux/wtmux.conf").readText()
            .contains("/data/user/0/com.yaakovch.fleet/files/home/.local/share/wtmux/registry/current/machines"))
        assertEquals(
            "https://fleet.example/agent-fleet/fleet/latest/manifest.json",
            JSONObject(File(destinationHome, ".config/wtmux/client-policy.json").readText())
                .getJSONArray("apkManifestUrls")
                .getString(0)
        )
        assertEquals(
            File(destinationHome, ".local/share/wtmux/registry/releases/0123456789abcdef").canonicalFile,
            File(destinationHome, ".local/share/wtmux/registry/current").canonicalFile
        )
    }

    @Test
    fun rejectsTamperingAndSamePackageImports() {
        source.getSharedPreferences("agent_fleet_terminal_tabs", Context.MODE_PRIVATE).edit().putString("tabs", "[]").commit()
        val archive = ByteArrayOutputStream().also { AgentFleetMigrationArchive.export(source, it) }.toByteArray()
        val changed = archive.clone().also { bytes -> bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) {
            AgentFleetMigrationArchive.import(destination, ByteArrayInputStream(changed))
        }
        val newSource = object : ContextWrapper(destination) {
            override fun getPackageName() = AgentFleetMigrationArchive.FLEET_PACKAGE
        }
        val sameArchive = ByteArrayOutputStream().also { AgentFleetMigrationArchive.export(newSource, it) }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            AgentFleetMigrationArchive.import(destination, ByteArrayInputStream(sameArchive))
        }
    }

    @Test
    fun repairsCanonicalRootsLeftByThePreviousPermanentBuild() {
        destination.getSharedPreferences("agent_fleet_workspace_v1", Context.MODE_PRIVATE).edit()
            .putString("layout", "{\"path\":\"/data/user/0/com.termux/files/home\"}")
            .putStringSet("paths", setOf("/data/data/com.termux/files/home/.ssh/id_ed25519"))
            .commit()
        val home = File(destination.filesDir, "home")
        File(home, ".config/wtmux").mkdirs()
        File(home, ".config/wtmux/wtmux.conf").writeText(
            "WTMUX_SHARED_REGISTRY_DIR='/data/user/0/com.termux/files/home/.local/share/wtmux/registry/current/machines'\n"
        )
        File(home, ".ssh").mkdirs()
        File(home, ".ssh/config").writeText(
            "IdentityFile /data/data/com.termux/files/home/.ssh/id_ed25519\n"
        )

        assertEquals(3, AgentFleetMigrationArchive.repairMigratedPrivateRoots(destination))
        assertTrue(destination.getSharedPreferences("agent_fleet_workspace_v1", Context.MODE_PRIVATE)
            .getString("layout", "").orEmpty().contains("/data/user/0/com.yaakovch.fleet/files/home"))
        assertEquals(
            setOf("/data/data/com.yaakovch.fleet/files/home/.ssh/id_ed25519"),
            destination.getSharedPreferences("agent_fleet_workspace_v1", Context.MODE_PRIVATE)
                .getStringSet("paths", emptySet())
        )
        assertTrue(File(home, ".config/wtmux/wtmux.conf").readText().contains("/data/user/0/com.yaakovch.fleet/"))
        assertTrue(File(home, ".ssh/config").readText().contains("/data/data/com.yaakovch.fleet/"))
        assertEquals(0, AgentFleetMigrationArchive.repairMigratedPrivateRoots(destination))
    }

    private fun createRegistry(home: File, releaseId: String) {
        val release = File(home, ".local/share/wtmux/registry/releases/$releaseId")
        val machine = File(release, "machines/phone.json")
        machine.parentFile!!.mkdirs()
        val payload = "{\"schemaVersion\":1,\"id\":\"phone\"}\n".toByteArray()
        machine.writeBytes(payload)
        val manifest = JSONObject()
            .put("formatVersion", 1)
            .put("schemaVersion", 1)
            .put("records", JSONArray().put(JSONObject()
                .put("id", "phone")
                .put("path", "machines/phone.json")
                .put("sha256", sha256(payload))
                .put("size", payload.size)))
        File(release, "registry-manifest.json").writeText(manifest.toString(2) + "\n")
        Files.createSymbolicLink(File(release.parentFile!!.parentFile, "current").toPath(), File("releases/$releaseId").toPath())
    }

    private fun clearDestination() {
        listOf(
            "agent_fleet_terminal_drawer", "agent_fleet_terminal_tabs", "agent_fleet_workspace_v1",
            "agent_fleet_workspace_presentation", "agent_fleet_locations", "agent-fleet-native-session",
            "agent-fleet-terminal-appearance"
        ).forEach { destination.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        File(destination.filesDir, "home/.config/wtmux").deleteRecursively()
        File(destination.filesDir, "home/.ssh").deleteRecursively()
        File(destination.filesDir, "home/.local/share/wtmux/registry").deleteRecursively()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private class LegacyContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getPackageName() = AgentFleetMigrationArchive.LEGACY_PACKAGE
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences("legacy-test-$name", mode)
    }
}
