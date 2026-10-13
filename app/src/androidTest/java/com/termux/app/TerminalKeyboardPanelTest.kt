package com.termux.app

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.app.fleet.*
import com.termux.shared.terminal.TermuxTerminalSessionClientBase
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalKeyboardPanelTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalKeyboardTestHostActivity>()
    private lateinit var previousEntry: TerminalKeyboardSettings.Entry
    private lateinit var settingsVisible: MutableState<Boolean>
    @Before fun resize() { compose.runOnUiThread {
        com.termux.shared.view.KeyboardUtils.setSoftInputModeAdjustResize(compose.activity)
        previousEntry = TerminalKeyboardSettings.load(compose.activity)
        TerminalKeyboardSettings.save(compose.activity, TerminalKeyboardSettings.Entry.Hidden)
    } }
    @After fun restoreEntryPreference() { compose.runOnUiThread { TerminalKeyboardSettings.save(compose.activity, previousEntry) } }
    private lateinit var view: TerminalView
    private lateinit var terminal: TerminalSession
    private lateinit var input: TerminalInputController
    private val keys = mutableListOf<String>()
    private fun setup(bytes: Int = 16, showSystem: Boolean = true, chooseSettings: Boolean = false) {
        check(android.os.Build.VERSION.SDK_INT == 36 && "x86_64" in android.os.Build.SUPPORTED_ABIS)
        compose.runOnUiThread {
            settingsVisible = mutableStateOf(chooseSettings)
            input = TerminalKeyboardSettings.controller(compose.activity).apply { bind("keyboard-fixture", true); connection(true); available = { true }; draft = "Keep this draft" }
            terminal = TerminalSession("/system/bin/sh", "/", arrayOf("sh", "-c",
                "stty -echo -icanon -icrnl -isig min 1 time 0; printf 'READY\\r\\n'; dd bs=1 count=$bytes 2>/dev/null | od -An -tx1; printf 'CAPTURED\\r\\n'; sleep 120"),
                arrayOf("PATH=/system/bin", "TERM=xterm-256color"), 2000,
                object : TermuxTerminalSessionClientBase() { override fun onTextChanged(session: TerminalSession) { if (::view.isInitialized) view.onScreenUpdated() } })
            view = TerminalView(compose.activity, null).apply {
                setTerminalViewClient(WorkspaceTerminalViewClient()); setInputBridge(input); setTextSize(26)
                isFocusableInTouchMode = true; attachSession(terminal)
            }
            input.literal = view::inputAppCodePoint
            input.freezeComposition = view::freezeImeComposition
            input.pendingComposition = view::hasPendingImeComposition
            input.discardComposition = view::discardImeComposition
            input.onKeyboard = { TerminalKeyboard.show(view) }
            input.send = { name, bits -> TerminalKeys.encode(name, bits, terminal.emulator)?.let { keys += it; terminal.write(it); true } ?: false }
        }
        compose.setContent { AgentFleetTheme(darkTheme = true) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                AndroidView(factory = { view }, modifier = Modifier.weight(1f))
                if (settingsVisible.value) TerminalKeyboardSettingsCard()
                else if (showSystem) TerminalInputControls(input)
                else CompositionLocalProvider(LocalTerminalInputController provides input) {
                    AgentFleetComposerContent("keyboard-fixture", AgentFleetComposerNativeState(), emptyList(), false, null,
                        onShowPendingQuestion = {}, onAttach = {}, onRemoveAttachment = {}, onComposerText = { _, _ -> false })
                }
            }
        } }
        await("READY")
        if (chooseSettings) {
            tap("terminal-keyboard-entry-terminal")
            assertEquals(TerminalKeyboardSettings.Entry.Terminal, TerminalKeyboardSettings.load(compose.activity))
            compose.runOnUiThread { settingsVisible.value = false }
        }
        if (!showSystem) {
            compose.onNodeWithTag("agent-fleet-message-input").performClick()
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            tap("terminal-input-direct")
        } else compose.onNodeWithTag("terminal-input-direct").performClick()
        if (showSystem) {
            compose.runOnUiThread { input.chooseSurface(false) }
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        }
    }
    private fun await(text: String) { compose.waitUntil(10_000) { var found = false; compose.runOnUiThread { found = terminal.emulator?.screen?.transcriptText?.contains(text) == true }; found } }
    private fun tap(tag: String) {
        if (tag.startsWith("terminal-app-key-")) compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any { androidx.compose.ui.semantics.SemanticsProperties.Disabled !in it.config }
        }
        compose.waitForIdle()
        val node = compose.onNodeWithTag(tag)
        if (tag == "terminal-input-surface") node.performScrollTo()
        tapNode(node)
    }
    private fun tapNode(node: SemanticsNodeInteraction) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300, 5_000)
        compose.waitForIdle()
        var previous: androidx.compose.ui.geometry.Rect? = null
        var stable = 0
        compose.waitUntil(5_000) {
            val next = node.fetchSemanticsNode().boundsInWindow
            stable = if (next == previous) stable + 1 else 0
            previous = next
            stable >= 5
        }
        val bounds = node.fetchSemanticsNode().boundsInWindow
        val origin = IntArray(2); val frame = android.graphics.Rect()
        compose.runOnUiThread { compose.activity.window.decorView.getLocationOnScreen(origin); compose.activity.window.decorView.getWindowVisibleDisplayFrame(frame) }
        assertTrue("Tap must be inside visible window: $bounds frame=$frame", bounds.bottom + origin[1] <= frame.bottom &&
            bounds.left + origin[0] >= frame.left && bounds.right + origin[0] <= frame.right)
        val start = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(start, android.os.SystemClock.uptimeMillis(), action, bounds.center.x + origin[0], bounds.center.y + origin[1], 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        compose.waitForIdle()
    }
    private fun terminalSurface() {
        tap("terminal-input-surface")
        compose.onNodeWithTag("terminal-surface-terminal").performClick()
        compose.waitUntil(5_000) { input.keyboardReady }
        assertFalse(ViewCompat.getRootWindowInsets(view)!!.isVisible(WindowInsetsCompat.Type.ime()))
        compose.onNodeWithTag("terminal-key-panel").assertDoesNotExist()
    }
    private fun capture(name: String) {
        compose.waitUntil(5_000) { !input.terminalSurface || input.keyboardDismissed || input.keyboardReady }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.waitForIdle(); automation.waitForIdle(300, 5_000)
        compose.waitUntil(5_000) { view.presentationState == TerminalView.PresentationState.READY }
        val before = view.terminalPaintCount
        compose.runOnUiThread { view.onScreenUpdated(); view.invalidate() }
        compose.waitUntil(5_000) { view.terminalPaintCount > before }
        automation.waitForIdle(300, 5_000)
        val bitmap = requireNotNull(automation.takeScreenshot())
        val file = java.io.File(compose.activity.externalMediaDirs.first(), "terminal-keyboard/$name.png")
        file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun settingsOfferBothPersistentEntryModesAndAlwaysHideTheSystemImeInDirect() {
        setup(1, showSystem = false, chooseSettings = true)
        try {
            compose.waitUntil(5_000) { input.keyboardReady }
            assertTrue(input.wantsTerminalKeyboard()); assertFalse(ViewCompat.getRootWindowInsets(view)!!.isVisible(WindowInsetsCompat.Type.ime()))
            capture("settings-terminal-entry")
            tap("terminal-input-keyboard"); assertTrue(input.keyboardDismissed)
            compose.runOnUiThread { input.connection(false); input.connection(true); input.onKeyboard(); settingsVisible.value = true }
            compose.onNodeWithTag("terminal-keyboard-entry-terminal").assertIsSelected()
            capture("direct-keyboard-settings")
            tap("terminal-keyboard-entry-hidden")
            assertEquals(TerminalKeyboardSettings.Entry.Hidden, TerminalKeyboardSettings.load(compose.activity))
            compose.runOnUiThread { settingsVisible.value = false; input.chooseComposer() }
            compose.onNodeWithTag("agent-fleet-message-input").performClick()
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            tap("terminal-input-direct")
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
            assertTrue(input.keyboardDismissed); compose.onNodeWithTag("terminal-keyboard-dock").assertDoesNotExist()
            assertEquals("Keep this draft", input.draft); assertTrue(keys.isEmpty())
            tap("terminal-input-keyboard"); compose.waitUntil(5_000) { input.keyboardReady }
            tap("terminal-app-key-CHAR_97"); await("61"); await("CAPTURED")
        } finally { compose.runOnUiThread { terminal.finishIfRunning() } }
    }
    @Test fun directStartsCollapsedAndRealButtonRestoresEitherKeyboardWithoutFlushingText() {
        setup(1, showSystem = false)
        try {
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
            assertTrue(input.keyboardDismissed)
            compose.onNodeWithTag("terminal-key-panel").assertDoesNotExist()
            compose.onNodeWithTag("terminal-keyboard-dock").assertDoesNotExist()
            capture("direct-collapsed")
            tap("terminal-input-keyboard")
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            val visibleHeight = view.height
            val old = arrayOfNulls<InputConnection>(1)
            compose.runOnUiThread {
                old[0] = view.onCreateInputConnection(EditorInfo())
                assertNotNull(old[0]); old[0]!!.setComposingText("unfinished", 1)
            }
            capture("direct-system-visible")
            tap("terminal-input-keyboard")
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == false && view.height > visibleHeight + 100 }
            compose.onNodeWithTag("terminal-key-panel").assertDoesNotExist()
            compose.runOnUiThread {
                assertTrue(view.hasPendingImeComposition())
                assertNull(view.onCreateInputConnection(EditorInfo()))
                old[0]!!.commitText("BAD", 1); old[0]!!.finishComposingText()
                assertTrue(view.hasPendingImeComposition())
            }
            capture("direct-system-hidden")
            tap("terminal-input-keyboard")
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            compose.runOnUiThread { assertTrue(view.hasPendingImeComposition()) }
            tap("terminal-input-surface"); compose.onNodeWithTag("terminal-surface-terminal").performClick()
            tapNode(compose.onNodeWithText("Discard and switch"))
            compose.waitUntil(5_000) { input.keyboardReady }
            tap("terminal-input-keyboard")
            compose.onNodeWithTag("terminal-keyboard-dock").assertDoesNotExist()
            tap("terminal-input-keyboard")
            compose.waitUntil(5_000) { input.keyboardReady }
            compose.runOnUiThread { input.dismissKeyboard(); input.connection(false); input.connection(true); input.onKeyboard() }
            tap("terminal-input-direct")
            assertTrue(input.keyboardDismissed)
            compose.onNodeWithTag("terminal-keyboard-dock").assertDoesNotExist()
            assertEquals("Keep this draft", input.draft); assertTrue(keys.isEmpty())
            compose.runOnUiThread { view.onKeyDown(KeyEvent.KEYCODE_X, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_X)) }
            await("78"); await("CAPTURED")
        } finally { compose.runOnUiThread { terminal.finishIfRunning() } }
    }
    @Test fun realTouchesSendAsciiChordsAndSystemUnicodeExactlyOnce() {
        setup()
        try {
            val old = arrayOfNulls<InputConnection>(1)
            compose.runOnUiThread { old[0] = view.onCreateInputConnection(EditorInfo()) }
            terminalSurface(); capture("qwerty")
            compose.runOnUiThread {
                old[0]!!.commitText("BAD", 1); old[0]!!.deleteSurroundingText(1, 0); old[0]!!.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_X))
                assertNull(view.onCreateInputConnection(EditorInfo()))
            }
            tap("terminal-app-key-SHIFT"); tap("terminal-app-key-CHAR_49") // !
            // True two-pointer held Ctrl+C (no modifier lock after release).
            val ctrl = compose.onNodeWithTag("terminal-app-key-CTRL").fetchSemanticsNode().boundsInRoot.center
            val c = compose.onNodeWithTag("terminal-app-key-CHAR_99").fetchSemanticsNode().boundsInRoot.center
            compose.onRoot().performTouchInput { down(0, ctrl); down(1, c); up(1); up(0) }
            tap("terminal-app-key-ALT"); tap("terminal-app-key-CHAR_98")
            tap("terminal-app-key-PAGE_Fn"); capture("fn")
            tap("terminal-app-key-CTRL"); tap("terminal-app-key-SHIFT_LEFT")
            tap("terminal-app-key-PAGE_ABC"); tap("terminal-app-key-PAGE_Symbols"); capture("symbols")
            compose.runOnUiThread { view.onKeyDown(KeyEvent.KEYCODE_X, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_X)) }
            tap("terminal-input-surface"); compose.onNodeWithTag("terminal-surface-system").performClick()
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            compose.runOnUiThread { view.onCreateInputConnection(EditorInfo())!!.commitText("🙂", 1) }
            compose.onNodeWithTag("terminal-key-ENTER").performClick()
            await("CAPTURED"); await("21 03 1b 62 1b 5b 31 3b 32 44 78 f0 9f 99 82 0d")
            assertEquals(1, keys.count { it == "\u001b[1;2D" })
            compose.onNodeWithTag("terminal-input-composer").performClick()
            assertEquals("Keep this draft", input.draft)
            capture("system-return")
        } finally { compose.runOnUiThread { terminal.finishIfRunning() } }
    }
    @Test fun preeditIsExplicitlyDiscardedAndBackDoesNotSendEscape() {
        setup(1)
        try {
            compose.runOnUiThread { view.onCreateInputConnection(EditorInfo())!!.setComposingText("unfinished", 1) }
            tap("terminal-input-surface"); compose.onNodeWithTag("terminal-surface-terminal").performClick()
            tapNode(compose.onNodeWithText("Keep System").assertIsDisplayed())
            assertFalse(input.terminalSurface); assertFalse("Keep must dismiss the pending choice", input.surfaceBlocked)
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            compose.runOnUiThread { assertTrue("Keep System must retain unfinished text", view.hasPendingImeComposition()) }
            tap("terminal-input-surface"); compose.onNodeWithTag("terminal-surface-terminal").performClick()
            tapNode(compose.onNodeWithText("Discard and switch"))
            assertTrue("A real Discard tap must switch the surface", input.terminalSurface)
            assertFalse(input.surfaceBlocked)
            try { compose.waitUntil(5_000) { input.keyboardReady } }
            catch (failure: Throwable) {
                val insets = ViewCompat.getRootWindowInsets(view)
                val state = org.json.JSONObject().put("direct", input.direct).put("terminal", input.terminalSurface)
                    .put("dismissed", input.keyboardDismissed).put("available", input.canInput())
                    .put("ime", insets?.isVisible(WindowInsetsCompat.Type.ime())).put("windowFocus", view.hasWindowFocus())
                    .put("widthDp", compose.activity.resources.configuration.screenWidthDp).put("heightDp", compose.activity.resources.configuration.screenHeightDp)
                val folder = java.io.File(compose.activity.externalMediaDirs.first(), "terminal-keyboard"); folder.mkdirs()
                java.io.File(folder, "preedit-ready-state.json").writeText(state.toString(2))
                val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                java.io.File(folder, "preedit-ready-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                throw failure
            }
            compose.runOnUiThread {
                view.onKeyDown(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                view.onKeyUp(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            }
            compose.onNodeWithTag("terminal-keyboard-dock").assertDoesNotExist()
            assertTrue(keys.isEmpty()); assertTrue(input.keyboardDismissed)
            compose.runOnUiThread { input.connection(false); input.connection(true); input.onKeyboard() }
            compose.onNodeWithTag("terminal-keyboard-dock").assertDoesNotExist()
            compose.onNodeWithTag("terminal-input-keyboard").performClick()
            compose.waitUntil(5_000) { input.keyboardReady }
            tap("terminal-app-key-CHAR_97"); await("61"); await("CAPTURED")
        } finally { compose.runOnUiThread { terminal.finishIfRunning() } }
    }
    @Test fun repeatStopsOnReleaseFocusLossAndModeChange() {
        setup(100)
        try {
            terminalSurface(); tap("terminal-app-key-PAGE_Fn")
            compose.waitUntil(5_000) { input.keyboardReady }
            // Keep a real pointer held while the UI/test clock can process repeat callbacks.
            val bounds = compose.onNodeWithTag("terminal-app-key-LEFT").fetchSemanticsNode().boundsInRoot
            compose.onRoot().performTouchInput { down(bounds.center) }
            compose.waitUntil(2_000) { keys.size >= 3 }
            compose.onRoot().performTouchInput { up() }
            compose.runOnIdle { assertTrue("Held arrow must repeat", keys.size >= 3) }
            val after = keys.size; Thread.sleep(220); compose.runOnIdle { assertEquals(after, keys.size) }
            compose.onRoot().performTouchInput { down(bounds.center) }
            compose.runOnUiThread { input.focus(false) }
            val canceled = keys.size; Thread.sleep(500)
            compose.onRoot().performTouchInput { up() }
            compose.runOnIdle { assertEquals(canceled, keys.size); input.focus(true); input.chooseComposer() }
            compose.onNodeWithTag("terminal-keyboard-dock").assertDoesNotExist()
        } finally { compose.runOnUiThread { terminal.finishIfRunning() } }
    }
    @Test fun workspaceHasOneDockAndMovesInputAuthorityWithFocus() {
        val sessions = listOf("one", "two").map { name -> FleetSession("fixture:$name", "fixture", name, name, name, "fixture", "shell", "linux", "active", false, null, 0) }
        val snapshot = FleetSnapshot("keyboard-workspace", "2026-10-13T11:00:00Z", listOf(FleetHost("fixture", "Fixture", "healthy", "wsl", null, emptySet())), sessions, emptyList(), emptyList())
        val empty = emptyWorkspaceLayout()
        val first = WorkspaceReducer.assign(empty, empty.focusedPaneId, sessions[0].id)
        val split = WorkspaceReducer.split(first, first.focusedPaneId, WorkspaceDirection.Row)
        val second = WorkspaceReducer.assign(split, split.focusedPaneId, sessions[1].id)
        val panes = workspacePanes(second.root)
        val state = mutableStateOf(AndroidWorkspaceState(WorkspaceReducer.focus(second, panes[0].id), railCollapsed = true))
        val broker = WorkspaceTerminalBroker(compose.activity)
        val registry = NativeSessionRegistry<WorkspaceRetainedNativeSession>(compose.activity)
        val terminals = mutableListOf<TerminalSession>()
        var focusRevokedBeforePublication = false
        fun terminalViews(view: android.view.View): List<TerminalView> = when (view) {
            is TerminalView -> listOf(view)
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { terminalViews(view.getChildAt(it)) }
            else -> emptyList()
        }
        fun captureWorkspace(name: String) {
            compose.waitForIdle()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.waitForIdle(300, 5_000)
            val bitmap = requireNotNull(automation.takeScreenshot())
            val folder = java.io.File(compose.activity.externalMediaDirs.first(), "terminal-keyboard"); folder.mkdirs()
            java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val details = terminalViews(compose.activity.window.decorView).map { terminalView ->
                val authority = terminalView.inputBridge as? TerminalInputController
                mapOf("direct" to authority?.direct, "canInput" to authority?.canInput(), "systemIme" to authority?.isSystemImeAllowed(), "generation" to authority?.getInputGeneration())
            }
            java.io.File(folder, "$name-state.json").writeText(org.json.JSONArray(details.map { org.json.JSONObject(it) }).toString(2))
        }
        compose.runOnUiThread {
            sessions.forEach { session ->
                val t = TerminalSession("/system/bin/sh", "/", arrayOf("sh", "-c", "stty -echo -icanon -icrnl -isig min 1 time 0; printf 'READY\\r\\n'; dd bs=1 count=1 2>/dev/null | od -An -tx1; sleep 120"), arrayOf("PATH=/system/bin", "TERM=xterm-256color"), 2000, TermuxTerminalSessionClientBase())
                terminals += t
                @Suppress("UNCHECKED_CAST")
                val binding = broker.state(session.id) as MutableState<WorkspaceTerminalBinding>
                binding.value = WorkspaceTerminalBinding(session.id, terminal = t, status = "live", message = "Live")
            }
        }
        try {
            compose.setContent { AgentFleetTheme(darkTheme = true) {
                CompositionLocalProvider(LocalWorkspaceNativeSessionRegistry provides registry) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        DesktopWorkspaceScreen(snapshot, sessions, state = state.value, broker = broker, onStateChange = { next ->
                            if (next.layout.focusedPaneId != state.value.layout.focusedPaneId) {
                                val oldView = terminalViews(compose.activity.window.decorView).firstOrNull { it.currentSession === terminals[0] }
                                focusRevokedBeforePublication = oldView?.inputBridge?.isInputAllowed() == false
                            }
                            state.value = next
                        }, onMoreSession = { _, _ -> }, onRefresh = {})
                    }
                }
            } }
            fun paneControl(index: Int, tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("workspace-pane-${panes[index].id}")))
            compose.waitUntil(10_000) { terminals.all { it.emulator?.screen?.transcriptText?.contains("READY") == true } }
            compose.waitUntil(5_000) { workspacePanes(state.value.layout.root).all { it.viewMode == WorkspaceViewMode.Terminal } }
            assertEquals("Shell initialization must retain the selected pane", panes[0].id, state.value.layout.focusedPaneId)
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
            paneControl(0, "terminal-input-keyboard").assertIsDisplayed()
            captureWorkspace("workspace-collapsed")
            tapNode(paneControl(0, "terminal-input-keyboard"))
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            captureWorkspace("workspace-before-selector")
            tapNode(paneControl(0, "terminal-input-surface").performScrollTo())
            captureWorkspace("workspace-selector")
            compose.onNodeWithTag("terminal-surface-terminal").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("terminal-app-key-CHAR_97").fetchSemanticsNodes().any { androidx.compose.ui.semantics.SemanticsProperties.Disabled !in it.config } }
            compose.onNodeWithTag("terminal-app-key-CHAR_97").assertIsEnabled()
            compose.onAllNodesWithTag("terminal-keyboard-dock").assertCountEquals(1)
            tap("terminal-app-key-CHAR_97")
            compose.waitUntil(5_000) { terminals[0].emulator.screen.transcriptText.contains("61") }
            tapNode(compose.onNodeWithTag("workspace-pane-${panes[1].id}"))
            assertTrue("Old pane input must be revoked before the state callback", focusRevokedBeforePublication)
            compose.onNodeWithTag("terminal-keyboard-dock").assertDoesNotExist()
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
            paneControl(1, "terminal-input-keyboard").assertIsDisplayed()
            tapNode(paneControl(1, "terminal-input-keyboard"))
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            tapNode(paneControl(1, "terminal-input-surface").performScrollTo())
            compose.onNodeWithTag("terminal-surface-terminal").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("terminal-app-key-CHAR_98").fetchSemanticsNodes().any { androidx.compose.ui.semantics.SemanticsProperties.Disabled !in it.config } }
            compose.onAllNodesWithTag("terminal-keyboard-dock").assertCountEquals(1)
            tap("terminal-app-key-CHAR_98")
            compose.waitUntil(5_000) { terminals[1].emulator.screen.transcriptText.contains("62") }
            assertFalse(terminals[0].emulator.screen.transcriptText.contains("62"))
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.waitForIdle(300, 5_000)
            val bitmap = requireNotNull(automation.takeScreenshot())
            val file = java.io.File(compose.activity.externalMediaDirs.first(), "terminal-keyboard/workspace.png")
            file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        } finally { compose.runOnUiThread { registry.destroy(); broker.close(); terminals.forEach { it.finishIfRunning() } } }
    }

    @Test fun savedSurfaceAndDismissalRestoreWithoutModifierOrDraftLoss() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        lateinit var current: TerminalInputController
        restoration.setContent {
            val controller = rememberTerminalInputController("keyboard-saved-pane", true)
            current = controller
            SideEffect { controller.available = { true }; controller.connection(true) }
            AgentFleetTheme(darkTheme = true) {
                TerminalInputControls(controller)
            }
        }
        compose.runOnIdle {
            current.draft = "Saved draft"
            current.chooseSurface(true)
            current.gestures.downModifier(7, com.termux.terminal.KeyHandler.KEYMOD_CTRL)
            current.dismissKeyboard()
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertTrue(current.direct); assertTrue(current.terminalSurface); assertTrue(current.keyboardDismissed)
            assertEquals("Saved draft", current.draft); assertEquals(0, current.gestures.bits); assertFalse(current.keyboardReady)
        }
        compose.onNodeWithTag("terminal-keyboard-dock").assertDoesNotExist()
    }

    @Test fun landscapeUsesFourRowsWithoutLosingFnOrDirectPreference() {
        setup(1)
        val original = compose.activity.requestedOrientation
        try {
            terminalSurface()
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && input.keyboardReady }
            val height = compose.onNodeWithTag("terminal-keyboard-dock").fetchSemanticsNode().boundsInWindow.height / compose.activity.resources.displayMetrics.density
            assertEquals(168f, height, 2f)
            tap("terminal-app-key-PAGE_Symbols"); capture("landscape-symbols")
            tap("terminal-app-key-PAGE_Fn"); capture("landscape-fn")
            compose.onNodeWithTag("terminal-app-key-F12").assertIsDisplayed()
            val fnHeight = compose.onNodeWithTag("terminal-keyboard-dock").fetchSemanticsNode().boundsInWindow.height / compose.activity.resources.displayMetrics.density
            assertEquals(168f, fnHeight, 2f)
            tap("terminal-app-key-PAGE_ABC"); tap("terminal-app-key-CHAR_97"); await("61")
            assertTrue(input.terminalSurface)
        } finally {
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            compose.runOnUiThread { compose.activity.requestedOrientation = original; terminal.finishIfRunning() }
        }
    }

    @Test fun smallWindowAndLargeFontOfferSystemFallbackWithoutInput() {
        val constrained = mutableStateOf(true)
        val controller = TerminalInputController().apply { bind("small-window", false); connection(true); available = { true }; chooseSurface(true); draft = "Keep" }
        compose.setContent {
            val base = androidx.compose.ui.platform.LocalConfiguration.current
            val config = android.content.res.Configuration(base).apply {
                if (constrained.value) { screenWidthDp = 360; screenHeightDp = 360 }
                else { fontScale = 1.6f }
            }
            CompositionLocalProvider(androidx.compose.ui.platform.LocalConfiguration provides config) {
                AgentFleetTheme(darkTheme = true) { TerminalInputControls(controller) }
            }
        }
        compose.onNodeWithTag("terminal-keyboard-fallback").assertIsDisplayed()
        assertFalse(controller.isAppKeyboardAllowed())
        compose.runOnIdle { constrained.value = false }
        compose.onNodeWithTag("terminal-keyboard-fallback").assertIsDisplayed()
        compose.onNodeWithTag("terminal-keyboard-dock").assertDoesNotExist()
        compose.onNodeWithTag("terminal-keyboard-system").performClick()
        assertFalse(controller.terminalSurface); assertEquals("Keep", controller.draft)
    }

}
