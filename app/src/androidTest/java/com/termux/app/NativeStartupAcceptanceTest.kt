package com.termux.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import androidx.test.rule.ServiceTestRule
import com.termux.app.fleet.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Connected UI acceptance against a private real tmux/OpenSSH fixture. */
@RunWith(AndroidJUnit4::class)
class NativeStartupAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val serviceRule = ServiceTestRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun newestContentAppearsQuicklyAndNotificationReentryRecoversActualSsh() = verifyStartup(20)

    @Test fun notificationRecoveryProbe() = verifyStartup(1)

    private fun verifyStartup(samples: Int) {
        val raw = shell("cat /sdcard/Download/agent-fleet-startup-fixture.json").trim()
        assumeTrue("Requires the isolated native-startup-probe fixture", raw.startsWith("{"))
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish") &&
            "x86_64" in android.os.Build.SUPPORTED_ABIS && android.os.Build.VERSION.SDK_INT == 36)
        val fixture = JSONObject(raw)
        val context = ApplicationProvider.getApplicationContext<Context>()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        AgentFleetEmbeddedRegistryTest().prepareRuntime(context)
        val service = (serviceRule.bindService(Intent(context, TermuxService::class.java)) as TermuxService.LocalBinder).service
        val config = File(context.filesDir, "home/.config/wtmux/wtmux.conf")
        val original = config.takeIf(File::exists)?.readBytes()
        val key = File(context.cacheDir, "native-startup-probe-key")
        val wasEnabled = NativeSessionSettings.isEnabled(context)
        val descriptor = FleetSession("native-startup:${fixture.getString("session")}", "native-startup",
            fixture.getString("session"), "Native startup", "", "native-startup", "codex", "linux",
            "active", false, null, 0)
        val cold = mutableListOf<Long>()
        val warm = mutableListOf<Long>()
        try {
            key.writeText(fixture.getString("privateKey"))
            key.setReadable(false, false); key.setReadable(true, true)
            config.parentFile?.mkdirs()
            config.writeText("WTMUX_MACHINE_IDS=()\n" + fixture.getString("config").replace("@KEY_PATH@", key.absolutePath))
            NativeSessionSettings.setEnabled(context, true)
            DrawerSessionStore(context).recordOpened(descriptor, DrawerSessionSurface.Native)
            DrawerSessionStore(context).setActiveFullscreen(descriptor.id)
            FleetRuntime(context).startWorkspaceSession(descriptor)
            await { service.getAgentFleetWorkspaceSession(descriptor.id)?.terminalSession?.isRunning == true }
            val intent = Intent(context, TermuxActivity::class.java).apply {
                putExtra(AgentFleetContract.EXTRA_COMPOSE_INPUT, true)
                putExtra(AgentFleetContract.EXTRA_NATIVE_SESSION, true)
                putExtra(AgentFleetContract.EXTRA_WORKSPACE_SESSION_ID, descriptor.id)
                putExtra(AgentFleetContract.EXTRA_HOST_ID, descriptor.hostId)
                putExtra(AgentFleetContract.EXTRA_PROJECT, descriptor.project)
                putExtra(AgentFleetContract.EXTRA_INTERNAL_SESSION, descriptor.internalName)
                putExtra(AgentFleetContract.EXTRA_SESSION_NAME, descriptor.name)
                putExtra(AgentFleetContract.EXTRA_INITIAL_SURFACE, AgentFleetContract.SURFACE_NATIVE)
            }
            // Termux creates the PTY when an activity first sizes its terminal.
            // Establish the healthy attachment before measuring cache-cold opens.
            ActivityScenario.launch<TermuxActivity>(intent).use { scenario ->
                tapText("Terminal")
                await(15_000) {
                    service.getAgentFleetWorkspaceSession(descriptor.id)?.terminalSession?.emulator?.screen?.transcriptText?.contains("TERMINAL_STARTUP_READY") == true
                }
                tapText("Native")
                awaitVisible(scenario, "NATIVE_STARTUP_READY", 15_000)
            }
            repeat(samples) {
                NativeConversationContentCache.clear()
                val started = SystemClock.elapsedRealtime()
                ActivityScenario.launch<TermuxActivity>(intent).use { scenario ->
                    awaitVisible(scenario, "NATIVE_STARTUP_READY")
                    cold += SystemClock.elapsedRealtime() - started
                }
            }
            ActivityScenario.launch<TermuxActivity>(intent).use { scenario ->
                awaitVisible(scenario, "NATIVE_STARTUP_READY")
                repeat(samples) {
                    tapText("Terminal")
                    await { textNode("Native") != null }
                    val started = SystemClock.elapsedRealtime()
                    tapText("Native")
                    awaitVisible(scenario, "NATIVE_STARTUP_READY", 500)
                    warm += SystemClock.elapsedRealtime() - started
                }
                screenshot("native-startup-warm.png")
                val old = service.getAgentFleetWorkspaceSession(descriptor.id)!!.terminalSession
                val oldHandle = old.mHandle
                scenario.moveToState(Lifecycle.State.CREATED)
                instrumentation.runOnMainSync { old.finishIfRunning() }
                await { !old.isRunning }
                shell("input keyevent KEYCODE_HOME")
                shell("cmd statusbar expand-notifications")
                await { textNode("Agent Fleet active") != null }
                screenshot("native-startup-notification.png")
                tapText("Agent Fleet active")
                await(15_000) {
                    service.getAgentFleetWorkspaceSession(descriptor.id)?.terminalSession?.let { it.isRunning && it.mHandle != oldHandle } == true
                }
                awaitVisible(scenario, "NATIVE_STARTUP_READY")
                scenario.onActivity { activity ->
                    val current = activity.currentSession
                    assertSame("The visible composer must use the replacement attachment",
                        service.getAgentFleetWorkspaceSession(descriptor.id)!!.terminalSession, current)
                    assertTrue("Replacement selected by the visible activity must be running", current!!.isRunning)
                    assertNotNull("Replacement must have a sized PTY", current.emulator)
                }
                val input = "native-input-notification-${SystemClock.elapsedRealtime()}"
                compose.onNodeWithTag("agent-fleet-message-input").performTextInput(input)
                // Wait for the keyboard resize before using screen coordinates.
                // A semantics text action can finish while the IME is still moving Send.
                instrumentation.waitForIdleSync()
                shell("input keyevent KEYCODE_BACK")
                await { textNode("Send")?.isEnabled == true }
                screenshot("native-startup-before-send.png")
                SystemClock.sleep(250)
                await { AgentFleetComposer.nativeStateForTest().mutationsAllowed }
                compose.onNodeWithTag("agent-fleet-composer-send").assertIsEnabled()
                tapText("Send")
                await { AgentFleetComposer.nativeStateForTest().items.any { "INPUT_RECEIVED: $input" in it.text } }
                compose.onNodeWithTag("native-message-list").performScrollToIndex(0)
                awaitVisible(scenario, "INPUT_RECEIVED: $input")
                assertEquals(descriptor.id, service.getAgentFleetWorkspaceSessionId(service.getAgentFleetWorkspaceSession(descriptor.id)?.terminalSession))
                assertEquals(1, service.termuxSessions.count { it.executionCommand?.commandDescription == AgentFleetContract.WORKSPACE_SESSION_PREFIX + descriptor.id })
                screenshot("native-startup-recovered.png")
            }
            fun p95(values: List<Long>) = values.sorted()[if (samples == 20) 18 else 0]
            assertTrue("Warm p95 ${p95(warm)} ms exceeded 500 ms", p95(warm) <= 500)
            assertTrue("Cold p95 ${p95(cold)} ms exceeded 5000 ms", p95(cold) <= 5_000)
            PlatformTestStorageRegistry.getInstance().openOutputFile(if (samples == 20) "native-startup-receipt.json" else "native-recovery-probe-receipt.json").use {
                it.write(JSONObject().put("host", descriptor.hostId).put("session", descriptor.internalName)
                    .put("warmMillis", JSONArray(warm)).put("coldMillis", JSONArray(cold))
                    .put("warmP95Millis", p95(warm)).put("coldP95Millis", p95(cold))
                    .put("notificationTapped", true).put("recoveredSshInput", true).toString(2).toByteArray())
            }
        } catch (error: Throwable) {
            screenshot("native-startup-failure.png")
            PlatformTestStorageRegistry.getInstance().openOutputFile("native-startup-partial-timings.json").use {
                it.write(JSONObject().put("coldMillis", JSONArray(cold)).put("warmMillis", JSONArray(warm)).toString(2).toByteArray())
            }
            PlatformTestStorageRegistry.getInstance().openOutputFile("native-startup-terminal-failure.txt").use {
                it.write(service.getAgentFleetWorkspaceSession(descriptor.id)?.terminalSession?.emulator?.screen?.transcriptText.orEmpty().takeLast(8_192).toByteArray())
            }
            throw error
        } finally {
            instrumentation.runOnMainSync { service.finishAgentFleetWorkspaceSession(descriptor.id) }
            NativeConversationContentCache.clear()
            NativeSessionSettings.setEnabled(context, wasEnabled)
            if (original == null) config.delete() else config.writeBytes(original)
            key.delete()
        }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun await(timeout: Long = 5_000, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.mainClock.advanceTimeByFrame()
            if (ready()) return
            SystemClock.sleep(10)
        }
        if (!ready()) {
            screenshot("native-startup-timeout.png")
            throw IllegalStateException("Expected fixture UI/attachment state within ${timeout}ms")
        }
    }

    private fun awaitVisible(scenario: ActivityScenario<TermuxActivity>, marker: String, timeout: Long = 5_000) {
        try { await(timeout) {
            var found = false
            scenario.onActivity { found = it.hasWindowFocus() && visibleMarker(it.window.decorView, marker) }
            found
        } } catch (error: Throwable) {
            scenario.onActivity { activity ->
                val field = TermuxActivity::class.java.getDeclaredField("mAgentFleetNativeSession").apply { isAccessible = true }
                val controller = field.get(activity)
                val details = JSONObject()
                for (name in listOf("uiState", "generation", "visible", "enabled", "localSession", "streamProcess")) {
                    val property = controller.javaClass.getDeclaredField(name).apply { isAccessible = true }
                    details.put(name, property.get(controller)?.toString().orEmpty().take(8_192))
                }
                PlatformTestStorageRegistry.getInstance().openOutputFile("native-startup-controller-failure.json").use { it.write(details.toString(2).toByteArray()) }
            }
            throw error
        }
    }

    private fun visibleMarker(view: View, marker: String): Boolean {
        if (view is TextView && marker in view.text) {
            val bounds = Rect()
            return view.isShown && view.getGlobalVisibleRect(bounds) && !bounds.isEmpty
        }
        return view is ViewGroup && (0 until view.childCount).any { visibleMarker(view.getChildAt(it), marker) }
    }

    private fun textNode(text: String): android.view.accessibility.AccessibilityNodeInfo? {
        fun search(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && node.text?.toString() == text) return node
            for (index in 0 until node.childCount) search(node.getChild(index))?.let { return it }
            return null
        }
        return search(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun tapText(text: String) {
        await { textNode(text) != null }
        val node = checkNotNull(textNode(text)) { "Visible tap target missing: $text" }
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        val at = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(at, at + if (action == MotionEvent.ACTION_UP) 30 else 0,
                action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
        }
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        PlatformTestStorageRegistry.getInstance().openOutputFile(name).use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
