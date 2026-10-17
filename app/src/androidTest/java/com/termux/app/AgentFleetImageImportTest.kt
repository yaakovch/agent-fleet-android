package com.termux.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.termux.app.fleet.importAgentFleetAttachment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.app.fleet.AgentFleetComposer
import com.termux.app.fleet.EmbeddedRuntimeManager
import com.termux.app.fleet.enableTermuxExec
import com.termux.app.fleet.parsePaneScrollbackSnapshot
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentFleetImageImportTest {
    @Test
    fun fileUriWithoutMimeImportsIntoPermanentAppPrivateRoot() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "picker-image-without-extension")
        FileOutputStream(source).use { stream ->
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(android.graphics.Color.BLUE)
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                bitmap.recycle()
            }
        }

        val imported = AgentFleetComposer.copyImage(context, Uri.fromFile(source))

        assertTrue(imported.name.endsWith(".png"))
        assertTrue(imported.absolutePath.startsWith("/data/user/0/com.yaakovch.fleet/"))
        assertArrayEquals(source.readBytes(), imported.readBytes())
        imported.delete()
        source.delete()
    }

    @Test
    fun documentProviderImportsMarkdownWithoutImageConversion() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "agent-fleet-camera/Selected plan.md").apply {
            parentFile?.mkdirs()
            writeBytes("# Plan\r\nשלום 🙂\n".toByteArray())
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.agentfleet.images", source)
        val imported = importAgentFleetAttachment(context, uri)
        try {
            assertEquals("Selected_plan.md", imported.documentName)
            assertArrayEquals(source.readBytes(), imported.file.readBytes())
            assertTrue(imported.file.absolutePath.contains("/.cache/agent-fleet/attachments/"))
        } finally {
            imported.file.delete()
            source.delete()
        }
    }

    @Test
    fun packagedRuntimeUsesOpenSshForImagesAndPaneScrollback() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ensureBootstrap(context)
        installPackagedWtmuxRuntime(context)
        val appRoot = requireNotNull(context.filesDir.parentFile)
        val home = File(appRoot, "files/home")
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val config = File(home, ".config/wtmux/wtmux.conf").apply { parentFile?.mkdirs() }
        testAssets.open("image-upload/wtmux.conf").use { input -> config.outputStream().use(input::copyTo) }
        val knownHosts = File(home, ".ssh/known_hosts").apply { parentFile?.mkdirs() }
        testAssets.open("image-upload/known_hosts").use { input -> knownHosts.outputStream().use(input::copyTo) }
        val fakeSsh = File(home, ".local/bin/ssh").apply { parentFile?.mkdirs() }
        testAssets.open("image-upload/ssh").use { input -> fakeSsh.outputStream().use(input::copyTo) }
        assertTrue(fakeSsh.setExecutable(true))
        val source = File(home, ".cache/agent-fleet/images/emulator.png").apply { parentFile?.mkdirs() }
        File(home, ".cache/agent-fleet/image-upload-retry-marker").delete()
        FileOutputStream(source).use { stream ->
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(android.graphics.Color.GREEN)
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                bitmap.recycle()
            }
        }

        val path = AgentFleetComposer.sendImage(context, source, "fake-host", "demo", "demo-session")

        assertEquals(".wtmux/images/emulator-upload.png", path)

        val document = File(context.cacheDir, "emulator-plan.md").apply {
            writeBytes("# Keep these bytes\r\nשלום 🙂\n".toByteArray())
        }
        try {
            val documentPath = AgentFleetComposer.sendImage(
                context, document, "fake-host", "demo", "demo-session", "Selected plan.md"
            )
            assertEquals(".wtmux/attachments/emulator-upload.md", documentPath)
            val received = File(home, ".cache/agent-fleet/received-document.md")
            assertArrayEquals(document.readBytes(), received.readBytes())
            File(context.externalMediaDirs.first(), "attachments/packaged-document.json").apply {
                parentFile?.mkdirs()
                writeText(org.json.JSONObject().put("syntheticSsh", true)
                    .put("sourceSha256", document.sha256()).put("receivedSha256", received.sha256())
                    .put("remotePath", documentPath).put("runtimeVersion", EmbeddedRuntimeManager(context).descriptor().baselineVersion)
                    .put("byteCount", document.length()).toString(2))
            }
        } finally {
            document.delete()
        }

        val prefix = File(appRoot, "files/usr")
        val raw = runTermuxCapture(
            context,
            listOf(
                File(prefix, "bin/bash").absolutePath,
                File(prefix, "bin/wtmux").absolutePath,
                "pane", "scrollback", "--host", "fake-host", "--session", "demo-session", "--limit", "2000"
            )
        )
        val snapshot = parsePaneScrollbackSnapshot(raw.lineSequence().last { it.isNotBlank() }, "demo-session")
        assertEquals("older row\ncurrent row", snapshot.ansi.decodeToString())

        val attachOutput = runTermuxCapture(
            context,
            listOf(
                File(prefix, "bin/bash").absolutePath,
                File(prefix, "bin/wtmux").absolutePath,
                "--noninteractive", "--host", "fake-host", "--project", "demo", "--session", "demo-session"
            )
        )
        assertTrue("Client-only attach did not reach the remote session: $attachOutput", attachOutput.contains("attached:demo-session"))
        assertTrue(
            "Client-only attach leaked the local registration warning: $attachOutput",
            !attachOutput.contains("current machine is not registered")
        )
    }

    private fun ensureBootstrap(context: android.content.Context) {
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

    private fun installPackagedWtmuxRuntime(context: android.content.Context) {
        val appRoot = requireNotNull(context.filesDir.parentFile)
        val prefix = File(appRoot, "files/usr")
        val home = File(appRoot, "files/home").apply { mkdirs() }
        val manager = EmbeddedRuntimeManager(context)
        val descriptor = manager.descriptor()
        val wtmux = File(prefix, "bin/wtmux")
        val runtimeRoot = File(home, ".local/share/agent-fleet/wtmux")
        val bundle = File(context.cacheDir, descriptor.runtime.file)
        context.assets.open("agent-fleet/${descriptor.runtime.file}").use { input ->
            bundle.outputStream().use(input::copyTo)
        }
        assertEquals(descriptor.runtime.sha256, bundle.sha256())
        val extraction = File(context.cacheDir, "image-runtime-installer").apply {
            deleteRecursively()
            mkdirs()
        }
        runTermux(
            context,
            listOf(File(prefix, "bin/tar").absolutePath, "-xf", bundle.absolutePath, "-C", extraction.absolutePath,
                "scripts/wtmux-runtime")
        )
        runTermux(
            context,
            listOf(
                File(prefix, "bin/python3").absolutePath, File(extraction, "scripts/wtmux-runtime").absolutePath,
                "install", "--bundle", bundle.absolutePath, "--sha256", descriptor.runtime.sha256,
                "--root", runtimeRoot.absolutePath,
                "--bin-dir", File(prefix, "bin").absolutePath, "--baseline"
            )
        )
        // Match production provisioning: activate the mutable registry before
        // setup. Otherwise setup creates fleet/machines inside the immutable
        // runtime, breaking subsequent installer integrity checks.
        val registry = File(context.cacheDir, descriptor.registry.file)
        context.assets.open("agent-fleet/${descriptor.registry.file}").use { input ->
            registry.outputStream().use(input::copyTo)
        }
        runTermux(context, listOf(
            File(prefix, "bin/python3").absolutePath,
            File(runtimeRoot, "current/scripts/wtmux-runtime").absolutePath,
            "install-registry", "--bundle", registry.absolutePath, "--sha256", descriptor.registry.sha256,
            "--root", runtimeRoot.absolutePath, "--config", File(home, ".config/wtmux/wtmux.conf").absolutePath,
            "--preserve-current"
        ))
        runTermux(
            context,
            listOf(
                File(prefix, "bin/bash").absolutePath,
                File(runtimeRoot, "current/setup.sh").absolutePath,
                "--refresh-only", "--no-hooks"
            )
        )
        assertTrue(wtmux.canExecute())
    }

    private fun runTermux(context: android.content.Context, command: List<String>) {
        runTermuxCapture(context, command)
    }

    private fun runTermuxCapture(context: android.content.Context, command: List<String>): String {
        val appRoot = requireNotNull(context.filesDir.parentFile)
        val home = File(appRoot, "files/home")
        val prefix = File(appRoot, "files/usr")
        val process = ProcessBuilder(command).directory(home).apply {
            redirectErrorStream(true)
            environment()["HOME"] = home.absolutePath
            environment()["PREFIX"] = prefix.absolutePath
            environment()["PATH"] = "${File(home, ".local/bin")}:${File(prefix, "bin")}"
            environment()["WTMUX_RUNTIME_BIN_DIR"] = File(prefix, "bin").absolutePath
            enableTermuxExec(environment(), prefix)
        }.start()
        val output = process.inputStream.bufferedReader().readText().take(16 * 1024)
        assertTrue("${command.first()} failed: $output", process.waitFor(60, TimeUnit.SECONDS))
        assertEquals("${command.first()} failed: $output", 0, process.exitValue())
        return output
    }

    private fun File.sha256(): String = inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
