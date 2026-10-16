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
import androidx.compose.ui.res.painterResource
import com.termux.R
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.terminal.KeyHandler
import com.termux.view.TerminalView

/** One shortcut strip; explicitly revealed letter rows replace the Android keyboard. */
@Composable
fun TerminalInputControls(state: TerminalInputController) {
    if (!state.direct) return
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val generation = state.getInputGeneration()
    Column(Modifier.fillMaxWidth().testTag("terminal-input-controls")) {
        Column(Modifier.testTag("terminal-key-panel")) {
            Row(Modifier.fillMaxWidth()) {
                Row(Modifier.weight(1f).testTag("terminal-navigation-row").horizontalScroll(rememberScrollState())) {
                    keyButtons(state, listOf("LEFT", "UP", "DOWN", "RIGHT", "SHIFT_TAB", "SHIFT_LEFT", "ENTER", "BACKSPACE", "DELETE"))
                    TextButton(onClick = { state.character(' '.code, generation) }, enabled = state.isInputAllowed(),
                        modifier = Modifier.testTag("terminal-key-SPACE")) { Text("Space", maxLines = 1) }
                    listOf("Ctrl" to KeyHandler.KEYMOD_CTRL, "Alt" to KeyHandler.KEYMOD_ALT, "Shift" to KeyHandler.KEYMOD_SHIFT).forEach { (label, bit) ->
                        TextButton(onClick = { state.modifier(bit, generation) }, enabled = state.isInputAllowed(),
                            modifier = Modifier.testTag("terminal-modifier-$label")) { Text(if (state.modifiers and bit != 0) "✓ $label" else label, maxLines = 1) }
                    }
                    keyButtons(state, listOf("ESC", "TAB", "HOME", "END", "PAGE_UP", "PAGE_DOWN"))
                    TextButton(onClick = state::toggleProfile, modifier = Modifier.testTag("terminal-input-profile")) {
                        Text(if (state.compatibility) "Compatibility" else "Terminal keys", maxLines = 1)
                    }
                }
                TextButton(onClick = { state.toggleKeyboardLayout() }, enabled = state.canInput(),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.width(48.dp).testTag("terminal-input-keyboard").semantics {
                        contentDescription = if (state.letterKeysVisible || state.keyboardDismissed) "Show Android keyboard" else "Show letter keys"
                        stateDescription = when { state.letterKeysVisible -> "Letter keys"; state.keyboardDismissed -> "Hidden"; else -> "Android keyboard" }
                    }) { Text(if (state.letterKeysVisible || state.keyboardDismissed) "⌨" else "ABC", fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp, maxLines = 1) }
            }
            if (state.letterKeysVisible) Column(Modifier.heightIn(max = if (landscape) 48.dp else 192.dp).verticalScroll(rememberScrollState())) {
                listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEachIndexed { row, letters ->
                    Row(Modifier.fillMaxWidth().testTag("terminal-letter-row-$row").horizontalScroll(rememberScrollState())) {
                        letters.forEach { letter ->
                            TextButton(onClick = { state.character(letter.code, generation) }, enabled = state.isInputAllowed(),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.widthIn(min = 48.dp).testTag("terminal-letter-$letter")) {
                                Text(if (state.modifiers and KeyHandler.KEYMOD_SHIFT != 0) letter.uppercase() else letter.toString(), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Shared by the Native/Terminal header and the focused workspace toolbar. */
@Composable
fun TerminalInputModeButton(state: TerminalInputController, tag: String = "native-direct-input") {
    TextButton(onClick = { if (state.direct) state.chooseComposer() else state.chooseDirect() },
        enabled = state.direct || state.canInput(), modifier = Modifier.testTag(tag)) {
        Text(if (state.direct) "Composer" else "Direct input", fontSize = 11.sp, maxLines = 1)
    }
}

private data class ComposerShortcut(val key: String, val label: String, val icon: Int)
private val composerShortcuts = listOf(
    ComposerShortcut("UP", "Up arrow", R.drawable.ic_composer_up),
    ComposerShortcut("DOWN", "Down arrow", R.drawable.ic_composer_down),
    ComposerShortcut("ENTER", "Enter", R.drawable.ic_composer_enter),
    ComposerShortcut("SHIFT_TAB", "Plan mode (Shift+Tab)", R.drawable.ic_composer_plan),
    ComposerShortcut("SHIFT_LEFT", "Answer question (Shift+Left)", R.drawable.ic_composer_answer),
    ComposerShortcut("CTRL_C", "Interrupt (Ctrl+C)", R.drawable.ic_composer_interrupt),
    ComposerShortcut("SPACE", "Space", R.drawable.ic_composer_space),
    ComposerShortcut("BACKSPACE", "Backspace", R.drawable.ic_composer_backspace),
    ComposerShortcut("DELETE", "Delete", R.drawable.ic_composer_delete)
)

/** Core keys stay reachable without horizontal scrolling, even in a narrow workspace pane. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerTerminalShortcuts(input: TerminalInputController) {
    if (input.direct) return
    val generation = input.getInputGeneration()
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("composer-terminal-shortcuts")) {
        val slots = (maxWidth / 48.dp).toInt().coerceAtLeast(1)
        val visibleKeys = composerShortcuts.take(slots.coerceIn(6, composerShortcuts.size))
        Column {
            visibleKeys.chunked(slots).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { shortcut ->
                        Box(Modifier.weight(1f)) {
                            TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
                                tooltip = { PlainTooltip { Text(shortcut.label) } }, state = rememberTooltipState()) {
                                IconButton(onClick = { input.composerKey(shortcut.key, generation) }, enabled = input.canInput(),
                                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("composer-terminal-key-${shortcut.key}")) {
                                    Icon(painterResource(shortcut.icon), contentDescription = shortcut.label, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                    if (row.size < slots) Spacer(Modifier.weight((slots - row.size).toFloat()))
                }
            }
        }
    }
}

@Composable private fun RowScope.keyButtons(state: TerminalInputController, keys: List<String>) {
    val generation = state.getInputGeneration()
    val labels = mapOf("LEFT" to "←", "RIGHT" to "→", "UP" to "↑", "DOWN" to "↓", "SHIFT_LEFT" to "Shift+←", "SHIFT_TAB" to "Shift+Tab", "PAGE_UP" to "PgUp", "PAGE_DOWN" to "PgDn")
    keys.forEach { key -> TextButton(onClick = { state.key(key, generation) }, enabled = state.isInputAllowed(),
        modifier = Modifier.testTag("terminal-key-$key"), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(labels[key] ?: key.lowercase().replaceFirstChar(Char::uppercase), maxLines = 1) } }
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
