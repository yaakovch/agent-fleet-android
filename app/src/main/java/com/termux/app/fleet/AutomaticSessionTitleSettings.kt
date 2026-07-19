package com.termux.app.fleet

import android.content.Context

object AutomaticSessionTitleSettings {
    private const val PREFERENCES = "agent_fleet_privacy"
    private const val KEY = "automatic_session_titles"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        val application = context.applicationContext
        application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
        if (!enabled) {
            DrawerSessionStore(application).clearCachedTitles()
            RecentSessionStore(application).clearCachedTitles()
            FleetSnapshotStore.redactTitles()
        }
        FleetSnapshotStore.refresh(showLoading = false)
    }
}

internal fun bridgeHelpSupportsSessionTitles(exitCode: Int, output: String): Boolean =
    exitCode == 0 && output.lineSequence().any { line -> "--session-titles" in line }

internal fun snapshotBridgeArguments(enabled: Boolean, supported: Boolean = true): List<String> =
    listOf("--snapshot") + if (enabled && supported) listOf("--session-titles") else emptyList()

internal fun stdioBridgeArguments(enabled: Boolean, supported: Boolean = true): List<String> =
    listOf("--stdio") + if (enabled && supported) listOf("--session-titles") else emptyList()
