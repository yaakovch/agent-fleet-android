package com.termux.app.fleet

import com.termux.terminal.KeyHandler
import org.junit.Assert.*
import org.junit.Test

class TerminalInputControllerTest {
    @Test fun shellEntryDoesNotRestoreAnUnselectedNativeViewWhenAnAgentStarts() {
        val input = TerminalInputController().apply {
            bind("new-shell", false); connection(true); available = { true }
        }
        assertTrue(input.direct); assertFalse(input.previousNative)
        input.providerChanged()
        var restoredNative = true
        input.onComposer = { restoredNative = input.previousNative }
        input.chooseComposer()
        assertFalse(restoredNative)
        input.readingNative = { true }; input.chooseDirect()
        input.chooseComposer()
        assertTrue(restoredNative)
    }

    @Test fun providerChangePreservesInputStateAndRevokesOldCallbacks() {
        val input = live(); input.draft = "Keep this draft"
        input.attachments = { listOf("image.png") }
        var freezes = 0; var writes = 0
        input.freezeComposition = { freezes++ }
        input.send = { _, _ -> writes++; true }
        val old = input.getInputGeneration()
        input.providerChanged()
        assertEquals(1, freezes); assertFalse(input.direct)
        assertEquals("Keep this draft", input.draft)
        assertEquals(listOf("image.png"), input.attachments())
        assertFalse(input.composerKey("ENTER", old)); assertEquals(0, writes)
        assertTrue(input.composerKey("ENTER")); assertEquals(1, writes)
        input.chooseDirect(); input.toggleProfile()
        input.modifier(KeyHandler.KEYMOD_SHIFT)
        val directGeneration = input.getInputGeneration()
        input.providerChanged()
        assertTrue(input.direct); assertTrue(input.compatibility)
        assertEquals(0, input.modifiers)
        assertFalse(input.key("ENTER", directGeneration)); assertEquals(1, writes)
        assertEquals("Keep this draft", input.draft)
    }
    @Test fun phrasesSendOnceWithOptionalEnterAndNeverSubmitTheDraft() {
        val input = live(); input.draft = "Keep my draft"; input.attachments = { listOf("image.png") }
        val writes = mutableListOf<String>(); input.sendText = { writes += it; true }
        assertTrue(input.composerPhrase("Continue", true)); assertTrue(input.composerPhrase("One more thing 🙂", false))
        assertEquals(listOf("Continue\r", "One more thing 🙂"), writes)
        assertEquals("Keep my draft", input.draft); assertEquals(listOf("image.png"), input.attachments()); assertFalse(input.direct)
        assertFalse(input.composerPhrase("hidden\nEnter", false)); assertEquals(2, writes.size)
    }
    @Test fun phrasesRejectDisconnectedUnfocusedChangedAndLateTargetsIncludingBeforeInputHooks() {
        val input = live(); var writes = 0; input.sendText = { writes++; true }
        val original = input.getInputGeneration()
        input.connection(false); assertFalse(input.composerPhrase("Continue", true)); input.connection(true)
        assertFalse(input.composerPhrase("Continue", true, original))
        input.focus(false); assertFalse(input.composerPhrase("Continue", true)); input.focus(true)
        input.available = { false }; assertFalse(input.composerPhrase("Continue", true)); input.available = { true }
        input.beforeKey = { input.onTerminalTargetChanged(Any(), Any()) }
        assertFalse(input.composerPhrase("Continue", true)); assertEquals(0, writes)
        input.beforeKey = {}; input.chooseDirect(); assertFalse(input.composerPhrase("Continue", true)); assertEquals(0, writes)
    }
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
        assertFalse(input.direct); assertFalse(input.compatibility); assertTrue(input.expanded)
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
    @Test fun composerKeysPreserveDraftReadingModeAndAttachmentsAndRejectStaleTargets() {
        val input = live(); input.draft = "Keep me"
        input.attachments = { listOf("image.png") }
        var viewChanges = 0; input.onDirect = { viewChanges++ }; input.onComposer = { viewChanges++ }
        val writes = mutableListOf<String>()
        input.send = { key, bits -> writes += TerminalKeys.encode(key, bits)!!; true }
        listOf("UP", "DOWN", "ENTER", "SHIFT_TAB", "SHIFT_LEFT", "CTRL_C", "SPACE", "BACKSPACE", "DELETE").forEach { assertTrue(input.composerKey(it)) }
        assertEquals(listOf("\u001b[A", "\u001b[B", "\r", "\u001b[Z", "\u001b[1;2D", "\u0003", " ", "\u007f", "\u001b[3~"), writes)
        assertEquals("Keep me", input.draft); assertEquals(listOf("image.png"), input.attachments())
        assertFalse(input.direct); assertEquals(0, viewChanges)
        input.connection(false); assertFalse(input.composerKey("ENTER")); input.connection(true)
        val old = input.getInputGeneration()
        input.onTerminalTargetChanged(Any(), Any())
        assertFalse(input.composerKey("ENTER", old))
        input.beforeKey = { input.onTerminalTargetChanged(Any(), Any()) }
        assertFalse(input.composerKey("ENTER")); assertEquals(9, writes.size)
    }
    @Test fun scrollNavigationIsIndependentOfTypingButRetainsAvailabilityAndFocusChecks() {
        val input = live()
        assertTrue(input.isScrollNavigationAllowed()); assertFalse(input.isInputAllowed())
        input.focus(false); assertFalse(input.isScrollNavigationAllowed()); input.focus(true)
        input.connection(false); assertFalse(input.isScrollNavigationAllowed()); input.connection(true)
        input.available = { false }; assertFalse(input.isScrollNavigationAllowed())
        input.available = { true }; input.chooseDirect(); assertTrue(input.isScrollNavigationAllowed())
        input.restore(true, true, false); input.restoreSurface(true, false)
        assertTrue(input.expanded); assertFalse(input.terminalSurface); assertTrue(input.keyboardDismissed)
    }
    @Test fun touchCharactersAndNamedKeysRejectCapturedAuthorityFromEarlierTargets() {
        val input = live()
        var characters = 0
        var named = 0
        input.directLiteral = { cp, captured ->
            assertEquals('q'.code, cp)
            assertEquals(input.getInputGeneration(), captured)
            characters++; true
        }
        input.send = { _, _ -> named++; true }
        assertFalse(input.character('q'.code))
        input.chooseDirect()
        val old = input.getInputGeneration()
        assertTrue(input.character('q'.code, old))
        input.connection(false)
        assertFalse(input.character('q'.code)); assertFalse(input.key("ENTER", old))
        input.connection(true)
        assertFalse(input.character('q'.code, old)); assertFalse(input.key("ENTER", old))
        input.focus(false); assertFalse(input.character('q'.code)); input.focus(true)
        val attachment = input.getInputGeneration()
        input.onTerminalTargetChanged(Any(), Any())
        assertFalse(input.character('q'.code, attachment))
        input.chooseComposer(); assertFalse(input.character('q'.code))
        assertEquals(1, characters); assertEquals(0, named)
        assertEquals("", input.draft)
    }
    private fun TerminalInputController.inputGenerationForTest() = getInputGeneration()
}
