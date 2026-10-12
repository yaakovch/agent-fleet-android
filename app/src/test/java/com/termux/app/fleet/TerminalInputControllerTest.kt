package com.termux.app.fleet

import com.termux.terminal.KeyHandler
import org.junit.Assert.*
import org.junit.Test

class TerminalInputControllerTest {
    private fun live(): TerminalInputController = TerminalInputController().apply {
        bind("one", true); connection(true); available = { true }
    }
    @Test fun draftAndAttachmentsSurviveSwitchingAndModesResetOnNewEntry() {
        val input = live()
        input.draft = "unsent draft"
        val paths = listOf("/test/image.jpg")
        input.attachments = { paths }
        var sends = 0
        input.send = { _, _ -> sends++; true }
        input.chooseDirect(); input.chooseComposer()
        assertEquals("unsent draft", input.draft)
        assertEquals(paths, input.attachments())
        assertEquals(0, sends)
        input.chooseDirect(); input.toggleProfile(); input.toggleExpanded()
        input.bind("two", true)
        assertFalse(input.direct); assertFalse(input.compatibility); assertFalse(input.expanded)
        input.bind("shell", false); assertTrue(input.direct)
    }
    @Test fun shiftLeftOpensFirstAndSendsOneKeyWithoutSubmitting() {
        val input = live()
        val order = mutableListOf<String>()
        input.onDirect = { order += "open" }
        input.send = { name, bits -> order += TerminalKeys.encode(name, bits)!!; true }
        input.modifier(KeyHandler.KEYMOD_CTRL)
        assertTrue(input.answerQuestion())
        assertEquals(listOf("open", "\u001b[1;2D"), order)
        assertEquals(0, input.modifiers)
    }
    @Test fun reconnectAndModeChangesInvalidateOldEditorsAndNeverReplayKeys() {
        val input = live(); input.chooseDirect()
        val generation = input.inputGenerationForTest()
        input.modifier(KeyHandler.KEYMOD_SHIFT)
        input.connection(false)
        assertFalse(input.isInputAllowed()); assertFalse(input.key("LEFT"))
        assertEquals(0, input.modifiers)
        assertNotEquals(generation, input.getInputGeneration())
        input.connection(true)
        var sends = 0; input.send = { _, _ -> sends++; true }
        assertEquals(0, sends)
        input.chooseComposer(); assertFalse(input.isInputAllowed())
    }
    @Test fun touchModifiersAreOneShotButProfileAndModeSurviveRecreation() {
        val input = live(); input.chooseDirect(); input.toggleProfile(); input.toggleExpanded()
        input.modifier(KeyHandler.KEYMOD_SHIFT)
        val writes = mutableListOf<String>()
        input.send = { key, bits -> writes += TerminalKeys.encode(key, bits)!!; true }
        input.key("LEFT"); input.key("LEFT")
        assertEquals(listOf("\u001b[1;2D", "\u001b[D"), writes)
        val recreated = live().apply { restore(input.direct, input.compatibility, input.expanded, input.previousNative) }
        assertTrue(recreated.direct); assertTrue(recreated.compatibility); assertTrue(recreated.expanded)
        assertEquals(0, recreated.modifiers)
    }
    @Test fun nativeViewSelectionCancelsDirectInputWithoutRestoringAnotherView() {
        val input = live(); input.chooseDirect(); input.modifier(KeyHandler.KEYMOD_SHIFT)
        val before = input.getInputGeneration()
        var callbacks = 0; input.onComposer = { callbacks++ }
        input.readingViewChanged(true)
        assertFalse(input.direct); assertFalse(input.isInputAllowed()); assertEquals(0, input.modifiers)
        assertNotEquals(before, input.getInputGeneration()); assertEquals(0, callbacks)
    }
    @Test fun focusChangeInvalidatesLateEditorsAndClearsTouchModifiers() {
        val input = live(); input.chooseDirect(); input.modifier(KeyHandler.KEYMOD_ALT)
        val old = input.getInputGeneration()
        input.focus(false)
        assertEquals(0, input.modifiers); assertFalse(input.isInputAllowed())
        input.focus(true)
        assertTrue(input.isInputAllowed()); assertNotEquals(old, input.getInputGeneration())
    }
    @Test fun rechecksTargetAvailabilityAfterOpening() {
        val input = live()
        var valid = true; input.available = { valid }
        input.onDirect = { valid = false }
        var sends = 0; input.send = { _, _ -> sends++; true }
        assertFalse(input.answerQuestion()); assertEquals(0, sends)
    }
    private fun TerminalInputController.inputGenerationForTest() = getInputGeneration()
}
