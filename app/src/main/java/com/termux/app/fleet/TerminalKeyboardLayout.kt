package com.termux.app.fleet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.KeyHandler

/** Local US coding layout. Page controls are never terminal keys. */
data class TerminalKeyboardKey(
    val name: String,
    val text: String = name,
    val shifted: Char? = null,
    val width: Float = 1f,
    val modifier: Int = 0,
    val page: String? = null,
    val printable: Char? = null
) {
    val repeatable get() = name in setOf("BACKSPACE", "LEFT", "RIGHT", "UP", "DOWN")
    fun character(bits: Int): Char? = printable?.let {
        if (bits and KeyHandler.KEYMOD_SHIFT != 0) shifted ?: it.uppercaseChar() else it
    }
}

object TerminalKeyboardLayout {
    private fun literal(c: Char, shift: Char = c, width: Float = 1f) =
        TerminalKeyboardKey("CHAR_${c.code}", c.toString(), shift, width, printable = c)
    private fun literals(base: String, shift: String = base.uppercase()) = base.mapIndexed { i, c -> literal(c, shift[i]) }
    private fun named(name: String, text: String = name, width: Float = 1f) = TerminalKeyboardKey(name, text, width = width)
    private val shift = TerminalKeyboardKey("SHIFT", "⇧", width = 1.5f, modifier = KeyHandler.KEYMOD_SHIFT)
    private val ctrl = TerminalKeyboardKey("CTRL", "Ctrl", modifier = KeyHandler.KEYMOD_CTRL)
    private val alt = TerminalKeyboardKey("ALT", "Alt", modifier = KeyHandler.KEYMOD_ALT)
    private fun page(name: String, text: String = name) = TerminalKeyboardKey("PAGE_$name", text, page = name)
    private val numbers = literals("1234567890", "!@#$%^&*()")
    private fun bottom(symbols: Boolean) = listOf(named("ESC", "Esc"), named("TAB", "Tab"), ctrl, alt,
        page("Fn"), page(if (symbols) "ABC" else "Symbols", if (symbols) "ABC" else "#+="),
        literal(' ', width = 2f), named("LEFT", "←"), named("RIGHT", "→"))
    fun rows(page: String): List<List<TerminalKeyboardKey>> = when (page) {
        "Fn" -> listOf(
            (1..6).map { named("F$it") }, (7..12).map { named("F$it") },
            listOf(named("HOME", "Home"), named("END", "End"), named("PAGE_UP", "PgUp"), named("PAGE_DOWN", "PgDn"), named("DELETE", "Del"), named("BACKSPACE", "⌫")),
            listOf(ctrl, alt, shift.copy(width = 1f), named("ESC", "Esc"), named("TAB", "Tab"), named("ENTER", "↵")),
            listOf(page("ABC"), named("SHIFT_LEFT", "⇧←"), named("LEFT", "←"), named("DOWN", "↓"), named("UP", "↑"), named("RIGHT", "→")))
        "Symbols" -> listOf(numbers, literals("-=[]\\;',./", "_+{}|:\"<>?"),
            listOf(literal('`', '~', 1.5f)) + literals("<>?!@#$", "<>?!@#$") + named("BACKSPACE", "⌫", 1.5f),
            listOf(shift) + literals("(){}_+|", "(){}_+|") + named("ENTER", "↵", 1.5f), bottom(true))
        else -> listOf(numbers, literals("qwertyuiop"), literals("asdfghjkl") + named("BACKSPACE", "⌫"),
            listOf(shift) + literals("zxcvbnm") + named("ENTER", "↵", 1.5f), bottom(false))
    }
    /** Wide windows use four rows with 14 units; targets remain nonoverlapping. */
    fun wideRows(page: String): List<List<TerminalKeyboardKey>> {
        if (page == "Fn") return listOf(
            (1..8).map { named("F$it") },
            (9..12).map { named("F$it") } + listOf(named("HOME", "Home"), named("END", "End"), named("PAGE_UP", "PgUp"), named("PAGE_DOWN", "PgDn")),
            listOf(ctrl, alt, shift.copy(width = 1f), named("ESC", "Esc"), named("TAB", "Tab"), named("ENTER", "↵"), named("DELETE", "Del"), named("BACKSPACE", "⌫")),
            listOf(page("ABC"), named("SHIFT_LEFT", "⇧←"), named("LEFT", "←"), named("DOWN", "↓"), named("UP", "↑"), named("RIGHT", "→"), literal(' ', width = 2f)))
        val r = rows(page)
        return listOf(r[0] + r[4].take(4), r[1] + listOf(r[4][4], r[4][5], r[4][7], r[4][8]),
            r[2] + listOf(named("HOME", "Home"), named("UP", "↑"), named("DOWN", "↓"), named("END", "End")),
            r[3] + literal(' ', width = 4f))
    }
}

/** Unsaved pointer leases: one-shot modifiers are reserved by the first key down. */
class TerminalKeyboardGestures {
    private data class Hold(val bit: Int, var used: Boolean = false)
    private val held = mutableMapOf<Long, Hold>()
    var oneShot by mutableStateOf(0)
        private set
    var heldBits by mutableStateOf(0)
        private set
    var page by mutableStateOf("ABC")
        private set
    val bits get() = oneShot or heldBits
    fun downModifier(pointer: Long, bit: Int) { held[pointer] = Hold(bit); updateHeld() }
    fun upModifier(pointer: Long, canceled: Boolean = false) {
        val hold = held.remove(pointer) ?: return
        if (!canceled && !hold.used) oneShot = oneShot xor hold.bit
        updateHeld()
    }
    fun reserve(): Int {
        val value = bits
        held.values.forEach { it.used = true }
        oneShot = 0
        return value
    }
    fun accessibilityModifier(bit: Int) { oneShot = oneShot xor bit }
    fun changePage(value: String) { cancel(); page = value }
    fun cancel() { held.clear(); heldBits = 0; oneShot = 0 }
    private fun updateHeld() { heldBits = held.values.fold(0) { bits, h -> bits or h.bit } }
}
