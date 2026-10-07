package com.termux.app.fleet

import android.app.Activity
import android.content.Context
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WrappedHostFileTerminalTest {
    private fun terminal(width: Int = 24) : TerminalEmulator {
        val output = object: TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) {}
            override fun titleChanged(oldTitle: String?, newTitle: String?) {}
            override fun onCopyTextToClipboard(text: String?) {}
            override fun onPasteTextFromClipboard() {}
            override fun onBell() {}
            override fun onColorsChanged() {}
        }
        val client = Proxy.newProxyInstance(TerminalSessionClient::class.java.classLoader, arrayOf(TerminalSessionClient::class.java)) { _, method, _ -> if (method.name == "getTerminalCursorStyle") 0 else null } as TerminalSessionClient
        return TerminalEmulator(output, width, 20, 8, 16, 100, client)
    }
    private fun append(t: TerminalEmulator, value: String) { val bytes = value.toByteArray();t.append(bytes, bytes.size) }
    private fun tap(t: TerminalEmulator, row: Int, col: Int, osc: Boolean = false, providedView: TerminalView? = null): String? {
        val view = providedView ?: TerminalView(RuntimeEnvironment.getApplication(), null).also {
            it.setTextSize(14)
            TerminalView::class.java.getDeclaredField("mEmulator").also { field -> field.isAccessible=true }.set(it,t)
        }
        val renderer=TerminalView::class.java.getDeclaredField("mRenderer").also { it.isAccessible=true }.get(view)
        fun metric(name: String)= (renderer.javaClass.getDeclaredField(name).also { it.isAccessible=true }.get(renderer) as Number).toFloat()
        assertTrue("Renderer must have real glyph metrics",metric("mFontWidth")>0 && metric("mFontLineSpacing")>0)
        val topRow=TerminalView::class.java.getDeclaredField("mTopRow").also { it.isAccessible=true }.getInt(view)
        val x=(col+0.5f)*metric("mFontWidth");val y=metric("mFontLineSpacingAndAscent")+(row-topRow+0.5f)*metric("mFontLineSpacing")
        val event = MotionEvent.obtain(0, 1, MotionEvent.ACTION_UP, x, y, 0)
        return try { HostFileTerminalLinks.at(view, event, if (osc) t.screen.getHyperlinkAt(row, col) else null) } finally { event.recycle() }
    }
    @Test fun asciiSoftWrapResolvesAllSegments() {
        val path = "/home/user/projects/very-long-project/reports/wrapped-report.html"
        for (width in listOf(12, 24, 40)) {
            val t = terminal(width);append(t, path)
            for (index in path.indices) assertEquals("width=$width index=$index", path, tap(t, index/width, index%width))
        }
        println("DIAG ascii terminal soft wrap: full target at every character on all rows")
    }
    @Test fun quotedSpacesAndUnicodeSoftWrapKeepFullTarget() {
        for (path in listOf("/home/user/a long folder/a report.html", "/home/user/长目录/🐱/report.html")) {
            val t = terminal(16);append(t, "\"$path\"")
            val matches = (0..5).flatMap { row -> (0..15).map { col -> tap(t, row, col) } }.filterNotNull()
            assertTrue(matches.isNotEmpty());assertTrue(matches.all { it == path })
        }
        println("DIAG quoted spaces + Unicode terminal soft wrap: full targets")
    }
    @Test fun osc8TargetSurvivesSoftAndExplicitBreaks() {
        val path="file:///home/user/projects/very-long-project/reports/wrapped-report.html"
        for (label in listOf("This visible label is longer than one terminal row", "This visible label\r\ncontinues on another row")) {
            val t=terminal(24);append(t,"\u001b]8;;$path\u001b\\$label\u001b]8;;\u001b\\")
            assertEquals(path,tap(t,0,2,true));assertEquals(path,tap(t,1,2,true))
        }
        println("DIAG OSC8 terminal link: full target across both kinds of line break")
    }
    @Test fun hardRowsKeepOneDestinationAndExcludeIndentation() {
        val path = "/home/user/projects/very-long-project/reports/report.md"
        val t = terminal(72)
        append(t, "  Open (/home/user/projects/very-long-project/\r\n  reports/report.md)")
        assertEquals(path, tap(t,0,9)); assertEquals(path,tap(t,1,3))
        assertNull(tap(t,1,0)); assertNull(tap(t,1,1)); assertNull(tap(t,1,19))
        val relative = terminal(72)
        append(relative,"  Open (reports/very-long-\r\n  report.md)")
        assertEquals("reports/very-long-report.md",tap(relative,0,9))
        assertEquals("reports/very-long-report.md",tap(relative,1,3))
    }
    @Test fun ambiguousAndIncompleteGroupsHaveNoFragmentLink() {
        for (text in listOf("(/tmp/a-\r\n b.md)", "(/tmp/a-\r\nb.md", "(/tmp/a.md\r\n/tmp/b.md)")) {
            val t = terminal(72); append(t,text)
            assertNull(text,tap(t,0,2)); assertNull(text,tap(t,1,2))
        }
    }
}
