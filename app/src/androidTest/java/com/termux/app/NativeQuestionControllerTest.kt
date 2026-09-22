package com.termux.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.termux.app.fleet.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Connected acceptance. No callback relay or replacement launcher/SSH executable. */
@RunWith(AndroidJUnit4::class)
class NativeQuestionControllerTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun threeAnswersReachCodexThroughPackagedControllerAndSsh() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val raw = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "cat /sdcard/Download/agent-fleet-controller-fixture.json")).bufferedReader().use { it.readText() }.trim()
        assumeTrue("Requires scripts/debug/native-controller-probe.py prepare", raw.startsWith("{"))
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish") &&
            "x86_64" in android.os.Build.SUPPORTED_ABIS && android.os.Build.VERSION.SDK_INT == 36)
        val fixture = JSONObject(raw)
        val context = ApplicationProvider.getApplicationContext<Context>()
        AgentFleetEmbeddedRegistryTest().prepareRuntime(context)
        val home = File(context.filesDir, "home")
        val config = File(home, ".config/wtmux/wtmux.conf")
        val original = config.takeIf { it.exists() }?.readBytes()
        val key = File(context.cacheDir, "controller-probe-key")
        val wasEnabled = NativeSessionSettings.isEnabled(context)
        var controller: NativeSessionController? = null
        var rootView: View? = null
        fun screenshot(name: String) {
            compose.waitForIdle()
            PlatformTestStorageRegistry.getInstance().openOutputFile("controller-$name.png").use {
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        fun visibleMarker(view: View): Boolean {
            if (view is TextView && view.text.contains("ANSWERS_RECEIVED: Codex continued.")) {
                val bounds = Rect()
                return view.isShown && view.getGlobalVisibleRect(bounds) && !bounds.isEmpty
            }
            return view is ViewGroup && (0 until view.childCount).any { visibleMarker(view.getChildAt(it)) }
        }
        try {
            key.writeText(fixture.getString("privateKey")); key.setReadable(false, false); key.setReadable(true, true)
            config.parentFile?.mkdirs()
            config.writeText((original?.toString(Charsets.UTF_8).orEmpty()) + "\n" +
                fixture.getString("config").replace("@KEY_PATH@", key.absolutePath))
            // Keep the real command/environment and retain stderr before UI retries
            // discard it. This read-only probe cannot submit any answer.
            val prefix = File(context.filesDir, "usr")
            val probe = ProcessBuilder(File(prefix, "bin/bash").absolutePath,
                File(prefix, "bin/wtmux").absolutePath, "conversation", "stream", "--host", "controller-probe",
                "--session", fixture.getString("session"), "--limit", "20", "--no-follow")
                .directory(home).redirectErrorStream(true).apply {
                    environment()["HOME"] = home.absolutePath
                    environment()["PREFIX"] = prefix.absolutePath
                    environment()["PATH"] = listOf(File(home, ".local/bin"), File(prefix, "bin"), File(prefix, "bin/applets")).joinToString(":")
                    enableTermuxExec(environment(), prefix)
                }.start()
            val output = StringBuilder()
            val reader = kotlin.concurrent.thread {
                probe.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                    synchronized(output) { if (output.length < 256 * 1024) output.appendLine(line.take(8192)) }
                } }
            }
            val exited = probe.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) probe.destroy()
            reader.join(2000)
            val result = synchronized(output) { output.toString() }
            PlatformTestStorageRegistry.getInstance().openOutputFile("controller-launcher-preflight.txt").use {
                it.write(result.toByteArray())
            }
            check(exited && probe.exitValue() == 0) { "Packaged launcher preflight failed: ${result.take(2048)}" }
            NativeSessionSettings.setEnabled(context, true)
            ActivityScenario.launch(DrawerComposeTestHostActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val view = ComposeView(activity)
                    rootView = view
                    activity.setContentView(view)
                    val host = object : NativeSessionHost {
                        override val nativeContext: Context = activity
                        override val nativeInlineComposer = true
                        override fun sendAgentFleetComposerText(text: String, appendEnter: Boolean) = false
                        override fun sendAgentFleetControlC() = false
                        override fun sendAgentFleetKey(key: String) = false
                        override fun pickAgentFleetImages() = Unit
                        override fun pickAgentFleetCamera() = Unit
                        override fun setAgentFleetNativeView(nativeAvailable: Boolean, nativeView: Boolean,
                            automaticTerminal: Boolean, aiComposer: Boolean) = Unit
                        override fun closeAgentFleetSessionTab() = Unit
                    }
                    controller = NativeSessionController(host, view).also {
                        it.bind(Intent().putExtra(AgentFleetContract.EXTRA_HOST_ID, "controller-probe")
                            .putExtra(AgentFleetContract.EXTRA_INTERNAL_SESSION, fixture.getString("session"))
                            .putExtra(AgentFleetContract.EXTRA_NATIVE_SESSION, true))
                        it.onStart()
                    }
                }
                val questions = fixture.getJSONArray("questions")
                check(questions.length() == 3)
                fun tag(i: Int): String = questions.getJSONObject(i).let {
                    "question-option-${it.getString("id")}-${it.getString("secondOption")}" }
                try {
                    compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag(0)).fetchSemanticsNodes().isNotEmpty() }
                } catch (error: Throwable) {
                    screenshot("failure")
                    val field = NativeSessionController::class.java.getDeclaredField("uiState").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    val state = (field.get(controller) as androidx.compose.runtime.State<NativeSessionUiState>).value
                    PlatformTestStorageRegistry.getInstance().openOutputFile("controller-failure.json").use {
                        it.write(JSONObject().put("connection", state.connection).put("error", state.error)
                            .put("hostId", state.hostId).put("session", state.internalSession)
                            .put("itemCount", state.items.size).put("configRetained", "controller-probe" in config.readText())
                            .put("launcher", File(home, ".local/bin/wtmux").canonicalPath)
                            .put("prefixLauncher", File(context.filesDir, "usr/bin/wtmux").canonicalPath)
                            .toString(2).toByteArray())
                    }
                    throw error
                }
                screenshot("prompt")
                repeat(3) { index ->
                    compose.onNodeWithTag(tag(index)).performScrollTo().performClick()
                    screenshot("tap-${index + 1}")
                }
                compose.waitUntil(90_000) {
                    var found = false
                    instrumentation.runOnMainSync { found = rootView?.let(::visibleMarker) == true }
                    found
                }
                compose.runOnIdle {
                    assertTrue("The provider continuation must be visible", rootView?.let(::visibleMarker) == true)
                }
                screenshot("continued")
                PlatformTestStorageRegistry.getInstance().openOutputFile("controller-ui-receipt.json").use {
                    it.write(JSONObject().put("session", fixture.getString("session"))
                        .put("observedAt", java.time.Instant.now().toString()).put("actualController", true)
                        .put("selectedQuestionCount", 3).put("visibleContinuation", true)
                        .put("runtime", EmbeddedRuntimeManager(context).descriptor().baselineVersion)
                        .toString(2).toByteArray())
                }
                scenario.onActivity { controller?.close(); controller = null }
            }
        } finally {
            instrumentation.runOnMainSync { controller?.close() }
            if (original == null) config.delete() else config.writeBytes(original)
            key.delete()
            NativeSessionSettings.setEnabled(context, wasEnabled)
        }
    }
}
