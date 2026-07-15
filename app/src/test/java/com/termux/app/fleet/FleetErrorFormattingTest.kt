package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Test

class FleetErrorFormattingTest {
    @Test
    fun tracebackShowsItsUsefulFinalCause() {
        val error = """
            Traceback (most recent call last):
              File "transfer.py", line 10, in download
            PermissionError: Android Downloads does not support this operation
        """.trimIndent()

        assertEquals(
            "PermissionError: Android Downloads does not support this operation",
            conciseFleetError(error)
        )
    }

    @Test
    fun wtmuxFileErrorWinsOverEarlierWarnings() {
        assertEquals(
            "wtmux file: Downloads folder is not writable",
            conciseFleetError("[wtmux][warn] reconnecting\nwtmux file: Downloads folder is not writable")
        )
    }

    @Test
    fun blankErrorUsesFallback() {
        assertEquals("Download failed.", conciseFleetError("\n", "Download failed."))
    }
}
