package com.termux.app

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import com.termux.app.fleet.*
import com.termux.view.TerminalView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real installed Codex, private tmux and pinned SSH; never sends a model request. */
@RunWith(AndroidJUnit4::class)
class TerminalCodexAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val serviceRule = ServiceTestRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun await(timeout: Long = 60_000, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.mainClock.advanceTimeByFrame()
            if (ready()) return
            SystemClock.sleep(20)
        }
        assertTrue("Expected actual Codex UI within ${timeout}ms", ready())
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
    private fun selectTerminal() {
        await { textNode("Terminal") != null || textNode("Native") != null }
        if (textNode("Terminal") != null) tapText("Terminal")
    }
    private fun tapText(text: String) {
        await { textNode(text) != null }
        val node = checkNotNull(textNode(text))
        val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
        val at = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(at, at + if (action == android.view.MotionEvent.ACTION_UP) 30 else 0,
                action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
        }
    }
    private fun state(view: TerminalView): String = try {
        TerminalView::class.java.getMethod("getPresentationState").invoke(view).toString()
    } catch (_: NoSuchMethodException) { "BASELINE" }

    @Test fun coldAttachReconnectKeyboardRotationAndZoom() {
        val raw = shell("cat /sdcard/Download/agent-fleet-terminal-codex-fixture.json").trim()
        assumeTrue("Requires private actual-Codex fixture", raw.startsWith("{"))
        check(android.os.Build.VERSION.SDK_INT == 36 && "x86_64" in android.os.Build.SUPPORTED_ABIS)
        val fixture = JSONObject(raw)
        val context = ApplicationProvider.getApplicationContext<Context>()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        AgentFleetEmbeddedRegistryTest().prepareRuntime(context)
        val service = (serviceRule.bindService(Intent(context, TermuxService::class.java)) as TermuxService.LocalBinder).service
        val config = File(context.filesDir, "home/.config/wtmux/wtmux.conf")
        val original = config.takeIf(File::exists)?.readBytes()
        val key = File(context.cacheDir, "terminal-codex-key")
        val wasEnabled = NativeSessionSettings.isEnabled(context)
        val descriptor = FleetSession("native-startup:${fixture.getString("session")}", "native-startup",
            fixture.getString("session"), "Terminal acceptance", "", "native-startup", "codex", "linux", "active", false, null, 0)
        val observations = JSONArray()
        val output = context.externalMediaDirs.first()
        fun capture(name: String, scenario: ActivityScenario<TermuxActivity>) {
            scenario.onActivity { activity ->
                val view = activity.terminalView
                observations.put(JSONObject().put("phase", name).put("state", state(view))
                    .put("at", SystemClock.elapsedRealtime()).put("width", view.width).put("height", view.height)
                    .put("alpha", view.alpha).put("shown", view.isShown)
                    .put("columns", view.mEmulator?.mColumns).put("rows", view.mEmulator?.mRows)
                    .put("sessionHandle", view.currentSession?.mHandle))
            }
            instrumentation.waitForIdleSync(); SystemClock.sleep(250)
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(output, "codex-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        fun settled(scenario: ActivityScenario<TermuxActivity>) = await {
            var ready = false
            scenario.onActivity { activity ->
                val view = activity.terminalView
                ready = view.mEmulator?.screen?.transcriptText?.contains("Codex", ignoreCase = true) == true &&
                    state(view) in setOf("READY", "BASELINE") && view.alpha == 1f && view.width > 0 && view.height > 0 &&
                    view.mEmulator?.let { it.screen.getSelectedText(0, 0, it.mColumns, it.mRows).isNotBlank() } == true
            }
            ready
        }
        try {
            key.writeText(fixture.getString("privateKey")); key.setReadable(false, false); key.setReadable(true, true)
            config.parentFile?.mkdirs()
            config.writeText("WTMUX_MACHINE_IDS=()\n" + fixture.getString("config").replace("@KEY_PATH@", key.absolutePath))
            NativeSessionSettings.setEnabled(context, true)
            DrawerSessionStore(context).recordOpened(descriptor, DrawerSessionSurface.Terminal)
            DrawerSessionStore(context).setActiveFullscreen(descriptor.id)
            FleetRuntime(context).startWorkspaceSession(descriptor)
            val intent = Intent(context, TermuxActivity::class.java).apply {
                putExtra(AgentFleetContract.EXTRA_COMPOSE_INPUT, true)
                putExtra(AgentFleetContract.EXTRA_NATIVE_SESSION, true)
                putExtra(AgentFleetContract.EXTRA_WORKSPACE_SESSION_ID, descriptor.id)
                putExtra(AgentFleetContract.EXTRA_HOST_ID, descriptor.hostId)
                putExtra(AgentFleetContract.EXTRA_PROJECT, descriptor.project)
                putExtra(AgentFleetContract.EXTRA_INTERNAL_SESSION, descriptor.internalName)
                putExtra(AgentFleetContract.EXTRA_SESSION_NAME, descriptor.name)
                putExtra(AgentFleetContract.EXTRA_INITIAL_SURFACE, AgentFleetContract.SURFACE_TERMINAL)
            }
            ActivityScenario.launch<TermuxActivity>(intent).use { scenario ->
                selectTerminal()
                capture("loading", scenario); settled(scenario); capture("cold", scenario)
                compose.onNodeWithTag("agent-fleet-message-input").performClick()
                await { var shown = false; scenario.onActivity { shown = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }; shown }
                settled(scenario); capture("keyboard", scenario)
                scenario.onActivity { activity ->
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
                }
                await { var hidden = false; scenario.onActivity { hidden = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false }; hidden }
                settled(scenario); capture("keyboard-hidden", scenario)
                scenario.onActivity { activity ->
                    (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(activity.terminalView.windowToken, 0)
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                await { var wide = false; scenario.onActivity { wide = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }; wide }
                scenario.onActivity { WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
                capture("rotation-pending", scenario)
                settled(scenario); capture("rotation", scenario)
                scenario.onActivity { it.terminalView.setTextSize(30) }
                settled(scenario); capture("zoom", scenario)
                val old = service.getAgentFleetWorkspaceSession(descriptor.id)!!.terminalSession
                scenario.moveToState(Lifecycle.State.CREATED)
                instrumentation.runOnMainSync { old.finishIfRunning() }
                await { !old.isRunning }
                shell("input keyevent KEYCODE_HOME")
                shell("cmd statusbar expand-notifications")
                tapText("Agent Fleet active")
                await { service.getAgentFleetWorkspaceSession(descriptor.id)?.terminalSession?.let { it.isRunning && it.mHandle != old.mHandle } == true }
                selectTerminal()
                settled(scenario)
                scenario.onActivity { assertEquals(service.getAgentFleetWorkspaceSession(descriptor.id)?.terminalSession?.mHandle, it.terminalView.currentSession?.mHandle) }
                capture("reconnect", scenario)
            }
            File(output, "codex-terminal-observations.json").writeText(observations.toString(2))
        } finally {
            File(output, "codex-terminal-observations.json").writeText(observations.toString(2))
            instrumentation.runOnMainSync {
                val terminal = service.getAgentFleetWorkspaceSession(descriptor.id)?.terminalSession
                File(output, "codex-terminal-debug.txt").writeText("pid=${terminal?.pid} columns=${terminal?.emulator?.mColumns} rows=${terminal?.emulator?.mRows}\n" + terminal?.emulator?.screen?.transcriptText.orEmpty().takeLast(8192))
                service.finishAgentFleetWorkspaceSession(descriptor.id)
            }
            NativeSessionSettings.setEnabled(context, wasEnabled)
            if (original == null) config.delete() else config.writeBytes(original)
            key.delete()
        }
    }
}
