package com.termux.app

import android.content.Context
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import com.termux.R
import com.termux.view.TerminalView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentFleetIdentityContractTest {
    @Suppress("DEPRECATION")
    @Test
    fun usesPermanentIdentityAndExposesOnlyFleetEntryPoints() {
        val context: Context = RuntimeEnvironment.getApplication()
        assertEquals("com.yaakovch.fleet", context.packageName)
        val manager = context.packageManager
        val info = manager.getPackageInfo(
            context.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_PROVIDERS or PackageManager.GET_SERVICES or
                PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA
        )
        assertEquals(1066, info.longVersionCode)
        assertEquals("0.118.4-agentfleet.64", info.versionName)
        assertNull(info.sharedUserId)
        val exportedActivities = info.activities.orEmpty().filter { it.exported && it.name.startsWith("com.termux.") }.associateBy { it.name }
        assertEquals(
            setOf("com.termux.app.AgentFleetActivity", "com.termux.app.migration.AgentFleetMigrationActivity"),
            exportedActivities.keys
        )
        assertEquals(
            "com.yaakovch.fleet.permission.MIGRATION",
            exportedActivities.getValue("com.termux.app.migration.AgentFleetMigrationActivity").permission
        )
        assertTrue(info.providers.orEmpty().none { it.exported })
        assertTrue(info.services.orEmpty().none { it.exported })
        assertFalse(info.requestedPermissions.orEmpty().any { it.contains("RUN_COMMAND") })
        assertTrue(
            info.requestedPermissions.orEmpty().toSet().intersect(setOf(
                "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.READ_LOGS", "android.permission.DUMP", "android.permission.WRITE_SECURE_SETTINGS",
                "android.permission.PACKAGE_USAGE_STATS"
            )).isEmpty()
        )
        assertNotNull(context.getDrawable(R.mipmap.ic_agent_fleet))
        assertNotNull(context.getDrawable(R.mipmap.ic_agent_fleet_round))
        assertEquals(0xff16191f.toInt(), context.getColor(R.color.agent_fleet_icon_tile))
        assertEquals(0xff2dd4bf.toInt(), context.getColor(R.color.agent_fleet_icon_teal))
        assertEquals(0xfffb923c.toInt(), context.getColor(R.color.agent_fleet_icon_orange))
    }

    @Test
    fun terminalScrollbackUsesTheLiveRendererWithOnlySessionChromeOverlay() {
        val context: Context = RuntimeEnvironment.getApplication()
        val root = LayoutInflater.from(context).inflate(R.layout.activity_termux, null)
        val content = root.findViewById<ViewGroup>(R.id.agent_fleet_session_content)
        val composeOverlays = (0 until content.childCount)
            .map(content::getChildAt)
            .filterIsInstance<ComposeView>()
            .map { it.id }

        assertEquals(
            listOf(R.id.agent_fleet_native_session, R.id.agent_fleet_terminal_chrome),
            composeOverlays
        )
        val terminal = content.findViewById<TerminalView>(R.id.terminal_view)
        val terminalChrome = content.findViewById<View>(R.id.agent_fleet_terminal_chrome)
        assertNotNull(terminal)
        assertEquals(View.VISIBLE, terminal.visibility)
        assertEquals(1f, terminal.alpha)
        assertEquals(
            context.resources.getDimensionPixelSize(R.dimen.agent_fleet_terminal_chrome_height),
            terminalChrome.layoutParams.height
        )
        assertTrue(TerminalView::class.java.methods.any { it.name == "setLocalScrollback" })
        assertTrue(TerminalView::class.java.methods.any { it.name == "isLocalScrollbackActive" })
    }
}
