package com.termux.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.app.fleet.*
import com.termux.shared.terminal.TermuxTerminalSessionClientBase
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ComposerTerminalNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalKeyboardTestHostActivity>()
    private lateinit var terminal: TerminalSession
    private lateinit var view: TerminalView
    private lateinit var input: TerminalInputController
    private lateinit var bytes: File
    private var writes = 0
    private var viewChanges = 0
    private val attachments = mutableStateListOf("/fixture/image.png")
    @Before fun useProductionKeyboardResize() {
        compose.runOnUiThread { com.termux.shared.view.KeyboardUtils.setSoftInputModeAdjustResize(compose.activity) }
    }

    private fun setup() {
        compose.runOnUiThread {
            bytes = File(compose.activity.cacheDir, "composer-navigation-bytes").apply { writeBytes(byteArrayOf()) }
            input = TerminalInputController().apply {
                bind("composer-fixture", true); connection(true); draft = "Keep my draft"
                onDirect = { viewChanges++ }; onComposer = { viewChanges++ }
            }
            terminal = TerminalSession("/system/bin/sh", "/", arrayOf("sh", "-c",
                "stty -echo -icanon -icrnl -isig min 1 time 0; printf 'READY\\r\\n'; cat > '${bytes.path}'"),
                arrayOf("PATH=/system/bin", "TERM=xterm-256color"), 2000,
                object : TermuxTerminalSessionClientBase() { override fun onTextChanged(session: TerminalSession) {
                    if (::view.isInitialized) view.onScreenUpdated()
                } })
            view = TerminalView(compose.activity, null).apply {
                setTerminalViewClient(WorkspaceTerminalViewClient()); setInputBridge(input)
                setTextSize(26); isFocusableInTouchMode = true; attachSession(terminal)
            }
            input.available = { terminal.isRunning && view.currentSession === terminal }
            input.attachments = { attachments.toList() }
            input.send = { key, bits -> TerminalKeys.encode(key, bits, terminal.emulator)?.let { terminal.write(it); writes++; true } ?: false }
        }
        compose.setContent { AgentFleetTheme(darkTheme = true) {
            CompositionLocalProvider(LocalTerminalInputController provides input) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    AndroidView(factory = { view }, modifier = Modifier.weight(1f).testTag("fixture-terminal"))
                    AgentFleetComposerContent("composer-fixture", AgentFleetComposerNativeState(), attachments, false, null,
                        onShowPendingQuestion = {}, onAttach = {}, onRemoveAttachment = {}, onComposerText = { _, _ -> false })
                }
            }
        } }
        compose.waitUntil(10_000) { terminal.emulator?.screen?.transcriptText?.contains("READY") == true }
    }
    @After fun cleanup() { if (::terminal.isInitialized) compose.runOnUiThread { terminal.finishIfRunning() } }
    private fun mode(ansi: String) = compose.runOnUiThread {
        val data = ansi.toByteArray(); terminal.emulator.append(data, data.size); view.onScreenUpdated()
    }
    private fun swipe() {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300, 5_000)
        val origin = IntArray(2)
        compose.runOnUiThread { view.getLocationOnScreen(origin) }
        val down = android.os.SystemClock.uptimeMillis()
        for (step in 0..20) {
            val action = when (step) { 0 -> android.view.MotionEvent.ACTION_DOWN; 20 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
            val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action,
                origin[0] + view.width * 0.5f, origin[1] + view.height * (0.2f + 0.6f * step / 20), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            android.os.SystemClock.sleep(30)
        }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300, 5_000)
        val bitmap = requireNotNull(automation.takeScreenshot())
        val file = File(compose.activity.externalMediaDirs.first(), "composer-navigation/$name.png")
        file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun composerMouseReportingSwipeSendsWheelEvents() {
        setup(); mode("\u001b[?1000h\u001b[?1006h")
        swipe()
        compose.waitUntil(3_000) { bytes.length() > 0 }
        assertTrue(bytes.readText().startsWith("\u001b[<64;"))
        assertFalse(input.direct); assertEquals(0, writes); assertEquals(0, viewChanges)
        capture("composer-mouse-scroll")
    }
    @Test fun legacyTypingPermissionSuppressesComposerSwipesButAllowsDirect() {
        setup(); mode("\u001b[?1000h\u001b[?1006h")
        compose.runOnUiThread { view.setInputBridge(object : com.termux.view.TerminalInputBridge by input {
            override fun isScrollNavigationAllowed() = input.isInputAllowed()
        }) }
        swipe(); assertEquals(0, bytes.length()); assertFalse(input.direct)
        capture("legacy-composer-wheel-suppressed")
        compose.runOnUiThread { input.chooseDirect() }
        swipe(); compose.waitUntil(3_000) { bytes.length() > 0 }
        assertTrue(bytes.readText().startsWith("\u001b[<64;"))
    }
    @Test fun narrowPaneAndLargeFontsKeepAllFiveShortcutsReachable() {
        val input = TerminalInputController().apply { bind("narrow", true); connection(true); available = { true }; draft = "Keep" }
        val sent = mutableListOf<String>()
        input.send = { key, _ -> sent += key; true }
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.6f)) {
                AgentFleetTheme(darkTheme = true) { Column(Modifier.width(200.dp)) { ComposerTerminalShortcuts(input) } }
            }
        }
        val keys = listOf("UP", "DOWN", "ENTER", "SHIFT_TAB", "SHIFT_LEFT")
        for (key in keys) compose.onNodeWithTag("composer-terminal-key-$key").performScrollTo().assertIsDisplayed().performTouchInput { click() }
        assertEquals(keys, sent); assertEquals("Keep", input.draft); assertFalse(input.direct)
        capture("composer-narrow-large-font")
    }
    @Test fun shortcutsSendEachKeyOnceAndKeepDraftAttachmentsAndReadingView() {
        setup()
        val keys = listOf("UP", "DOWN", "ENTER", "SHIFT_TAB", "SHIFT_LEFT")
        for (key in keys) compose.onNodeWithTag("composer-terminal-key-$key").performScrollTo().performTouchInput { click() }
        val expected = "\u001b[A\u001b[B\r\u001b[Z\u001b[1;2D"
        compose.waitUntil(5_000) { bytes.length() == expected.toByteArray().size.toLong() }
        assertEquals(expected, bytes.readText()); assertEquals(5, writes); assertEquals(0, viewChanges)
        assertFalse(input.direct); assertEquals("Keep my draft", input.draft); assertEquals(listOf("/fixture/image.png"), attachments.toList())
        capture("composer-shortcuts-portrait")
        compose.runOnUiThread { input.connection(false) }
        compose.onNodeWithTag("composer-terminal-key-ENTER").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnUiThread { input.connection(true); input.available = { false } }
        compose.onNodeWithTag("composer-terminal-key-ENTER").assertIsNotEnabled()
        assertEquals(5, writes)
    }
    @Test fun ordinaryHistoryAlternateNavigationAndMouseScrollWorkInBothModes() {
        setup()
        for (direct in listOf(false, true)) {
            compose.runOnUiThread { if (direct) input.chooseDirect() }
            mode("\u001b[?1000l\u001b[?1049l" + (1..100).joinToString("") { "History $it\r\n" })
            val top = TerminalView::class.java.getDeclaredField("mTopRow").apply { isAccessible = true }
            swipe(); compose.runOnUiThread { assertTrue(top.getInt(view) < 0) }
            val before = bytes.length()
            mode("\u001b[?1049h")
            swipe(); compose.waitUntil(3_000) { bytes.length() > before }
            assertTrue(bytes.readText().substring(before.toInt()).startsWith("\u001b[A"))
            mode("\u001b[?1000h\u001b[?1006h")
            val mouseBefore = bytes.length()
            swipe(); compose.waitUntil(3_000) { bytes.length() > mouseBefore }
            assertTrue(bytes.readText().substring(mouseBefore.toInt()).startsWith("\u001b[<64;"))
            capture(if (direct) "direct-scroll" else "composer-scroll")
        }
        compose.runOnUiThread { input.connection(false) }
        val before = bytes.length(); swipe(); compose.waitForIdle(); assertEquals(before, bytes.length())
    }
    @Test fun workspaceComposerSendsOnlyToTheFocusedLivePane() {
        val sessions = listOf("one", "two").map { name -> FleetSession("navigation:$name", "navigation", name, name, name, "fixture", "codex", "linux", "active", false, null, 0) }
        val snapshot = FleetSnapshot("composer-navigation", "2026-10-14T20:00:00Z",
            listOf(FleetHost("navigation", "Fixture", "healthy", "wsl", null, emptySet())), sessions, emptyList(), emptyList())
        val empty = emptyWorkspaceLayout()
        val first = WorkspaceReducer.assign(empty, empty.focusedPaneId, sessions[0].id)
        val split = WorkspaceReducer.split(first, first.focusedPaneId, WorkspaceDirection.Row)
        val second = WorkspaceReducer.assign(split, split.focusedPaneId, sessions[1].id)
        val panes = workspacePanes(second.root)
        var layout = second
        panes.forEach { layout = WorkspaceReducer.setView(layout, it.id, WorkspaceViewMode.Terminal) }
        val state = androidx.compose.runtime.mutableStateOf(AndroidWorkspaceState(WorkspaceReducer.focus(layout, panes[0].id), railCollapsed = true))
        val broker = WorkspaceTerminalBroker(compose.activity)
        val registry = NativeSessionRegistry<WorkspaceRetainedNativeSession>(compose.activity)
        val terminals = mutableListOf<TerminalSession>()
        val files = sessions.mapIndexed { index, _ -> File(compose.activity.cacheDir, "workspace-navigation-$index").apply { writeBytes(byteArrayOf()) } }
        compose.runOnUiThread {
            sessions.forEachIndexed { index, session ->
                val terminal = TerminalSession("/system/bin/sh", "/", arrayOf("sh", "-c", "stty -echo -icanon -icrnl -isig min 1 time 0; printf 'READY\\r\\n'; cat > '${files[index].path}'"),
                    arrayOf("PATH=/system/bin", "TERM=xterm-256color"), 2000, TermuxTerminalSessionClientBase())
                terminals += terminal
                @Suppress("UNCHECKED_CAST")
                val binding = broker.state(session.id) as androidx.compose.runtime.MutableState<WorkspaceTerminalBinding>
                binding.value = WorkspaceTerminalBinding(session.id, terminal = terminal, status = "live", message = "Live")
            }
        }
        fun control(index: Int, tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("workspace-pane-${panes[index].id}")))
        try {
            compose.setContent { AgentFleetTheme(darkTheme = true) {
                CompositionLocalProvider(LocalWorkspaceNativeSessionRegistry provides registry) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        DesktopWorkspaceScreen(snapshot, sessions, state = state.value, broker = broker,
                            onStateChange = { state.value = it }, onMoreSession = { _, _ -> }, onRefresh = {})
                    }
                }
            } }
            compose.waitUntil(10_000) { terminals.all { it.emulator?.screen?.transcriptText?.contains("READY") == true } }
            control(0, "agent-fleet-message-input").performTextInput("Keep workspace draft")
            val keys = listOf("UP", "DOWN", "ENTER", "SHIFT_TAB", "SHIFT_LEFT")
            for (key in keys) control(0, "composer-terminal-key-$key").performScrollTo().assertIsEnabled().performTouchInput { click() }
            val expected = "\u001b[A\u001b[B\r\u001b[Z\u001b[1;2D"
            compose.waitUntil(5_000) { files[0].length() == expected.toByteArray().size.toLong() }
            assertEquals(expected, files[0].readText()); assertEquals(0L, files[1].length())
            control(0, "agent-fleet-message-input").assertTextContains("Keep workspace draft")
            control(1, "composer-terminal-key-ENTER").assertIsNotEnabled()
            assertEquals(panes[0].id, state.value.layout.focusedPaneId)
            assertTrue(workspacePanes(state.value.layout.root).all { it.viewMode == WorkspaceViewMode.Terminal })
            capture("workspace-composer-shortcuts")
        } finally { compose.runOnUiThread { registry.destroy(); broker.close(); terminals.forEach { it.finishIfRunning() } } }
    }
}
