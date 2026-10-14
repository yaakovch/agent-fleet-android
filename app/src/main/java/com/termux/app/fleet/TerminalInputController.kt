package com.termux.app.fleet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalInputBridge

/** Session-scoped input choices; no pending remote input is stored here. */
class TerminalInputController : TerminalInputBridge {
    var direct by mutableStateOf(false)
        private set
    var compatibility by mutableStateOf(false)
        private set
    val expanded = true
    var modifiers by mutableStateOf(0)
        private set
    var terminalSurface by mutableStateOf(false)
        private set
    var keyboardDismissed by mutableStateOf(true)
        private set
    var keyboardReady by mutableStateOf(false)
        private set
    private var selectingSurface by mutableStateOf(false)
    var freezeComposition: () -> Unit = {}
    var surfaceBlocked by mutableStateOf(false)
    var literal: (Int, Int, Long) -> Boolean = { _, _, _ -> false }
    var pendingComposition: () -> Boolean = { false }
    var discardComposition: () -> Unit = {}
    val gestures = TerminalKeyboardGestures()
    private var generation by mutableStateOf(0L)
    private var target = ""
    private var terminalOwner: Any? = null
    private var terminalAttachment: Any? = null
    private var connected by mutableStateOf(false)
    private var focused by mutableStateOf(true)
    var available: () -> Boolean = { false }
    var draft by mutableStateOf("")
    var attachments: () -> List<String> = { emptyList() }
    var uploading: () -> Boolean = { false }
    var onAttach: () -> Unit = {}
    var onCamera: () -> Unit = {}
    var removeAttachment: (String) -> Unit = {}
    var composerSend: (String, Boolean) -> Boolean = { _, _ -> false }
    var previousNative = true
    var readingNative: () -> Boolean = { true }
    var onDirect: () -> Unit = {}
    var onComposer: () -> Unit = {}
    var onKeyboard: () -> Unit = {}
    var systemImeOnEntry: () -> Boolean = { false }
    var beforeKey: () -> Unit = {}
    var send: (String, Int) -> Boolean = { _, _ -> false }

    fun bind(id: String, ai: Boolean) {
        if (id == target) return
        discardComposition()
        surfaceBlocked = false
        selectingSurface = false
        target = id
        draft = ""
        previousNative = true
        connected = false
        direct = !ai
        compatibility = false
        terminalSurface = false
        keyboardDismissed = !(direct && systemImeOnEntry())
        modifiers = 0
        invalidateInput()
    }

    fun resetEntry(id: String, ai: Boolean) { target = ""; bind(id, ai) }

    override fun onTerminalTargetChanged(view: Any?, session: Any?) {
        if (view === terminalOwner && session === terminalAttachment) return
        terminalOwner = view
        terminalAttachment = session
        invalidateInput()
    }

    fun focus(value: Boolean) {
        if (value == focused) return
        focused = value
        modifiers = 0
        invalidateInput()
    }

    fun connection(live: Boolean) {
        if (live == connected) return
        connected = live
        modifiers = 0
        invalidateInput()
    }

    fun restore(mode: Boolean, profile: Boolean, keysExpanded: Boolean, previous: Boolean = true) {
        previousNative = previous
        direct = mode
        compatibility = profile
        // Legacy collapsed-panel choices are intentionally ignored.
        modifiers = 0
        invalidateInput()
    }

    fun chooseDirect(): Boolean = enterDirect(useEntryPreference = true)

    private fun enterDirect(useEntryPreference: Boolean): Boolean {
        if (!canInput()) return false
        if (direct) return true
        modifiers = 0
        previousNative = readingNative()
        freezeComposition()
        direct = true
        terminalSurface = false
        keyboardDismissed = !(useEntryPreference && systemImeOnEntry())
        invalidateInput()
        onDirect()
        onKeyboard()
        return true
    }

    fun readingViewChanged(native: Boolean) {
        if (!native || !direct) return
        previousNative = true
        modifiers = 0
        direct = false
        invalidateInput()
    }

    fun chooseComposer() {
        modifiers = 0
        direct = false
        surfaceBlocked = false
        selectingSurface = false
        invalidateInput()
        onComposer()
    }

    fun toggleProfile() {
        compatibility = !compatibility
        modifiers = 0
        invalidateInput()
        if (canInput()) onKeyboard()
    }

    private fun invalidateInput() {
        generation++
        modifiers = 0
        keyboardReady = false
        gestures.cancel()
    }

    fun restoreSurface(terminal: Boolean, dismissed: Boolean = false) {
        // Saved app-keyboard surfaces migrate to Android keyboard hidden.
        terminalSurface = false
        keyboardDismissed = terminal || dismissed
        invalidateInput()
    }

    fun keyboardGeometryChanged() { invalidateInput() }
    fun changeKeyboardPage(page: String) {
        invalidateInput()
        gestures.changePage(page)
    }

    fun beginSurfaceSelection() {
        if (!canInput()) return
        freezeComposition()
        selectingSurface = true
        invalidateInput()
    }

    fun cancelSurfaceSelection() {
        if (surfaceBlocked) { terminalSurface = false; keyboardDismissed = false }
        surfaceBlocked = false
        selectingSurface = false
        invalidateInput()
        if (direct && canInput()) onKeyboard()
    }

    fun chooseSurface(terminal: Boolean, discard: Boolean = false): Boolean {
        if (!canInput()) return false
        if (terminal && pendingComposition()) {
            if (!selectingSurface) { freezeComposition(); selectingSurface = true; invalidateInput() }
            if (!discard) { surfaceBlocked = true; return false }
            discardComposition()
        }
        surfaceBlocked = false
        selectingSurface = false
        if (!direct && !enterDirect(useEntryPreference = false)) return false
        terminalSurface = terminal
        keyboardDismissed = false
        invalidateInput()
        onKeyboard()
        return true
    }

    override fun dismissAppKeyboard(): Boolean = dismissKeyboard()

    fun showKeyboard(): Boolean {
        if (!canInput() || !chooseDirect() || !canInput() || surfaceBlocked) return false
        keyboardDismissed = false
        invalidateInput()
        onKeyboard()
        return true
    }

    fun toggleKeyboard(): Boolean = if (!direct || keyboardDismissed) showKeyboard() else dismissKeyboard()

    fun dismissKeyboard(): Boolean {
        if (!direct || keyboardDismissed) return false
        // Preserve the editor's preedit before revoking its callbacks and restarting input.
        freezeComposition()
        keyboardDismissed = true
        invalidateInput()
        onKeyboard()
        return true
    }

    fun updateKeyboardReady(value: Boolean) { keyboardReady = value && wantsTerminalKeyboard() && canInput() }
    fun wantsTerminalKeyboard(): Boolean = direct && terminalSurface && !keyboardDismissed
    override fun isSystemImeAllowed(): Boolean = isInputAllowed() && !terminalSurface && !keyboardDismissed && !selectingSurface
    override fun isAppKeyboardAllowed(): Boolean = keyboardReady && !selectingSurface && wantsTerminalKeyboard() && isInputAllowed()

    /** Captured generation and modifiers belong to this gesture, never a later target. */
    fun appKey(key: TerminalKeyboardKey, bits: Int, captured: Long): Boolean {
        if (captured != generation || !isAppKeyboardAllowed()) return false
        if (key.name == "SHIFT_LEFT") {
            clearModifiers()
            beforeInput()
            return captured == generation && isAppKeyboardAllowed() && send("SHIFT_LEFT", 0)
        }
        val cp = key.character(bits)
        if (cp != null) return literal(cp.code, bits and KeyHandler.KEYMOD_SHIFT.inv(), captured)
        beforeInput()
        return captured == generation && isAppKeyboardAllowed() && send(key.name, bits)
    }

    fun toggleExpanded() { /* Retained for old saved-state callers; keys stay expanded. */ }
    fun modifier(bit: Int) { if (isInputAllowed()) modifiers = modifiers xor bit }
    fun clearModifiers() { modifiers = 0 }
    fun key(name: String): Boolean {
        if (!isInputAllowed()) { clearModifiers(); return false }
        val captured = generation
        val bits = consumeModifiers()
        beforeInput()
        return isInputAllowed() && captured == generation && send(name, bits)
    }
    fun composerKey(name: String, captured: Long = generation): Boolean {
        if (captured != generation || direct || name !in setOf("UP", "DOWN", "ENTER", "SHIFT_TAB", "SHIFT_LEFT") || !canInput()) return false
        clearModifiers()
        beforeInput()
        return !direct && captured == generation && canInput() && send(name, 0)
    }
    override fun isScrollNavigationAllowed(): Boolean = canInput()
    fun answerQuestion(): Boolean {
        if (!chooseDirect() || !isInputAllowed()) return false
        clearModifiers()
        val captured = generation
        beforeInput()
        return captured == generation && isInputAllowed() && send("SHIFT_LEFT", 0)
    }

    fun canInput(): Boolean = focused && connected && available()
    override fun isInputAllowed(): Boolean = direct && canInput()
    override fun isCharBasedInput(): Boolean = compatibility
    override fun getInputGeneration(): Long = generation
    override fun beforeInput() { beforeKey() }
    override fun consumeModifiers(): Int = modifiers.also { modifiers = 0 }
}

/** Use the terminal's encoder so cursor/keypad modes and all modifier combinations agree. */
object TerminalKeys {
    @JvmStatic @JvmOverloads
    fun encode(name: String, modifiers: Int = 0, emulator: TerminalEmulator? = null): String? {
        if (name == "CTRL_C") return "\u0003"
        val code = when (name) {
            "ESC" -> android.view.KeyEvent.KEYCODE_ESCAPE
            "TAB", "SHIFT_TAB" -> android.view.KeyEvent.KEYCODE_TAB
            "ENTER" -> android.view.KeyEvent.KEYCODE_ENTER
            "BACKSPACE" -> android.view.KeyEvent.KEYCODE_DEL
            "UP" -> android.view.KeyEvent.KEYCODE_DPAD_UP
            "DOWN" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            "LEFT", "SHIFT_LEFT" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
            "RIGHT" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            "HOME" -> android.view.KeyEvent.KEYCODE_MOVE_HOME
            "END" -> android.view.KeyEvent.KEYCODE_MOVE_END
            "PAGE_UP" -> android.view.KeyEvent.KEYCODE_PAGE_UP
            "PAGE_DOWN" -> android.view.KeyEvent.KEYCODE_PAGE_DOWN
            "DELETE" -> android.view.KeyEvent.KEYCODE_FORWARD_DEL
            else -> name.removePrefix("F").toIntOrNull()?.takeIf { name.startsWith("F") && it in 1..12 }?.let { android.view.KeyEvent.KEYCODE_F1 + it - 1 } ?: return null
        }
        val bits = modifiers or if (name in setOf("SHIFT_TAB", "SHIFT_LEFT")) KeyHandler.KEYMOD_SHIFT else 0
        return KeyHandler.getCode(code, bits, emulator?.isCursorKeysApplicationMode == true, emulator?.isKeypadApplicationMode == true)
    }
}

val LocalTerminalInputController = androidx.compose.runtime.staticCompositionLocalOf<TerminalInputController?> { null }

/** Shared saved-state owner for a workspace pane; a fresh entry uses a fresh key. */
@androidx.compose.runtime.Composable
fun rememberTerminalInputController(id: String, ai: Boolean): TerminalInputController {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    return androidx.compose.runtime.saveable.rememberSaveable(id, ai, saver = androidx.compose.runtime.saveable.listSaver(
        save = { input: TerminalInputController -> listOf(input.direct, input.compatibility, input.expanded, input.previousNative, input.draft, input.terminalSurface, input.keyboardDismissed) },
        restore = { values -> TerminalKeyboardSettings.controller(context).apply {
            bind(id, ai)
            restore(values[0] as Boolean, values[1] as Boolean, values[2] as Boolean, values[3] as Boolean)
            draft = values[4] as String
            restoreSurface(values.getOrNull(5) as? Boolean ?: false, values.getOrNull(6) as? Boolean ?: true)
        } }
    )) { TerminalKeyboardSettings.controller(context).apply { bind(id, ai) } }
}
