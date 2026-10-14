package com.termux.app.fleet

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.inputmethod.InputMethodManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.termux.terminal.KeyHandler
import com.termux.view.TerminalView

@Composable
fun TerminalInputControls(state: TerminalInputController, composerAvailable: Boolean = true) {
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    Column(Modifier.fillMaxWidth().testTag("terminal-input-controls")) {
        TerminalInputModeControls(state, composerAvailable)
        if (state.direct) Column(Modifier.testTag("terminal-key-panel").heightIn(max = if (landscape) 96.dp else 240.dp).verticalScroll(rememberScrollState())) {
            val modifiers: @Composable () -> Unit = {
                listOf("Ctrl" to KeyHandler.KEYMOD_CTRL, "Alt" to KeyHandler.KEYMOD_ALT, "Shift" to KeyHandler.KEYMOD_SHIFT).forEach { (label, bit) ->
                    TextButton(onClick = { state.modifier(bit) }, enabled = state.isInputAllowed(),
                        modifier = Modifier.testTag("terminal-modifier-$label")) { Text(if (state.modifiers and bit != 0) "✓ $label" else label) }
                }
            }
            if (landscape) Row(Modifier.testTag("terminal-navigation-row").fillMaxWidth().horizontalScroll(rememberScrollState())) {
                modifiers(); keyButtons(state, listOf("ESC", "TAB", "LEFT", "UP", "DOWN", "RIGHT", "SHIFT_LEFT", "ENTER"))
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { modifiers(); keyButtons(state, listOf("ESC", "TAB")) }
                Row(Modifier.testTag("terminal-navigation-row").fillMaxWidth().horizontalScroll(rememberScrollState())) { keyButtons(state, listOf("LEFT", "UP", "DOWN", "RIGHT", "SHIFT_LEFT", "ENTER")) }
            }
            run {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    keyButtons(state, listOf("SHIFT_TAB", "HOME", "END", "PAGE_UP", "PAGE_DOWN", "DELETE", "BACKSPACE"))
                }
                (1..12).chunked(6).forEach { keys -> Row(Modifier.testTag("terminal-function-row-${keys.first()}").fillMaxWidth().horizontalScroll(rememberScrollState())) { keyButtons(state, keys.map { "F$it" }) } }
                TextButton(onClick = state::toggleProfile, modifier = Modifier.testTag("terminal-input-profile")) { Text(if (state.compatibility) "Compatibility" else "Terminal keys") }
            }
        }
    }
}

/** Mode selection can share the composer's existing footer instead of adding a row above it. */
@Composable
fun TerminalInputModeControls(
    state: TerminalInputController,
    composerAvailable: Boolean = true,
    trailingActions: @Composable RowScope.() -> Unit = {}
) {
    Row(Modifier.fillMaxWidth().testTag("terminal-input-mode-row")) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            if (composerAvailable) TextButton(onClick = state::chooseComposer,
                modifier = Modifier.testTag("terminal-input-composer")) { Text(if (state.direct) "Composer" else "✓ Composer") }
            TextButton(onClick = { state.chooseDirect() }, enabled = state.canInput(),
                modifier = Modifier.testTag("terminal-input-direct")) { Text(if (state.direct) "✓ Direct" else "Direct input") }
            trailingActions()
        }
        if (state.direct) TextButton(onClick = { state.toggleKeyboard() }, enabled = state.canInput(),
            contentPadding = PaddingValues(horizontal = 6.dp),
            modifier = Modifier.width(48.dp).testTag("terminal-input-keyboard").semantics {
                contentDescription = if (state.keyboardDismissed) "Show Android keyboard" else "Hide Android keyboard"
                stateDescription = if (state.keyboardDismissed) "Hidden" else "Open"
            }) { Text(if (state.keyboardDismissed) "⌨" else "⌨⌄") }
    }
}

/** Named terminal keys do not submit or focus the Composer's draft. */
@Composable
fun ComposerTerminalShortcuts(input: TerminalInputController) {
    if (input.direct) return
    val generation = input.getInputGeneration()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("composer-terminal-shortcuts")) {
        listOf("UP" to "↑", "DOWN" to "↓", "ENTER" to "Enter", "SHIFT_TAB" to "Shift+Tab", "SHIFT_LEFT" to "Shift+Left").forEach { (key, label) ->
            TextButton(onClick = { input.composerKey(key, generation) }, enabled = input.canInput(),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.testTag("composer-terminal-key-$key")) { Text(label, maxLines = 1) }
        }
    }
}

@Composable private fun RowScope.keyButtons(state: TerminalInputController, keys: List<String>) {
    val labels = mapOf("LEFT" to "←", "RIGHT" to "→", "UP" to "↑", "DOWN" to "↓", "SHIFT_LEFT" to "Shift+←", "SHIFT_TAB" to "Shift+Tab", "PAGE_UP" to "PgUp", "PAGE_DOWN" to "PgDn")
    keys.forEach { key -> TextButton(onClick = { state.key(key) }, enabled = state.isInputAllowed(),
        modifier = Modifier.testTag("terminal-key-$key"), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(labels[key] ?: key.lowercase().replaceFirstChar(Char::uppercase)) } }
}

object TerminalKeyboard {
    @JvmStatic fun show(view: TerminalView) {
        if (!view.isAttachedToWindow || !view.isEnabled) return
        val input = view.inputBridge as? TerminalInputController
        if (input != null && !input.isInputAllowed()) return
        view.requestFocus()
        var context: Context? = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        val activity = context as? Activity ?: return
        (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(view)
        val controller = WindowCompat.getInsetsController(activity.window, view)
        if (input != null && !input.isSystemImeAllowed()) controller.hide(WindowInsetsCompat.Type.ime())
        else controller.show(WindowInsetsCompat.Type.ime())
    }
}
