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
        val first = maxOf(-screen.activeTranscriptRows, row - 31)
        val last = minOf(emulator.mRows - 1, row + 31)
        val offset = screen.getColumnTextOffset(row, location[0])
        if (offset < 0) return null
        val rows = (first..last).map { current -> HostFileRow(
            screen.getLineTextAt(current).let { if (screen.getLineWrap(current)) it else it.trimEnd(' ') },
            current > -screen.activeTranscriptRows && screen.getLineWrap(current - 1)
        ) }
        return HostFileRowReferences.extract(rows).firstOrNull { it.row == row - first && offset >= it.start && offset < it.end }?.target
    }
}
