package com.termux.app.fleet

import android.widget.Button
import com.termux.shared.terminal.io.extrakeys.ExtraKeysConstants
import com.termux.shared.terminal.io.extrakeys.ExtraKeysInfo
import com.termux.shared.terminal.io.extrakeys.ExtraKeysView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ExtraKeysCompactRowTest {
    @Test
    fun customMatricesAreFlattenedInRowMajorOrder() {
        val view = ExtraKeysView(RuntimeEnvironment.getApplication(), null)
        val info = ExtraKeysInfo(
            "[['ESC','TAB'],['LEFT','RIGHT']]",
            "default",
            ExtraKeysConstants.CONTROL_CHARS_ALIASES
        )

        view.setCompactSingleRow(true)
        view.reload(info)

        assertEquals(1, view.rowCount)
        assertEquals(4, view.columnCount)
        assertEquals(listOf("ESC", "↹", "←", "→"), (0 until view.childCount).map { (view.getChildAt(it) as Button).text.toString() })
    }
}
