package com.termux.app.fleet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.drawerlayout.widget.DrawerLayout
import com.termux.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AgentFleetSessionLayoutContractTest {
    @Test
    fun drawerOverlaysSessionContentComposerAndTerminalShortcuts() {
        val root = LayoutInflater.from(RuntimeEnvironment.getApplication()).inflate(R.layout.activity_termux, null)
        val drawer = root.findViewById<DrawerLayout>(R.id.drawer_layout)
        val mainContent = drawer.getChildAt(0)
        val drawerContent = root.findViewById<View>(R.id.left_drawer)

        assertEquals(drawerContent, drawer.getChildAt(1))
        assertTrue(mainContent.contains(root.findViewById(R.id.agent_fleet_session_content)))
        assertTrue(mainContent.contains(root.findViewById(R.id.agent_fleet_composer)))
        assertTrue(mainContent.contains(root.findViewById(R.id.terminal_toolbar_view_pager)))
    }

    private fun View.contains(target: View): Boolean = this === target ||
        (this is ViewGroup && (0 until childCount).any { getChildAt(it).contains(target) })
}
