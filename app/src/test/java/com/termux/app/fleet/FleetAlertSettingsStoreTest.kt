package com.termux.app.fleet

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FleetAlertSettingsStoreTest {
    private lateinit var context: Context
    private lateinit var store: FleetAlertSettingsStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        store = FleetAlertSettingsStore(context)
    }

    @Test
    fun defaultsEnableAllSixCategoriesWithoutPause() {
        val value = store.load()
        FleetAlertCategory.entries.forEach { assertTrue(it.canonicalId, value.isEnabled(it)) }
        assertNull(value.pauseUntilEpochMillis)
        assertFalse(value.isPaused(NOW))
    }

    @Test
    fun categoryChoicesAndPauseDeadlineRoundTripWithoutEventContent() {
        FleetAlertCategory.entries.forEach { store.setCategory(it, enabled = false) }
        val paused = store.pauseForOneHour(NOW)
        assertEquals(NOW + FLEET_ALERT_PAUSE_DURATION_MILLIS, paused.pauseUntilEpochMillis)
        assertTrue(paused.isPaused(NOW))

        val restored = FleetAlertSettingsStore(context).load()
        FleetAlertCategory.entries.forEach { assertFalse(it.canonicalId, restored.isEnabled(it)) }
        assertEquals(paused.pauseUntilEpochMillis, restored.pauseUntilEpochMillis)
        assertEquals(
            setOf(
                "hard_limits",
                "delivery_failures",
                "delivery_success",
                "host_state",
                "version_drift",
                "pairing",
                "pause_until_epoch_millis"
            ),
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).all.keys
        )

        val resumed = store.resume()
        assertNull(resumed.pauseUntilEpochMillis)
        assertFalse(resumed.isPaused(NOW))
    }

    @Test
    fun malformedPreferenceTypesFailClosedToSafeDefaults() {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("hard_limits", "false")
            .putString("pause_until_epoch_millis", "never")
            .commit()
        val value = store.load()
        assertTrue(value.hardLimits)
        assertNull(value.pauseUntilEpochMillis)
    }

    private companion object {
        const val PREFERENCES = "agent_fleet_alerts_v1"
        const val NOW = 1_786_000_000_000L
    }
}
