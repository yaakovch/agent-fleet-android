package com.termux.app

import android.content.Context
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentFleetLegacyIdentityContractTest {
    @Suppress("DEPRECATION")
    @Test
    fun retainsTermuxIdentityWithAnExplicitLegacyLabelAndSignedBridge() {
        val context: Context = RuntimeEnvironment.getApplication()
        assertEquals("com.termux", context.packageName)
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA
        )
        assertEquals(1043, info.longVersionCode)
        assertEquals("0.118.4-agentfleet.41-legacy", info.versionName)
        assertEquals("com.termux", info.sharedUserId)
        val applicationInfo = requireNotNull(info.applicationInfo)
        assertEquals("Agent Fleet Legacy", context.packageManager.getApplicationLabel(applicationInfo).toString())
        val migration = info.activities.orEmpty().single { it.name == "com.termux.app.migration.AgentFleetMigrationActivity" }
        assertTrue(migration.exported)
        assertEquals("com.termux.permission.AGENT_FLEET_MIGRATION", migration.permission)
        assertNotNull(context.getDrawable(applicationInfo.icon))
    }
}
