package com.termux.app.fleet

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ClientSupervisorSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun restoreDefault() {
        ClientSupervisorSettings.setUsesSharedControl(context, true)
    }

    @Test
    fun sharedForegroundControlIsDefaultWithLegacyRollbackAvailable() {
        context.getSharedPreferences("agent_fleet_runtime", 0).edit().clear().commit()
        assertTrue(ClientSupervisorSettings.usesSharedControl(context))
        ClientSupervisorSettings.setUsesSharedControl(context, false)
        assertFalse(ClientSupervisorSettings.usesSharedControl(context))
        assertEqualsStopped()
    }

    private fun assertEqualsStopped() {
        assertTrue(FleetControlSupervisor.metrics().currentControlProcesses == 0)
        assertTrue(FleetControlSupervisor.state().phase == "stopped")
    }
}
