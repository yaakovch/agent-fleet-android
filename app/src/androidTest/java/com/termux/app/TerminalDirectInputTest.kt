package com.termux.app

import android.graphics.Bitmap
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.termux.app.fleet.*
import com.termux.shared.terminal.TermuxTerminalSessionClientBase
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Production controls and editor connected to a deterministic real PTY, without a model request. */
@RunWith(AndroidJUnit4::class)
class TerminalDirectInputTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun useProductionKeyboardResize() {
        compose.runOnUiThread {
            com.termux.shared.view.KeyboardUtils.setSoftInputModeAdjustResize(compose.activity)
        }
    }

    private fun assertAboveKeyboardAndTap(tag: String) {
        val decor = compose.activity.window.decorView
        compose.waitUntil(5_000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(decor)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        val visible = android.graphics.Rect()
        val origin = IntArray(2)
        compose.runOnUiThread {
            decor.getWindowVisibleDisplayFrame(visible)
            decor.getLocationOnScreen(origin)
        }
        assertTrue("Control must remain above the visible keyboard: bounds=$bounds frame=$visible",
            bounds.bottom + origin[1] <= visible.bottom)
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action,
                bounds.center.x + origin[0], bounds.center.y + origin[1], 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        compose.waitForIdle()
    }

    private fun capture(view: TerminalView, name: String) {
        compose.waitForIdle()
        // IME animation can resize the AndroidView after Compose becomes idle.
        // Wait for presentation and compositor frames, not just a draw callback.
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300, 5_000)
        compose.waitUntil(5_000) { view.presentationState == TerminalView.PresentationState.READY }
        val before = view.terminalPaintCount
        compose.runOnUiThread { view.onScreenUpdated(); view.invalidate() }
        compose.waitUntil(5_000) { view.terminalPaintCount > before }
        automation.waitForIdle(300, 5_000)
        val bitmap = requireNotNull(automation.takeScreenshot())
        PlatformTestStorageRegistry.getInstance().openOutputFile(name).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val file = java.io.File(compose.activity.externalMediaDirs.first(), "terminal-direct-input/$name")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun captureUi(name: String) {
        compose.waitForIdle()
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300, 5_000)
        val bitmap = requireNotNull(automation.takeScreenshot())
        PlatformTestStorageRegistry.getInstance().openOutputFile(name).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val file = java.io.File(compose.activity.externalMediaDirs.first(), "terminal-direct-input/$name")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun composerControlsFitBelowWritingField() {
        val input = TerminalInputController().apply { bind("compact-composer", true); connection(true); available = { true }; draft = "Unsent message" }
        compose.setContent { AgentFleetTheme(darkTheme = true) {
            CompositionLocalProvider(LocalTerminalInputController provides input) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Spacer(Modifier.weight(1f))
                    AgentFleetComposerContent("compact-composer", AgentFleetComposerNativeState(), emptyList(), false, null,
                        onShowPendingQuestion = {}, onAttach = {}, onRemoveAttachment = {}, onComposerText = { _, _ -> false })
                }
            }
        } }
        compose.onNodeWithTag("agent-fleet-message-input").assertTextContains("Unsent message")
        compose.onNodeWithTag("agent-fleet-message-input").performClick()
        captureUi("compact-composer-controls.png")
        val field = compose.onNodeWithTag("agent-fleet-message-input").fetchSemanticsNode().boundsInRoot
        val direct = compose.onNodeWithTag("terminal-input-direct").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("agent-fleet-composer-action-stack").fetchSemanticsNode().boundsInRoot
        val tolerance = compose.activity.resources.displayMetrics.density * 8
        assertTrue("Input modes must sit below the writing field, not above it: field=$field direct=$direct", direct.top >= field.bottom)
        assertTrue("Input modes must reuse the space beside the action stack: actions=$actions direct=$direct", direct.bottom <= actions.bottom + tolerance)
        assertAboveKeyboardAndTap("terminal-input-direct")
        assertTrue("A real screen tap must activate Direct with the keyboard open", input.direct)
        compose.onNodeWithTag("terminal-input-composer").performClick()
        compose.onNodeWithTag("agent-fleet-message-input").assertTextContains("Unsent message")
    }

    private fun await(session: TerminalSession, text: String) {
        compose.waitUntil(15_000) { var found = false; compose.runOnUiThread { found = session.emulator?.screen?.transcriptText?.contains(text) == true }; found }
    }
    @Test fun actionOpensRealQuestionAndAnswerIsSubmittedExactlyOnce() {
        check(android.os.Build.VERSION.SDK_INT == 36 && "x86_64" in android.os.Build.SUPPORTED_ABIS)
        val input = TerminalInputController().apply { bind("question", true); connection(true); available = { true } }
        lateinit var terminal: TerminalSession
        lateinit var view: TerminalView
        var count = 0
        compose.runOnUiThread {
            terminal = TerminalSession("/system/bin/sh", "/", arrayOf("sh", "-c",
                "stty -echo -icanon min 1 time 0; printf '\u001b[?1000hQueued follow-up: shift+left to answer\\r\\n'; key=\$(dd bs=1 count=6 2>/dev/null | od -An -tx1); printf 'KEY:%s\\r\\nQuestion: Choose a direction\\r\\n' \"\$key\"; IFS= read -r answer; printf 'ANSWER:%s\\r\\n' \"\$answer\"; sleep 120"),
                arrayOf("PATH=/system/bin", "TERM=xterm-256color"), 2000,
                object : TermuxTerminalSessionClientBase() { override fun onTextChanged(session: TerminalSession) { runCatching { view.onScreenUpdated() } } })
            view = TerminalView(compose.activity, null).apply {
                setTerminalViewClient(WorkspaceTerminalViewClient()); setInputBridge(input)
                setTextSize(26); isFocusableInTouchMode = true; attachSession(terminal)
            }
            input.send = { name, bits -> TerminalKeys.encode(name, bits, terminal.emulator)?.let { terminal.write(it); count++; true } ?: false }
            input.onKeyboard = { TerminalKeyboard.show(view) }
        }
        try {
            compose.setContent {
                AgentFleetTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalTerminalInputController provides input) {
                        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                            // Keep the PTY attached during the Native reading view.
                            AndroidView(factory = { view }, modifier = Modifier.weight(1f))
                            if (!input.direct) AgentFleetTerminalSessionChrome(
                                state = NativeSessionUiState("Question fixture", "test", "q"),
                                onShowNative = {}, onRefreshModel = {}, onSetModel = { _, _, _, _ -> }, onCancelModel = {}, aiComposer = true
                            )
                            TerminalInputControls(input)
                        }
                    }
                }
            }
            await(terminal, "Queued follow-up")
            capture(view, "direct-input-question-before.png")
            compose.onNodeWithTag("compact-session-actions").performClick()
            compose.onNodeWithTag("terminal-answer-question").performClick()
            compose.onNodeWithTag("terminal-key-ENTER").assertIsDisplayed()
            await(terminal, "Question: Choose a direction")
            await(terminal, "1b 5b 31 3b 32 44")
            assertEquals(1, count)
            capture(view, "direct-input-question-open.png")
            compose.runOnUiThread {
                val info = EditorInfo(); val connection = view.onCreateInputConnection(info)
                assertTrue("Direct must focus the terminal even with mouse reporting", view.hasFocus())
                assertTrue(terminal.emulator.isMouseTrackingActive)
                assertEquals(InputType.TYPE_NULL, info.inputType)
                connection.commitText("left", 1)
            }
            compose.onNodeWithTag("terminal-key-ENTER").performClick()
            await(terminal, "ANSWER:left")
            compose.runOnUiThread { assertEquals(1, Regex("ANSWER:left").findAll(terminal.emulator.screen.transcriptText).count()) }
            assertEquals(2, count)
            capture(view, "direct-input-answer-submitted.png")
        } finally { compose.runOnUiThread { terminal.finishIfRunning() } }
    }

    @Test fun workspaceShellOpensDirectAndWritesToItsRealTerminal() {
        val session = FleetSession("fixture:shell", "fixture", "shell", "Shell", "Shell", "fixture", "shell", "linux", "active", false, null, 0)
        val snapshot = FleetSnapshot("shell-default", "2026-10-12T17:00:00Z",
            listOf(FleetHost("fixture", "Fixture", "healthy", "wsl", null, emptySet())), listOf(session), emptyList(), emptyList())
        val initial = emptyWorkspaceLayout()
        val state = mutableStateOf(AndroidWorkspaceState(WorkspaceReducer.assign(initial, initial.focusedPaneId, session.id)))
        val broker = WorkspaceTerminalBroker(compose.activity)
        val transitions = mutableListOf<String>()
        val registry = NativeSessionRegistry<WorkspaceRetainedNativeSession>(compose.activity)
        lateinit var terminal: TerminalSession
        compose.runOnUiThread {
            terminal = TerminalSession("/system/bin/sh", "/", arrayOf("sh", "-c", "stty -echo -icanon -icrnl min 1 time 0; printf 'READY\\r\\n'; dd bs=1 count=1 2>/dev/null | od -An -tx1; sleep 120"),
                arrayOf("PATH=/system/bin", "TERM=xterm-256color"), 2000, TermuxTerminalSessionClientBase())
            @Suppress("UNCHECKED_CAST")
            val binding = broker.state(session.id) as androidx.compose.runtime.MutableState<WorkspaceTerminalBinding>
            binding.value = WorkspaceTerminalBinding(session.id, terminal = terminal, status = "live", message = "Live")
        }
        try {
            compose.setContent { AgentFleetTheme(darkTheme = true) {
                CompositionLocalProvider(LocalWorkspaceNativeSessionRegistry provides registry) {
                    DesktopWorkspaceScreen(snapshot, snapshot.sessions, state = state.value, broker = broker,
                        onStateChange = { transitions.add(workspacePanes(it.layout.root).single().viewMode.toString()); state.value = it }, onMoreSession = { _, _ -> }, onRefresh = {})
                }
            } }
            compose.waitForIdle()
            assertEquals("Actual shell layout; transitions=$transitions", WorkspaceViewMode.Terminal, workspacePanes(state.value.layout.root).single().viewMode)
            compose.onNodeWithTag("workspace-mode-terminal").assertIsSelected()
            compose.onNodeWithTag("terminal-input-direct").assertTextContains("✓ Direct input")
            compose.onNodeWithTag("terminal-input-composer").assertDoesNotExist()
            await(terminal, "READY")
            compose.onNodeWithTag("terminal-key-ENTER").performScrollTo().assertIsEnabled().performClick()
            await(terminal, "0d")
        } finally { compose.runOnUiThread { registry.destroy(); broker.close(); terminal.finishIfRunning() } }
    }

    @Test fun nativeSavedDraftRemainsAuthoritativeForTheSharedInput() {
        val directory = java.io.File(compose.activity.cacheDir, "native-direct-draft-${System.nanoTime()}").apply { mkdirs() }
        val binding = SavedSessionBinding(directory)
        binding.install(VerifiedSessionIdentity("fixture", "draft", "a".repeat(64), "/fixture", "linux", "codex"), "linux")
        binding.updateMessage("Saved native draft")
        val input = TerminalInputController().apply { bind("draft", true); connection(true); available = { true } }
        try {
            compose.setContent { AgentFleetTheme(darkTheme = true) {
                CompositionLocalProvider(LocalTerminalInputController provides input, LocalSavedSessionBinding provides binding) {
                    // Production embeds this screen below TermuxActivity's fitsSystemWindows
                    // root, which owns status-bar and keyboard sizing. Mirror that host here.
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    NativeSessionScreen(
                        state = NativeSessionUiState("Draft", "fixture", "draft", adapter = "codex", connection = "Live",
                            providerStateKnown = true, providerState = ProviderState("verified", "PROVIDER_STATE_VERIFIED", "fixture", 1,
                                ProviderComponent("codex-parser", "3.0.0"), ProviderComponent("codex-actions", "2.0.0"), true, "none")),
                        aiComposer = true, inlineComposer = true, applyStatusBarInset = false, localSuggestionsAvailableOverride = false,
                        onToggleTerminal = {}, onRetry = {}, onLoadOlder = {}, onApproval = { _, _ -> }, onQuestion = { _, _ -> },
                        onShellCommand = {}, onShellKey = {}, onDirectory = {}, onRefreshDirectory = {}, onControlC = {},
                        onCloseSession = {}, onKillSession = {}, onScheduleContinue = {}, onDismissAttention = {})
                    }
                }
            } }
            compose.onNodeWithTag("native-message-input").assertTextContains("Saved native draft")
            assertEquals("Saved native draft", input.draft)
            compose.onNodeWithTag("native-message-input").performClick()
            captureUi("compact-native-composer-controls.png")
            val field = compose.onNodeWithTag("native-message-input").fetchSemanticsNode().boundsInRoot
            val direct = compose.onNodeWithTag("terminal-input-direct").fetchSemanticsNode().boundsInRoot
            val answer = compose.onNodeWithTag("terminal-answer-question").fetchSemanticsNode().boundsInRoot
            assertTrue("Native mode controls must sit below the writing field", direct.top >= field.bottom)
            assertEquals("The question shortcut must share the footer row", direct.center.y, answer.center.y, 1f)
            assertAboveKeyboardAndTap("terminal-input-direct")
            assertTrue("Native Direct must accept a real tap above the keyboard", input.direct)
            compose.onNodeWithTag("terminal-input-composer").performClick()
            compose.onNodeWithTag("native-message-input").assertTextContains("Saved native draft")
        } finally { SavedSessionStates.writer.submit {}.get(); directory.deleteRecursively() }
    }

    @Test fun composerDraftAttachmentsAndProfilesSurviveModeSwitch() {
        val input = TerminalInputController().apply { bind("draft", true); connection(true); available = { true } }
        val attachments = mutableStateOf(listOf("/synthetic/image.jpg"))
        var sends = 0
        compose.setContent {
            AgentFleetTheme(darkTheme = true) {
                CompositionLocalProvider(LocalTerminalInputController provides input) {
                    AgentFleetComposerContent("draft", AgentFleetComposerNativeState(), attachments.value, false, null,
                        onShowPendingQuestion = {}, onAttach = {}, onRemoveAttachment = {}, onComposerText = { _, _ -> sends++; true })
                }
            }
        }
        compose.onNodeWithTag("agent-fleet-message-input").performTextInput("Unsent draft שלום 🙂")
        compose.onNodeWithTag("terminal-input-direct").performClick()
        compose.onNodeWithTag("agent-fleet-message-input").assertDoesNotExist()
        compose.onNodeWithTag("terminal-input-expand").performClick()
        compose.onNodeWithTag("terminal-input-profile").performScrollTo().performClick()
        compose.onNodeWithText("Compatibility").assertIsDisplayed()
        compose.onNodeWithTag("terminal-key-F12").assertExists()
        compose.onNodeWithTag("terminal-input-composer").performClick()
        compose.onNodeWithTag("agent-fleet-message-input").assertTextContains("Unsent draft שלום 🙂")
        assertEquals(listOf("/synthetic/image.jpg"), attachments.value)
        assertEquals(0, sends)
    }

    @Test fun savedWorkspaceInputAndDraftRestoreAcrossRecreation() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        lateinit var current: TerminalInputController
        restoration.setContent {
            val input = rememberTerminalInputController("saved-pane", true)
            current = input
            androidx.compose.runtime.SideEffect { input.available = { true }; input.connection(true) }
            AgentFleetTheme(darkTheme = true) {
                CompositionLocalProvider(LocalTerminalInputController provides input) {
                    AgentFleetComposerContent("saved-pane", AgentFleetComposerNativeState(), emptyList(), false, null,
                        onShowPendingQuestion = {}, onAttach = {}, onRemoveAttachment = {}, onComposerText = { _, _ -> false })
                }
            }
        }
        compose.onNodeWithTag("agent-fleet-message-input").performTextInput("Saved unsent draft")
        compose.onNodeWithTag("terminal-input-direct").performClick()
        compose.onNodeWithTag("terminal-input-expand").performClick()
        compose.onNodeWithTag("terminal-input-profile").performScrollTo().performClick()
        compose.onNodeWithTag("terminal-modifier-Shift").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        assertTrue(current.direct); assertTrue(current.compatibility); assertEquals(0, current.modifiers)
        compose.onNodeWithTag("terminal-input-composer").performClick()
        compose.onNodeWithTag("agent-fleet-message-input").assertTextContains("Saved unsent draft")
    }

    @Test fun expandedLandscapeKeysStayBoundedAtLargeFont() {
        val input = TerminalInputController().apply { bind("layout", false); connection(true); available = { true } }
        compose.setContent {
            val config = android.content.res.Configuration(androidx.compose.ui.platform.LocalConfiguration.current).apply { orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalConfiguration provides config,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.5f)) {
                AgentFleetTheme(darkTheme = true) { Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) { TerminalInputControls(input) } }
            }
        }
        compose.onNodeWithTag("terminal-key-SHIFT_LEFT").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("terminal-input-expand").performClick()
        assertTrue(input.expanded)
        compose.onNodeWithTag("terminal-function-row-7").performScrollTo()
        compose.onNodeWithTag("terminal-key-F12").performScrollTo()
        try { compose.onNodeWithTag("terminal-key-F12").assertIsDisplayed() } catch (error: AssertionError) {
            val file = java.io.File(compose.activity.externalMediaDirs.first(), "terminal-direct-input/large-font-failure.png")
            file.parentFile?.mkdirs()
            val screenshot = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }; screenshot.recycle()
            java.io.File(file.parentFile, "large-font-semantics.txt").writeText(compose.onRoot().printToString())
            throw error
        }
        compose.onNodeWithTag("terminal-input-profile").performScrollTo().assertIsDisplayed()
        val height = compose.onNodeWithTag("terminal-input-controls").fetchSemanticsNode().boundsInRoot.height / compose.activity.resources.displayMetrics.density
        assertTrue("Expanded keys height ${height}dp must leave room for the terminal", height <= 180)
    }

    @Test fun staleEditorAndOfflineKeysAreDiscardedAndAltControlUseCanonicalEncoder() {
        val input = TerminalInputController().apply { bind("keys", true); connection(true); available = { true }; chooseDirect() }
        lateinit var terminal: TerminalSession
        lateinit var view: TerminalView
        compose.runOnUiThread {
            terminal = TerminalSession("/system/bin/sh", "/", arrayOf("sh", "-c", "stty -echo -icanon min 1 time 0; printf 'READY\\r\\n'; dd bs=1 count=7 2>/dev/null | od -An -tx1; sleep 120"),
                arrayOf("PATH=/system/bin", "TERM=xterm-256color"), 2000,
                object : TermuxTerminalSessionClientBase() { override fun onTextChanged(session: TerminalSession) { runCatching { view.onScreenUpdated() } } })
            view = TerminalView(compose.activity, null).apply { setTerminalViewClient(WorkspaceTerminalViewClient()); setInputBridge(input); isFocusableInTouchMode = true; setTextSize(26); attachSession(terminal) }
            compose.activity.setContentView(view)
        }
        try {
            await(terminal, "READY")
            compose.runOnUiThread {
                val old = view.onCreateInputConnection(EditorInfo())
                input.chooseComposer(); old.commitText("BAD", 1)
                input.chooseDirect(); input.toggleProfile()
                old.deleteSurroundingText(1, 0)
                old.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                val info = EditorInfo(); val active = view.onCreateInputConnection(info)
                assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType and InputType.TYPE_MASK_CLASS)
                input.connection(false); active.commitText("BAD", 1); view.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                input.connection(true)
                val fresh = view.onCreateInputConnection(EditorInfo())
                input.modifier(com.termux.terminal.KeyHandler.KEYMOD_CTRL); fresh.commitText("a", 1)
                input.modifier(com.termux.terminal.KeyHandler.KEYMOD_ALT); fresh.commitText("x", 1)
                fresh.commitText("🙂", 1)
            }
            await(terminal, "01 1b 78 f0 9f 99 82")
            capture(view, "direct-input-unicode-modifiers.png")
        } finally { compose.runOnUiThread { terminal.finishIfRunning() } }
    }
}
