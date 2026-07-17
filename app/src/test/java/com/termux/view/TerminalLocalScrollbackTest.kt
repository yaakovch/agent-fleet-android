package com.termux.view

import android.view.MotionEvent
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TerminalLocalScrollbackTest {
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
