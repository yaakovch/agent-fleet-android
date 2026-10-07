package com.termux.view

import android.view.MotionEvent
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TerminalLocalScrollbackTest {
    @Test fun explicitHistoryAlsoShowsACaptureWithoutOlderRows() {
        val view = TerminalView(RuntimeEnvironment.getApplication(), null)
        val live = TerminalEmulator(NO_OUTPUT, 20, 10, 1, 1, 100, null)
        val text = "\u001b[?1049hLive".toByteArray()
        live.append(text, text.size)
        view.mEmulator = live
        assertTrue(view.setLocalScrollback("Current snapshot".toByteArray(), 20, 10))
        assertTrue(view.enterPreparedLocalHistory())
        assertTrue(view.isLocalScrollbackActive)
        assertEquals(0, view.mTopRow)
        view.returnToLiveTerminal()
        assertSame(live, view.displayedEmulator())
    }
    @Test fun explicitRefreshReplacesHistoryWhileBackgroundCaptureKeepsItStable() {
        val view = TerminalView(RuntimeEnvironment.getApplication(), null)
        val live = TerminalEmulator(NO_OUTPUT, 20, 10, 1, 1, 100, null)
        val text = "\u001b[?1049hLive".toByteArray()
        live.append(text, text.size)
        view.mEmulator = live
        fun capture(prefix: String) = TerminalView.prepareLocalScrollback(
            (0 until 40).joinToString("\n") { "$prefix-$it" }.toByteArray(), 20, 10, null)
        assertTrue(view.installPreparedScrollback(capture("old")))
        assertTrue(view.enterPreparedLocalHistory())
        val displayed = view.displayedEmulator()
        assertFalse(view.installPreparedScrollback(capture("new")))
        assertSame(displayed, view.displayedEmulator())
        assertTrue(view.installPreparedScrollback(capture("new"), true))
        assertTrue(view.isLocalScrollbackActive)
        assertEquals(-1, view.mTopRow)
        assertTrue(view.displayedEmulator().screen.transcriptText.contains("new-0"))
        view.returnToLiveTerminal()
        assertSame(live, view.displayedEmulator())
        assertTrue(view.hasLocalScrollback())
    }
    @Test
    fun upwardScrollUsesPrefetchedPaneAndBottomReturnsToLive() {
        val view = TerminalView(RuntimeEnvironment.getApplication(), null)
        val live = TerminalEmulator(NO_OUTPUT, 20, 10, 1, 1, 100, null)
        live.append("\u001b[?1049hLive".toByteArray(), 12)
        view.mEmulator = live
        val capture = (0 until 40).joinToString("\n") { "row-$it" }.toByteArray()

        assertTrue(view.setLocalScrollback(capture, 20, 10))
        assertFalse(view.isLocalScrollbackActive)

        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 1f, 1f, 0)
        view.doScroll(event, -3)
        assertTrue(view.isLocalScrollbackActive)
        view.doScroll(event, 500)
        assertFalse(view.isLocalScrollbackActive)
        assertTrue(view.hasLocalScrollback())
        event.recycle()
    }

    private companion object {
        val NO_OUTPUT = object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) = Unit
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String?) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
        }
    }
}
