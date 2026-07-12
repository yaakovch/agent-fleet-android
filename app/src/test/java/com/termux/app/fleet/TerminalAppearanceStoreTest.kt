package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAppearanceStoreTest {
    @Test
    fun builtInThemesHaveUniqueIdsAndCompletePalettes() {
        val themes = TerminalAppearanceStore.themes
        assertEquals(themes.size, themes.map { it.id }.distinct().size)
        assertTrue(themes.any { it.id == "system" && !it.managed })
        assertTrue(themes.all { it.colors.size == 16 })
        assertTrue(themes.all { it.foreground.matches(COLOR) && it.background.matches(COLOR) && it.cursor.matches(COLOR) })
        assertTrue(themes.flatMap { it.colors }.all { it.matches(COLOR) })
    }

    @Test
    fun managedBlockPreservesUserContentAndIsIdempotent() {
        val original = "# user setting\nuse-black-ui=true\n"
        val first = TerminalAppearanceStore.replaceManagedBlock(original, BEGIN, END, listOf("terminal-cursor-style=bar"))
        val second = TerminalAppearanceStore.replaceManagedBlock(first, BEGIN, END, listOf("terminal-cursor-style=bar"))
        assertEquals(first, second)
        assertTrue(first.startsWith(original.trimEnd()))
        assertEquals(1, first.lineSequence().count { it == BEGIN })
    }

    @Test
    fun removingManagedBlockRestoresUserContent() {
        val managed = "# user\nforeground=#ffffff\n\n$BEGIN\nforeground=#000000\n$END\n"
        val restored = TerminalAppearanceStore.replaceManagedBlock(managed, BEGIN, END, null)
        assertEquals("# user\nforeground=#ffffff\n", restored)
        assertFalse(restored.contains("#000000"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedManagedBlockIsRejected() {
        TerminalAppearanceStore.replaceManagedBlock("$BEGIN\nvalue=1\n", BEGIN, END, emptyList())
    }

    private companion object {
        val COLOR = Regex("#[0-9a-fA-F]{6}")
        const val BEGIN = "# BEGIN test"
        const val END = "# END test"
    }
}
