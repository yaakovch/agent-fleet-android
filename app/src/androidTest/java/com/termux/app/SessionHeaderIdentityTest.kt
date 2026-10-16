package com.termux.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.app.fleet.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionHeaderIdentityTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalKeyboardTestHostActivity>()

    @Test fun sessionHeaderReclaimsProviderAndTopicRows() {
        val input = TerminalInputController().apply { bind("compact", true); connection(true); available = { true } }
        compose.setContent { AgentFleetTheme(darkTheme = true) {
            CompositionLocalProvider(LocalTerminalInputController provides input) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    AgentFleetTerminalSessionChrome(NativeSessionUiState("project:1", "fixture", "compact", adapter = "codex", sessionTopic = "A long session topic that may be clipped"), {}, {}, { _, _, _, _ -> }, {})
                    ComposerTerminalShortcuts(input)
                }
            }
        } }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300, 5_000)
        val file = java.io.File(compose.activity.externalMediaDirs.first(), "compact-header/header-and-icons.png")
        file.parentFile!!.mkdirs()
        val bitmap = requireNotNull(automation.takeScreenshot())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val header = compose.onNodeWithTag("terminal-session-chrome").fetchSemanticsNode().boundsInRoot
        assertTrue("Header must reclaim space: ${header.height / compose.activity.resources.displayMetrics.density}dp", header.height / compose.activity.resources.displayMetrics.density <= 88.5f)
        compose.onNodeWithTag("native-session-status").assertDoesNotExist()
        compose.onNodeWithTag("composer-terminal-key-CTRL_C").assertIsDisplayed()
    }

    @Test fun compactHeaderKeepsRecoveryDetailAndRetryInActions() {
        var retries = 0
        compose.setContent { AgentFleetTheme(darkTheme = true) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                AgentFleetTerminalSessionChrome(NativeSessionUiState("project:1", "fixture", "compact",
                    attachmentRecovery = SessionAttachmentRecovery("Connection was lost. Retry to reattach.", true)),
                    {}, {}, { _, _, _, _ -> }, {}, onRetryConnection = { retries++ })
            }
        } }
        compose.onNodeWithTag("terminal-session-chrome").assertHeightIsEqualTo(88.dp)
        compose.onNodeWithContentDescription("Connection was lost. Retry to reattach.").assertIsDisplayed()
        compose.onNodeWithTag("compact-session-actions").performClick()
        compose.onNodeWithText("Connection was lost. Retry to reattach.").assertIsDisplayed()
        compose.onNodeWithTag("session-attachment-retry").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun sameProjectThreadsKeepTheirOwnStatusAcrossNativeAndTerminal() {
        var terminal by mutableStateOf(false)
        var state by mutableStateOf(NativeSessionUiState("shared-daemon:1", "owned-host", "first-thread", adapter = "codex",
            connection = "Live", sessionTopic = "First thread", workStatus = SessionWorkStatus("working", java.time.Instant.now().toString())))
        compose.setContent {
            AgentFleetTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                if (terminal) AgentFleetTerminalSessionChrome(state.copy(viewMode = NativeViewMode.ManualTerminal), {}, {}, { _, _, _, _ -> }, {})
                else NativeSessionScreen(state = state, aiComposer = true,
                    onToggleTerminal = {}, onRetry = {}, onLoadOlder = {}, onApproval = { _, _ -> }, onQuestion = { _, _ -> },
                    onShellCommand = {}, onShellKey = {}, onDirectory = {}, onRefreshDirectory = {}, onControlC = {},
                    onCloseSession = {}, onKillSession = {}, onScheduleContinue = {}, onDismissAttention = {},
                    localSuggestionsAvailableOverride = false, applyStatusBarInset = false)
                }
            }
        }
        fun capture(name: String) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.waitForIdle(300, 5_000)
            val bitmap = requireNotNull(automation.takeScreenshot())
            val file = java.io.File(compose.activity.externalMediaDirs.first(), "session-identity/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.runOnIdle { state = state.copy(workStatus = SessionWorkStatus("working", java.time.Instant.now().toString())) }
        capture("shared-daemon-working")
        compose.onNodeWithContentDescription("Codex · Working").assertIsDisplayed()
        compose.onNodeWithTag("session-title").assertTextEquals("shared-daemon:1 · First thread")
        compose.runOnIdle {
            terminal = true
            state = state.copy(sessionLabel = "shared-daemon:2", internalSession = "second-thread", sessionTopic = "Second thread",
                workStatus = SessionWorkStatus("waiting", java.time.Instant.now().toString()))
        }
        compose.onNodeWithContentDescription("Codex · Waiting for input").assertIsDisplayed()
        compose.onNodeWithTag("session-title").assertTextEquals("shared-daemon:2 · Second thread")
        capture("shared-daemon-waiting")
        compose.runOnIdle {
            terminal = false
            state = state.copy(sessionLabel = "shared-daemon:1", internalSession = "first-thread", sessionTopic = "First thread",
                workStatus = SessionWorkStatus("working", java.time.Instant.now().toString()))
        }
        compose.onNodeWithContentDescription("Codex · Working").assertIsDisplayed()
        compose.onNodeWithTag("session-title").assertTextEquals("shared-daemon:1 · First thread")
    }

    @Test fun folderTopicAndWorkStatusStayOrderedAcrossBothViewsAndSizes() {
        var native by mutableStateOf(true)
        var narrow by mutableStateOf(false)
        var actions = 0
        var state by mutableStateOf(NativeSessionUiState("home_through_work:1", "gaming", "owned-fixture", adapter = "codex",
            connection = "Live", sessionTopic = "Keep Whisper medium loaded", workStatus = SessionWorkStatus("waiting", java.time.Instant.now().toString())))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (narrow) 1.6f else 1f)) {
                AgentFleetTheme(darkTheme = true) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Box(Modifier.width(if (narrow) 280.dp else 393.dp)) {
                            if (native) NativeSessionScreen(state = state, aiComposer = true,
                                onToggleTerminal = {}, onRetry = {}, onLoadOlder = {}, onApproval = { _, _ -> },
                                onQuestion = { _, _ -> }, onShellCommand = { actions++ }, onShellKey = {},
                                onDirectory = {}, onRefreshDirectory = {}, onControlC = {}, onCloseSession = {},
                                onKillSession = {}, onScheduleContinue = {}, onDismissAttention = {},
                                localSuggestionsAvailableOverride = false, applyStatusBarInset = false)
                            else AgentFleetTerminalSessionChrome(state.copy(viewMode = NativeViewMode.ManualTerminal), {}, {}, { _, _, _, _ -> }, {}, onControlC = { actions++ })
                        }
                    }
                }
            }
        }
        fun check(name: String) {
            compose.runOnIdle { state = state.copy(workStatus = SessionWorkStatus("waiting", java.time.Instant.now().toString())) }
            compose.waitForIdle()
            val titleNode = compose.onNodeWithTag("session-title").assertIsDisplayed()
                .assertTextEquals("${state.sessionLabel} · ${state.sessionTopic}")
            val title = titleNode.fetchSemanticsNode().boundsInRoot
            val controls = compose.onNodeWithTag("compact-session-control-row").fetchSemanticsNode().boundsInRoot
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            titleNode.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            assertTrue(title.bottom <= controls.top)
            compose.onNodeWithTag("native-session-status").assertDoesNotExist()
            compose.onNodeWithContentDescription("Codex · Waiting for input").assertIsDisplayed()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.waitForIdle(300, 5_000)
            val bitmap = requireNotNull(automation.takeScreenshot())
            val file = java.io.File(compose.activity.externalMediaDirs.first(), "session-identity/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            check("native-portrait")
            compose.runOnIdle { native = false }; check("terminal-portrait")
            compose.runOnIdle { narrow = true }; check("terminal-narrow-large-font")
            compose.runOnIdle { native = true }; check("native-narrow-large-font")
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            check("native-landscape-large-font")
            compose.runOnIdle { native = false }; check("terminal-landscape-large-font")
            assertEquals(0, actions)
        } finally {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}
