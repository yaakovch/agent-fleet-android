package com.termux.app

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.app.fleet.EmbeddedRuntimeDescriptor
import com.termux.app.fleet.EmbeddedRuntimeManager
import com.termux.app.fleet.embeddedRegistryBindingIsCurrent
import com.termux.app.fleet.enableTermuxExec
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentFleetEmbeddedRegistryTest {
    internal fun prepareRuntime(context: Context): File {
        ensureBootstrap(context)
        val descriptor = EmbeddedRuntimeManager(context).descriptor()
        val runtimeRoot = File(context.filesDir, "home/.local/share/agent-fleet/wtmux")
        installRuntime(context, descriptor, runtimeRoot)
        return runtimeRoot
    }

    @Test
    fun currentRuntimeRepairsMissingAndMigratedRegistryState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ensureBootstrap(context)
        val manager = EmbeddedRuntimeManager(context)
        val descriptor = manager.descriptor()
        val appRoot = requireNotNull(context.filesDir.parentFile)
        val home = File(appRoot, "files/home")
        val runtimeRoot = File(home, ".local/share/agent-fleet/wtmux")
        val registryRoot = File(runtimeRoot, "registry")
        val expectedRelease = descriptor.registry.sha256.take(16)
        val config = File(home, ".config/wtmux/wtmux.conf")
        val registryMachines = File(registryRoot, "current/machines").absolutePath

        installRuntime(context, descriptor, runtimeRoot)
        installRegistry(context, descriptor, runtimeRoot, config, preserveCurrent = false)

        assertEquals(
            File(registryRoot, "releases/$expectedRelease").canonicalFile,
            File(registryRoot, "current").canonicalFile
        )
        val manifest = JSONObject(
            File(registryRoot, "current/registry-manifest.json").readText(Charsets.UTF_8)
        )
        assertEquals(3, manifest.getJSONArray("records").length())
        assertTrue(embeddedRegistryBindingIsCurrent(config.readText(Charsets.UTF_8), registryMachines))

        val staleRegistry = File(home, ".local/share/wtmux/registry/current/machines").absolutePath
        config.writeText(config.readText(Charsets.UTF_8).replace(registryMachines, staleRegistry), Charsets.UTF_8)
        assertFalse(embeddedRegistryBindingIsCurrent(config.readText(Charsets.UTF_8), registryMachines))

        installRegistry(context, descriptor, runtimeRoot, config)

        assertTrue(embeddedRegistryBindingIsCurrent(config.readText(Charsets.UTF_8), registryMachines))

        val newer = File(registryRoot, "releases/${java.util.UUID.randomUUID().toString().replace("-", "").take(16)}")
        File(registryRoot, "current").canonicalFile.copyRecursively(newer, overwrite = true)
        val newerManifest = JSONObject(File(newer, "registry-manifest.json").readText())
        val entry = newerManifest.getJSONArray("records").getJSONObject(0)
        val recordFile = File(newer, entry.getString("path"))
        val record = JSONObject(recordFile.readText()).put("name", "Updated fleet host")
        val bytes = record.toString(2).toByteArray(Charsets.UTF_8)
        recordFile.writeBytes(bytes)
        entry.put("size", bytes.size).put("sha256", java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        File(newer, "registry-manifest.json").writeText(newerManifest.toString(2))
        assertTrue(File(registryRoot, "current").delete())
        android.system.Os.symlink("releases/${newer.name}", File(registryRoot, "current").absolutePath)
        installRegistry(context, descriptor, runtimeRoot, config)
        assertEquals(newer.canonicalFile, File(registryRoot, "current").canonicalFile)
        assertEquals("Updated fleet host", JSONObject(recordFile.readText()).getString("name"))
    }

    private fun installRuntime(context: Context, descriptor: EmbeddedRuntimeDescriptor, runtimeRoot: File) {
        val appRoot = requireNotNull(context.filesDir.parentFile)
        val prefix = File(appRoot, "files/usr")
        val bundle = File(context.cacheDir, descriptor.runtime.file)
        context.assets.open("agent-fleet/${descriptor.runtime.file}").use { input ->
            bundle.outputStream().use(input::copyTo)
        }
        val extraction = File(context.cacheDir, "embedded-registry-runtime-installer").apply {
            deleteRecursively()
            mkdirs()
        }
        runTermux(
            context,
            listOf(
                File(prefix, "bin/tar").absolutePath, "-xf", bundle.absolutePath,
                "-C", extraction.absolutePath, "scripts/wtmux-runtime"
            )
        )
        runTermux(
            context,
            listOf(
                File(prefix, "bin/python3").absolutePath,
                File(extraction, "scripts/wtmux-runtime").absolutePath,
                "install", "--bundle", bundle.absolutePath, "--sha256", descriptor.runtime.sha256,
                "--root", runtimeRoot.absolutePath, "--bin-dir", File(prefix, "bin").absolutePath, "--baseline"
            )
        )
    }

    private fun installRegistry(
        context: Context,
        descriptor: EmbeddedRuntimeDescriptor,
        runtimeRoot: File,
        config: File,
        preserveCurrent: Boolean = true
    ) {
        val appRoot = requireNotNull(context.filesDir.parentFile)
        val prefix = File(appRoot, "files/usr")
        val registry = File(context.cacheDir, descriptor.registry.file)
        context.assets.open("agent-fleet/${descriptor.registry.file}").use { input ->
            registry.outputStream().use(input::copyTo)
        }
        runTermux(
            context,
            listOf(
                File(prefix, "bin/python3").absolutePath,
                File(runtimeRoot, "current/scripts/wtmux-runtime").absolutePath,
                "install-registry", "--bundle", registry.absolutePath, "--sha256", descriptor.registry.sha256,
                "--root", runtimeRoot.absolutePath, "--config", config.absolutePath
            ) + if (preserveCurrent) listOf("--preserve-current") else emptyList()
        )
    }

    private fun runTermux(context: Context, command: List<String>) {
        val appRoot = requireNotNull(context.filesDir.parentFile)
        val home = File(appRoot, "files/home").apply { mkdirs() }
        val prefix = File(appRoot, "files/usr")
        val process = ProcessBuilder(command).directory(home).redirectErrorStream(true).apply {
            environment()["HOME"] = home.absolutePath
            environment()["PREFIX"] = prefix.absolutePath
            environment()["PATH"] = "${File(home, ".local/bin")}:${File(prefix, "bin")}"
            environment()["WTMUX_RUNTIME_BIN_DIR"] = File(prefix, "bin").absolutePath
            enableTermuxExec(environment(), prefix)
        }.start()
        val output = process.inputStream.bufferedReader().readText().take(16 * 1024)
        assertTrue("${command.first()} timed out: $output", process.waitFor(60, TimeUnit.SECONDS))
        assertEquals("${command.first()} failed: $output", 0, process.exitValue())
    }

    private fun ensureBootstrap(context: Context) {
        val prefix = File(requireNotNull(context.filesDir.parentFile), "files/usr")
        if (File(prefix, "bin/bash").canExecute()) return
        val completed = CountDownLatch(1)
        ActivityScenario.launch(DrawerComposeTestHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                TermuxInstaller.setupBootstrapIfNeeded(activity) { completed.countDown() }
            }
            assertTrue(
                "Timed out waiting for the packaged Termux bootstrap",
                completed.await(3, TimeUnit.MINUTES)
            )
        }
        assertTrue("The packaged Termux bootstrap did not install bash", File(prefix, "bin/bash").canExecute())
    }
}
