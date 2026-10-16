package com.termux.app

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.app.fleet.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellSessionTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalKeyboardTestHostActivity>()

    @Test fun fullscreenProviderChangesRetainInputAndRequireAnExplicitNativeSelection() {
        compose.runOnUiThread {
            val input = TerminalInputController().apply {
                bind("fixture:session", false); connection(true); available = { true }
                draft = "Keep my draft"; attachments = { listOf("image.png") }
            }
            val presentations = mutableListOf<Boolean>()
            val host = object : NativeSessionHost {
                override val nativeContext: Context = compose.activity
                override val terminalInput = input
                override val nativeInlineComposer = true
                override fun sendAgentFleetComposerText(text: String, appendEnter: Boolean) = false
                override fun sendAgentFleetControlC() = false
                override fun sendAgentFleetKey(key: String) = false
                override fun pickAgentFleetImages() = Unit
                override fun pickAgentFleetCamera() = Unit
                override fun closeAgentFleetSessionTab() = Unit
                override fun setAgentFleetNativeView(nativeAvailable: Boolean, nativeView: Boolean,
                    automaticTerminal: Boolean, aiComposer: Boolean) { presentations += nativeView }
            }
            val controller = NativeSessionController(host, ComposeView(compose.activity))
            val type = NativeSessionController::class.java
            @Suppress("UNCHECKED_CAST")
            val ui = type.getDeclaredField("uiState").apply { isAccessible = true }.get(controller) as MutableState<NativeSessionUiState>
            val session = FleetSession("fixture:session", "fixture", "session", "Shell", "Shell", "fixture", "shell", "windows", "active", false, null, 0)
            val snapshot = FleetSnapshot("initial", "2026-10-16T00:00:00Z",
                listOf(FleetHost("fixture", "Fixture", "healthy", "wsl", null, emptySet())), listOf(session), emptyList(), emptyList())
            val fleet = type.getDeclaredField("fleetSnapshot").apply { isAccessible = true }
            val apply = type.getDeclaredMethod("applyFleetSnapshot", FleetSnapshot::class.java).apply { isAccessible = true }
            ui.value = NativeSessionUiState("Shell", "fixture", "session", adapter = "shell", sourceMode = "shell", viewMode = NativeViewMode.ManualTerminal)
            fleet.set(controller, snapshot)
            try {
                val old = input.getInputGeneration()
                apply.invoke(controller, snapshot.copy(revision = "agent", sessions = listOf(session.copy(tool = "codex"))))
                assertEquals(NativeViewMode.ManualTerminal, ui.value.viewMode)
                assertEquals("codex", ui.value.adapter)
                assertEquals("Keep my draft", input.draft)
                assertEquals(listOf("image.png"), input.attachments())
                assertFalse(input.key("ENTER", old))
                ui.value = ui.value.copy(viewMode = NativeViewMode.Native)
                input.chooseComposer()
                apply.invoke(controller, snapshot.copy(revision = "exit"))
                assertEquals(NativeViewMode.ManualTerminal, ui.value.viewMode)
                assertEquals("shell", ui.value.adapter)
                assertFalse(ui.value.providerStateKnown)
                assertTrue(input.direct)
                assertEquals("Keep my draft", input.draft)
                assertEquals(listOf("image.png"), input.attachments())
                assertFalse(presentations.any { it })
                val directory = java.io.File(compose.activity.cacheDir, "shell-view-${System.nanoTime()}").apply { mkdirs() }
                val binding = SavedSessionBinding(directory)
                binding.install(VerifiedSessionIdentity("fixture", "session", "b".repeat(64), "/fixture", "linux", "shell"), "linux")
                binding.updateView("native")
                input.chooseComposer()
                type.getDeclaredField("enteredAsShell").apply { isAccessible = true }.setBoolean(controller, true)
                val selection = type.getDeclaredField("viewSelectionSerial").apply { isAccessible = true }.getLong(controller)
                type.getDeclaredMethod("installSavedBinding", SavedSessionBinding::class.java, java.lang.Long.TYPE)
                    .apply { isAccessible = true }.invoke(controller, binding, selection)
                assertEquals(NativeViewMode.ManualTerminal, ui.value.viewMode)
                assertEquals("terminal", binding.state.value?.selectedView)
                assertEquals("Keep my draft", input.draft)
            } finally { controller.close() }
        }
    }
}
