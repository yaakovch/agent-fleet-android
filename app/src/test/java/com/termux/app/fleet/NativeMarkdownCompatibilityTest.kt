package com.termux.app.fleet

import android.app.Activity
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.StrikethroughSpan
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeMarkdownCompatibilityTest {
    private fun renderer() = nativeMarkdownMarkwon(Robolectric.buildActivity(Activity::class.java).get())

    @Test fun tableKeepsItsRowsAndAlignmentInsteadOfBecomingAParagraph() {
        val root = renderer().parse("| Name | Value | Notes |\n|:---|---:|:---:|\n| Voting patterns | 15 | Fixed |")
        assertEquals("TableBlock", root.firstChild.javaClass.simpleName)
        assertEquals("TableHead", root.firstChild.firstChild.javaClass.simpleName)
    }

    @Test fun strikethroughAndTaskStatesAreRendered() {
        val renderer = renderer()
        val text = renderer.toMarkdown("~~obsolete~~")
        assertEquals("obsolete", text.toString())
        assertEquals(1, text.getSpans(0, text.length, StrikethroughSpan::class.java).size)
        val tasks = renderer.toMarkdown("- [x] Complete\n- [ ] Pending")
        assertFalse(tasks.toString().contains("[x]"))
        assertFalse(tasks.toString().contains("[ ]"))
        assertEquals(2, tasks.getSpans(0, tasks.length, Any::class.java).count { it.javaClass.simpleName == "TaskListSpan" })
    }

    @Test fun softLineBreaksAndBareUrlsMatchTheWindowsFeed() {
        val text = renderer().toMarkdown("First line\nSecond line https://example.com/docs")
        assertTrue(text.toString().contains("First line\nSecond line"))
        assertEquals(1, text.getSpans(0, text.length, ClickableSpan::class.java).size)
    }

    @Test fun rawHtmlRemainsLiteralAndRemoteImagesHaveAnExplicitPlaceholder() {
        assertEquals("<b>literal</b>", renderer().toMarkdown("<b>literal</b>").toString())
        assertEquals("[Image omitted: diagram]", renderer().toMarkdown("![diagram](https://example.com/private.png)").toString())
    }

    @Test fun ordinaryMarkdownAndUnsafeLinkRulesRemainIntact() {
        val text = renderer().toMarkdown("## Heading\n\n**Bold** and *italic*, `code`, [safe](https://example.com) and [blocked](javascript:alert(1))")
        assertFalse(text.toString().contains("**"))
        assertFalse(text.toString().contains("javascript:"))
        assertEquals(1, text.getSpans(0, text.length, ClickableSpan::class.java).size)
    }
    @Test fun tableCellsPreserveEscapedPipesAlignmentAndReferenceLinks() {
        val grid = nativeMarkdownBlocks(renderer(), "| Name | Value | Notes |\n|:---|---:|:---:|\n| A\\|B | 15 | [docs][ref] |\n\n[ref]: https://example.com").single() as NativeMarkdownBlock.Grid
        assertEquals(2, grid.rows.size)
        assertTrue(grid.rows[0].header())
        assertEquals("Name", grid.rows[0].columns()[0].content().toString())
        assertEquals("A|B", grid.rows[1].columns()[0].content().toString())
        assertEquals(io.noties.markwon.ext.tables.Table.Alignment.RIGHT, grid.rows[1].columns()[1].alignment())
        val link = grid.rows[1].columns()[2].content()
        assertEquals(1, link.getSpans(0, link.length, ClickableSpan::class.java).size)
        assertTrue(grid.copyText.contains("A|B\t15\tdocs"))
    }

    @Test fun blockParserHandlesAlternateAndIndentedFencesWithoutLosingProse() {
        val blocks = nativeMarkdownBlocks(renderer(), "Before\n\n~~~sh\necho ok\n~~~\n\nAfter\n\n    indented\n")
        assertEquals(4, blocks.size)
        assertEquals("Before", (blocks[0] as NativeMarkdownBlock.Prose).content.toString())
        assertEquals(NativeMarkdownBlock.Code("sh", "echo ok"), blocks[1])
        assertEquals("After", (blocks[2] as NativeMarkdownBlock.Prose).content.toString())
        assertEquals(NativeMarkdownBlock.Code("", "indented"), blocks[3])
    }

}
