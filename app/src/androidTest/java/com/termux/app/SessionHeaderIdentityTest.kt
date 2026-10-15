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
            val project = compose.onNodeWithText(state.sessionLabel).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val topic = compose.onNodeWithTag("session-topic").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val status = compose.onNodeWithTag("native-session-status").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val controls = compose.onNodeWithTag("compact-session-control-row").fetchSemanticsNode().boundsInRoot
            assertTrue(project.bottom <= topic.top)
            assertTrue(topic.bottom <= status.top)
            assertTrue(status.bottom <= controls.top)
            compose.onNodeWithText("Codex · Waiting for input").assertIsDisplayed()
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
