package com.termux.app.fleet

import android.view.MotionEvent
import com.termux.view.TerminalView

object HostFileTerminalLinks {
    @JvmStatic fun at(view: TerminalView, event: MotionEvent, hyperlink: String?): String? {
        if (hyperlink != null) HostFileReferences.target(hyperlink, true)?.let { return it }
        val location = view.getColumnAndRow(event, true)
        val emulator = view.displayedEmulator() ?: return null
        val screen = emulator.screen
        val row = location[1]
        if (row < -screen.activeTranscriptRows || row >= emulator.mRows) return null
        var first = row
        var last = row
        while (first > -screen.activeTranscriptRows && row - first < 32 && screen.getLineWrap(first - 1)) first--
        while (last < emulator.mRows - 1 && last - first < 32 && screen.getLineWrap(last)) last++
        val content = StringBuilder()
        var offset = screen.getColumnTextOffset(row, location[0])
        if (offset < 0) return null
        for (current in first..last) {
            val raw = screen.getLineTextAt(current)
            if (current < row) offset += raw.length
            content.append(raw)
            if (content.length > 8192) return null
        }
        val text = content.toString()
        return HostFileReferences.extract(text).firstOrNull { offset >= it.start && offset < it.end }?.target
    }
}
