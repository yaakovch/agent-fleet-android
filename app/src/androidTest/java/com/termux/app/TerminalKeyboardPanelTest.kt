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
            tap("terminal-keyboard-entry-android")
            assertEquals(TerminalKeyboardSettings.Entry.Android, TerminalKeyboardSettings.load(compose.activity))
            compose.runOnUiThread { settingsVisible.value = false }
        }
        if (!showSystem) {
            compose.onNodeWithTag("agent-fleet-message-input").performClick()
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            tap("terminal-input-direct")
        } else compose.onNodeWithTag("terminal-input-direct").performClick()

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
    @Test fun androidKeyboardToggleKeepsExpandedShortcutsAndPendingText() {
        setup(1, showSystem = false)
        try {
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
            compose.onNodeWithTag("terminal-key-panel").assertExists()
            compose.onNodeWithTag("terminal-key-F12").assertExists()
            compose.onNodeWithTag("terminal-input-expand").assertDoesNotExist()
            compose.onNodeWithTag("terminal-input-surface").assertDoesNotExist()
            capture("direct-expanded-ime-hidden")
            tap("terminal-input-keyboard")
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            val old = arrayOfNulls<InputConnection>(1)
            compose.runOnUiThread { old[0] = view.onCreateInputConnection(EditorInfo()); old[0]!!.setComposingText("unfinished", 1) }
            capture("direct-expanded-ime-visible")
            tap("terminal-input-keyboard")
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
            compose.onNodeWithTag("terminal-key-panel").assertExists()
            compose.runOnUiThread {
                assertTrue(view.hasPendingImeComposition()); assertNull(view.onCreateInputConnection(EditorInfo()))
                old[0]!!.commitText("BAD", 1); old[0]!!.deleteSurroundingText(1, 0)
                old[0]!!.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_X))
                old[0]!!.finishComposingText(); assertTrue(view.hasPendingImeComposition())
                input.connection(false); input.connection(true); input.onKeyboard()
            }
            assertTrue(input.keyboardDismissed); assertEquals("Keep this draft", input.draft); assertTrue(keys.isEmpty())
            tap("terminal-input-keyboard")
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            compose.runOnUiThread { assertTrue(view.hasPendingImeComposition()); view.discardImeComposition(); view.onCreateInputConnection(EditorInfo())!!.commitText("x", 1) }
            await("78"); await("CAPTURED")
        } finally { compose.runOnUiThread { terminal.finishIfRunning() } }
    }
    @Test fun settingsMigrateOldEntriesAndControlOnlyAndroidImeOnNextEntry() {
        compose.runOnUiThread {
            val prefs = compose.activity.getSharedPreferences("agent-fleet-terminal-keyboard", 0)
            for (old in listOf("hidden", "terminal")) {
                prefs.edit().putString("direct-entry", old).commit()
                assertEquals(TerminalKeyboardSettings.Entry.Hidden, TerminalKeyboardSettings.load(compose.activity))
            }
        }
        setup(1, showSystem = false, chooseSettings = true)
        try {
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            assertFalse(input.terminalSurface); compose.onNodeWithTag("terminal-key-panel").assertExists()
            tap("terminal-input-keyboard")
            compose.runOnUiThread { settingsVisible.value = true }
            compose.onNodeWithTag("terminal-keyboard-entry-android").assertIsSelected()
            capture("android-entry-settings")
            tap("terminal-keyboard-entry-hidden")
            compose.runOnUiThread { settingsVisible.value = false; input.chooseComposer() }
            tap("terminal-input-direct")
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
            assertTrue(input.keyboardDismissed); assertTrue(input.expanded); assertEquals("Keep this draft", input.draft); assertTrue(keys.isEmpty())
        } finally { compose.runOnUiThread { terminal.finishIfRunning() } }
    }
    @Test fun legacySavedSurfaceRestoresAsExpandedDirectWithAndroidImeHidden() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        lateinit var current: TerminalInputController
        restoration.setContent {
            val controller = rememberTerminalInputController("keyboard-saved-pane", true)
            current = controller
            SideEffect { controller.available = { true }; controller.connection(true) }
            AgentFleetTheme(darkTheme = true) { TerminalInputControls(controller) }
        }
        compose.runOnIdle {
            current.draft = "Saved draft"; current.restore(true, true, false)
            current.chooseSurface(true); current.modifier(com.termux.terminal.KeyHandler.KEYMOD_CTRL)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertTrue(current.direct); assertFalse(current.terminalSurface); assertTrue(current.keyboardDismissed)
            assertTrue(current.expanded); assertTrue(current.compatibility); assertEquals("Saved draft", current.draft); assertEquals(0, current.modifiers)
        }
        compose.onNodeWithTag("terminal-key-panel").assertExists()
        compose.onNodeWithTag("terminal-key-F12").assertExists()
        compose.onNodeWithTag("terminal-keyboard-dock").assertDoesNotExist()
    }
    @Test fun landscapeKeepsExpandedShortcutsWithAndroidImeHidden() {
        setup(1, showSystem = false)
        val original = compose.activity.requestedOrientation
        try {
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithTag("terminal-function-row-7").performScrollTo()
            compose.onNodeWithTag("terminal-key-F12").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("terminal-navigation-row").performScrollTo()
            compose.onNodeWithTag("terminal-key-ENTER").performScrollTo().assertIsDisplayed()
            assertFalse(ViewCompat.getRootWindowInsets(view)!!.isVisible(WindowInsetsCompat.Type.ime()))
            capture("direct-expanded-landscape")
        } finally {
            compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            compose.runOnUiThread { compose.activity.requestedOrientation = original; terminal.finishIfRunning() }
        }
    }
}
