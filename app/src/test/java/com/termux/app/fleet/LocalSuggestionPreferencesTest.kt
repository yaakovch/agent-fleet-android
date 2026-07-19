package com.termux.app.fleet

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalSuggestionPreferencesTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("agent_fleet_local_suggestions", Context.MODE_PRIVATE)

    @Before fun setUp() { preferences.edit().clear().commit() }
    @After fun tearDown() = setUp()

    @Test fun defaultsToOff() {
        assertEquals(LocalSuggestionMode.OFF, LocalSuggestionPreferences.configuredMode(context))
    }

    @Test fun migratesLegacyEnabledToManualWithoutAutomaticInference() {
        preferences.edit().putBoolean(LocalSuggestionPreferences.LEGACY_ENABLED, true).commit()
        assertEquals(LocalSuggestionMode.MANUAL, LocalSuggestionPreferences.configuredMode(context))
    }

    @Test fun persistsAllThreeModesAndRejectsMalformedValues() {
        LocalSuggestionMode.entries.forEach { mode ->
            LocalSuggestionPreferences.setMode(context, mode)
            assertEquals(mode, LocalSuggestionPreferences.configuredMode(context))
        }
        preferences.edit().putString(LocalSuggestionPreferences.MODE, "unexpected").commit()
        assertEquals(LocalSuggestionMode.OFF, LocalSuggestionPreferences.configuredMode(context))
    }
}
