package com.termux.app.fleet

import android.text.Spanned
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.Table
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.CustomNode
import org.commonmark.node.Paragraph
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Document

internal sealed interface NativeMarkdownBlock {
    data class Prose(val content: Spanned) : NativeMarkdownBlock
    data class Code(val language: String, val content: String) : NativeMarkdownBlock
    data class Grid(val rows: List<Table.Row>) : NativeMarkdownBlock {
        val copyText: String get() = rows.joinToString("\n") { row ->
            row.columns().joinToString("\t") { it.content().toString() }
        }
    }
}

/** Use the same parser for display, alternate fences, and ordinary Markdown. */
internal fun nativeMarkdownBlocks(markwon: Markwon, value: String): List<NativeMarkdownBlock> {
    val blocks = mutableListOf<NativeMarkdownBlock>()
    var prose = Document()
    fun flushProse() {
        if (prose.firstChild != null) {
            val rendered = markwon.render(prose)
            if (rendered.isNotEmpty()) blocks += NativeMarkdownBlock.Prose(rendered)
        }
        prose = Document()
    }
    var node = markwon.parse(value).firstChild
    while (node != null) {
        val next = node.next
        when (node) {
            is FencedCodeBlock -> { flushProse(); blocks += NativeMarkdownBlock.Code(node.info.substringBefore(' ').take(32), node.literal.trimEnd('\n')) }
            is IndentedCodeBlock -> { flushProse(); blocks += NativeMarkdownBlock.Code("", node.literal.trimEnd('\n')) }
            is TableBlock -> {
                flushProse()
                blocks += nativeMarkdownGrid(markwon, node)
            }
            else -> { node.unlink(); prose.appendChild(node) }
        }
        node = next
    }
    flushProse()
    return blocks
}

private fun nativeMarkdownGrid(markwon: Markwon, table: TableBlock): NativeMarkdownBlock.Grid {
    val rows = mutableListOf<Table.Row>()
    table.accept(object : AbstractVisitor() {
        override fun visit(node: CustomNode) {
            if ((node !is TableHead && node !is TableRow) || node.firstChild !is TableCell) {
                visitChildren(node)
                return
            }
            val header = (node.firstChild as TableCell).isHeader
            val columns = mutableListOf<Table.Column>()
            var child = node.firstChild
            while (child != null) {
                val next = child.next
                if (child is TableCell) {
                    val alignment = when (child.alignment) {
                        TableCell.Alignment.RIGHT -> Table.Alignment.RIGHT
                        TableCell.Alignment.CENTER -> Table.Alignment.CENTER
                        else -> Table.Alignment.LEFT
                    }
                    // TablePlugin's TableCell visitor consumes its text into a
                    // span row. Render inline children through a paragraph so
                    // independent selectable cell views retain their content.
                    val paragraph = Paragraph()
                    while (child.firstChild != null) paragraph.appendChild(child.firstChild)
                    columns += Table.Column(alignment, markwon.render(paragraph))
                }
                child = next
            }
            rows += Table.Row(header, columns)
        }
    })
    return NativeMarkdownBlock.Grid(rows)
}
