package com.termux.app.fleet

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SavedPhrasesTest {
    @Test fun newPhrasesDefaultEnterOffAndPopupStaysAboveItsAnchor() {
        assertFalse(SavedPhrases.newPhrase().enter)
        val position = AbovePhraseIcon(8, 12)
        val result = position.calculatePosition(androidx.compose.ui.unit.IntRect(300, 600, 348, 648),
            androidx.compose.ui.unit.IntSize(400, 800), androidx.compose.ui.unit.LayoutDirection.Ltr, androidx.compose.ui.unit.IntSize(320, 200))
        assertEquals(androidx.compose.ui.unit.IntOffset(68, 392), result)
        val left = position.calculatePosition(androidx.compose.ui.unit.IntRect(0, 400, 48, 448),
            androidx.compose.ui.unit.IntSize(400, 800), androidx.compose.ui.unit.LayoutDirection.Rtl, androidx.compose.ui.unit.IntSize(320, 200))
        assertEquals(androidx.compose.ui.unit.IntOffset(12, 192), left)
    }
    @Test fun savedEditsEnterChoicesUnicodeAndEmptyListSurviveReload() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(listOf(SavedPhrase("continue", "Continue", true)), SavedPhrases.load(context))
        val phrases = listOf(SavedPhrase("one", "כן, continue 🙂", false))
        SavedPhrases.save(context, phrases); assertEquals(phrases, SavedPhrases.load(context))
        SavedPhrases.save(context, emptyList()); assertTrue(SavedPhrases.load(context).isEmpty())
    }
    @Test fun malformedDataIsNotOverwrittenAndDuplicateIdsAreRejected() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("agent-fleet-saved-phrases-v1", Context.MODE_PRIVATE)
        prefs.edit().putString("phrases", "{broken").commit()
        assertTrue(runCatching { SavedPhrases.load(context) }.isFailure)
        assertEquals("{broken", prefs.getString("phrases", null))
        assertTrue(runCatching { SavedPhrases.save(context, listOf(SavedPhrase("one", "A", true), SavedPhrase("one", "B", false))) }.isFailure)
    }
    @Test fun invalidTextCannotSneakInAnImplicitEnterOrControlKey() {
        listOf("", "  ", "A\r", "A\nB", "\u001b[31m", "A\u0000", "A\t", "A".repeat(2001)).forEach { assertFalse(it, SavedPhrases.validText(it)) }
        assertTrue(SavedPhrases.validText("Continue \\n $" + "HOME 🙂"))
    }
}
