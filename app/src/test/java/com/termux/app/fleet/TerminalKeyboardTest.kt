package com.termux.app.fleet

import com.termux.terminal.KeyHandler
import org.junit.Assert.*
import org.junit.Test

class TerminalKeyboardTest {
    private fun live() = TerminalInputController().apply { bind("one", true); connection(true); available = { true }; chooseDirect(); chooseSurface(true); updateKeyboardReady(true) }
    @Test fun explicitHiddenEntryKeepsBothKeyboardsHiddenWithoutBlockingHardwareOrNamedKeys() {
        val input = TerminalInputController().apply { systemImeOnEntry = { false }; bind("hidden", true); connection(true); available = { true }; draft = "Keep me" }
        input.chooseDirect()
        assertTrue(input.keyboardDismissed)
        assertFalse(input.isSystemImeAllowed())
        assertFalse(input.wantsTerminalKeyboard())
        assertTrue(input.isInputAllowed())
        var writes = 0; input.send = { _, _ -> writes++; true }
        assertTrue(input.key("SHIFT_LEFT")); assertEquals(1, writes)
        input.connection(false); input.connection(true); input.chooseDirect()
        assertTrue(input.keyboardDismissed)
        assertEquals("Keep me", input.draft)
        input.bind("shell", false)
        assertTrue(input.direct); assertTrue(input.keyboardDismissed)
    }
    @Test fun directDefaultsToAndroidAndLetterRevealRetainsDraftAndRevokesOldEditors() {
        val input = TerminalInputController().apply { bind("ai", true); connection(true); available = { true }; draft = "Keep me" }
        input.attachments = { listOf("image.png") }
        var writes = 0; var freezes = 0; var discards = 0
        input.send = { _, _ -> writes++; true }
        input.freezeComposition = { freezes++ }; input.discardComposition = { discards++ }
        input.chooseDirect()
        assertTrue(input.isSystemImeAllowed()); assertFalse(input.letterKeysVisible)
        val old = input.getInputGeneration()
        input.modifier(KeyHandler.KEYMOD_CTRL)
        assertTrue(input.toggleKeyboardLayout())
        assertTrue(input.letterKeysVisible); assertTrue(input.keyboardDismissed); assertFalse(input.isSystemImeAllowed())
        assertNotEquals(old, input.getInputGeneration()); assertFalse(input.key("ENTER", old)); assertEquals(0, input.modifiers)
        assertEquals(2, freezes); assertEquals(0, discards); assertEquals(0, writes)
        assertEquals("Keep me", input.draft); assertEquals(listOf("image.png"), input.attachments())
        assertTrue(input.toggleKeyboardLayout()); assertTrue(input.isSystemImeAllowed()); assertFalse(input.letterKeysVisible)
        input.showLetterKeys(); assertTrue(input.dismissKeyboard()); assertFalse(input.letterKeysVisible)
        assertTrue(input.keyboardDismissed)
        input.chooseComposer(); input.chooseDirect()
        assertTrue(input.isSystemImeAllowed()); assertFalse(input.letterKeysVisible)
        input.bind("fresh-shell", false); input.connection(true)
        assertTrue(input.isSystemImeAllowed()); assertFalse(input.letterKeysVisible)
        input.connection(false); assertFalse(input.showLetterKeys()); assertFalse(input.letterKeysVisible)
    }
    @Test fun hideRevokesBothSourcesAndFreezesPreeditUntilExplicitReveal() {
        val input = live(); input.chooseSurface(false)
        var freezes = 0; input.freezeComposition = { freezes++ }
        val systemGeneration = input.getInputGeneration()
        assertTrue(input.dismissKeyboard()); assertEquals(1, freezes)
        assertNotEquals(systemGeneration, input.getInputGeneration())
        assertFalse(input.isSystemImeAllowed()); assertFalse(input.isAppKeyboardAllowed())
        assertTrue(input.showKeyboard()); assertTrue(input.isSystemImeAllowed())
        input.chooseSurface(true); input.updateKeyboardReady(true)
        val appGeneration = input.getInputGeneration()
        input.gestures.downModifier(1, KeyHandler.KEYMOD_CTRL)
        assertTrue(input.toggleKeyboard()); assertEquals(0, input.gestures.bits)
        assertFalse(input.appKey(TerminalKeyboardLayout.rows("ABC")[1][0], 0, appGeneration))
        assertTrue(input.toggleKeyboard()); assertTrue(input.wantsTerminalKeyboard()); assertFalse(input.keyboardReady)
    }
    @Test fun androidEntryPreferenceAppliesOnlyOnEntryAndDoesNotReopenDismissedIme() {
        var show = true
        val input = TerminalInputController().apply { systemImeOnEntry = { show }; bind("ai", true); connection(true); available = { true } }
        input.chooseDirect(); assertTrue(input.isSystemImeAllowed()); assertFalse(input.terminalSurface)
        input.dismissKeyboard(); input.connection(false); input.connection(true); input.chooseDirect()
        assertTrue(input.keyboardDismissed)
        input.chooseComposer(); input.chooseDirect(); assertTrue(input.isSystemImeAllowed())
        show = false
        input.chooseComposer(); input.chooseDirect(); assertTrue(input.keyboardDismissed)
        input.bind("shell", false); assertTrue(input.direct); assertTrue(input.keyboardDismissed)
        show = true; input.bind("new-shell", false); input.connection(true); assertTrue(input.isInputAllowed()); assertTrue(input.isSystemImeAllowed())
    }
    @Test fun entryNeverDiscardsOrSendsPendingComposition() {
        val input = TerminalInputController().apply { systemImeOnEntry = { false }; bind("ai", true); connection(true); available = { true } }
        var writes = 0; var discarded = 0; var freezes = 0
        input.send = { _, _ -> writes++; true }; input.discardComposition = { discarded++ }
        input.pendingComposition = { true }; input.freezeComposition = { freezes++ }
        input.chooseDirect(); assertFalse(input.isSystemImeAllowed())
        assertEquals(1, freezes); assertEquals(0, writes); assertEquals(0, discarded)
    }
    @Test fun allPrintableAsciiAndBalancedLayouts() {
        val chars = listOf("ABC", "Symbols").flatMap { page -> TerminalKeyboardLayout.rows(page).flatten().flatMap { key -> listOfNotNull(key.character(0), key.character(KeyHandler.KEYMOD_SHIFT)) } }.toSet()
        assertEquals((32..126).map(Int::toChar).toSet(), chars)
        for (page in listOf("ABC", "Symbols", "Fn")) {
            val rows = TerminalKeyboardLayout.rows(page)
            assertEquals(5, rows.size)
            rows.forEach { assertEquals(if (page == "Fn") 6f else 10f, it.sumOf { key -> key.width.toDouble() }.toFloat(), 0.01f) }
            TerminalKeyboardLayout.wideRows(page).forEach { assertEquals(if (page == "Fn") 8f else 14f, it.sumOf { key -> key.width.toDouble() }.toFloat(), 0.01f) }
        }
    }
    @Test fun heldAndOneShotModifiersReserveAtMostOneConcurrentKey() {
        val keys = TerminalKeyboardGestures()
        keys.downModifier(1, KeyHandler.KEYMOD_CTRL); keys.upModifier(1)
        assertEquals(KeyHandler.KEYMOD_CTRL, keys.reserve()); assertEquals(0, keys.reserve())
        keys.downModifier(2, KeyHandler.KEYMOD_SHIFT)
        assertEquals(KeyHandler.KEYMOD_SHIFT, keys.reserve()); assertEquals(KeyHandler.KEYMOD_SHIFT, keys.reserve())
        keys.upModifier(2); assertEquals(0, keys.reserve())
        keys.downModifier(3, KeyHandler.KEYMOD_ALT); keys.upModifier(3, canceled = true); assertEquals(0, keys.bits)
        keys.downModifier(4, KeyHandler.KEYMOD_CTRL); keys.changePage("Fn"); keys.upModifier(4); assertEquals(0, keys.bits)
    }
    @Test fun switchesInvalidateInputAndNeverEmitPreeditOrRestoreGestures() {
        val input = live(); val old = input.getInputGeneration()
        input.gestures.downModifier(1, KeyHandler.KEYMOD_CTRL)
        input.chooseComposer()
        assertFalse(input.appKey(TerminalKeyboardLayout.rows("ABC")[1][0], 0, old)); assertEquals(0, input.gestures.bits)
        input.chooseDirect(); assertFalse(input.keyboardReady); assertTrue(input.isSystemImeAllowed())
        input.showKeyboard(); input.updateKeyboardReady(true); assertTrue(input.dismissKeyboard()); assertFalse(input.wantsTerminalKeyboard())
        input.connection(false); input.connection(true); assertFalse(input.wantsTerminalKeyboard())
        input.chooseDirect(); assertFalse(input.wantsTerminalKeyboard())
        input.showKeyboard(); assertTrue(input.isSystemImeAllowed())
        input.chooseSurface(false); assertTrue(input.isSystemImeAllowed())
        input.pendingComposition = { true }; var discarded = false; input.discardComposition = { discarded = true }
        assertFalse(input.chooseSurface(true)); assertFalse(discarded); assertFalse(input.terminalSurface)
        assertTrue(input.chooseSurface(true, discard = true)); assertTrue(discarded)
    }
    @Test fun targetIsRecheckedAfterHooksAndDedicatedQuestionIgnoresModifiers() {
        val input = live(); val writes = mutableListOf<String>()
        input.send = { name, bits -> writes += TerminalKeys.encode(name, bits)!!; true }
        val arrow = TerminalKeyboardLayout.rows("Fn")[4][1]
        assertTrue(input.appKey(arrow, KeyHandler.KEYMOD_CTRL or KeyHandler.KEYMOD_ALT, input.getInputGeneration()))
        assertEquals(listOf("\u001b[1;2D"), writes)
        input.beforeKey = { input.focus(false) }
        assertFalse(input.appKey(arrow, 0, input.getInputGeneration())); assertEquals(1, writes.size)
    }
    @Test fun selectorSuspendsImeUntilExplicitKeepOrDiscard() {
        val input = live(); input.chooseSurface(false)
        var frozen = 0; input.freezeComposition = { frozen++ }
        input.beginSurfaceSelection(); assertEquals(1, frozen); assertFalse(input.isSystemImeAllowed())
        input.pendingComposition = { true }
        assertFalse(input.chooseSurface(true)); assertFalse(input.isSystemImeAllowed())
        input.cancelSurfaceSelection(); assertTrue(input.isSystemImeAllowed()); assertFalse(input.surfaceBlocked)
        input.beginSurfaceSelection(); assertTrue(input.chooseSurface(true, discard = true)); assertFalse(input.isSystemImeAllowed())
    }
    @Test fun replacingViewOrAttachmentInvalidatesHeldInputEvenWhenStillLive() {
        val input = live()
        val view = Any(); val terminal = Any()
        input.onTerminalTargetChanged(view, terminal); input.updateKeyboardReady(true)
        val before = input.getInputGeneration()
        input.gestures.downModifier(1, KeyHandler.KEYMOD_CTRL)
        input.onTerminalTargetChanged(view, Any())
        assertNotEquals(before, input.getInputGeneration()); assertFalse(input.keyboardReady); assertEquals(0, input.gestures.bits)
        val next = input.getInputGeneration(); input.onTerminalTargetChanged(Any(), terminal)
        assertNotEquals(next, input.getInputGeneration())
    }
    @Test fun shiftPunctuationIsAppliedExactlyOnce() {
        val input = live(); val chars = mutableListOf<Pair<Int,Int>>()
        input.literal = { cp, bits, _ -> chars += cp to bits; true }
        val key = TerminalKeyboardLayout.rows("ABC")[0][0]
        assertTrue(input.appKey(key, KeyHandler.KEYMOD_SHIFT or KeyHandler.KEYMOD_ALT, input.getInputGeneration()))
        assertEquals(listOf('!'.code to KeyHandler.KEYMOD_ALT), chars)
        val old = input.getInputGeneration(); input.focus(false); input.focus(true); input.updateKeyboardReady(true)
        assertFalse(input.appKey(key, 0, old)); assertEquals(1, chars.size)
    }
}
