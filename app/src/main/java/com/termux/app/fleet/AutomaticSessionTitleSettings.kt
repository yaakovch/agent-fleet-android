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
        if (ClientSupervisorSettings.usesSharedControl(application)) {
            FleetControlSupervisor.restartForConfigurationChange()
        }
        FleetSnapshotStore.refresh(showLoading = false)
    }
}

internal fun bridgeHelpSupportsSessionTitles(exitCode: Int, output: String): Boolean =
    exitCode == 0 && output.lineSequence().any { line -> "--session-titles" in line }

internal fun bridgeHelpSupportsIdentityGraph(exitCode: Int, output: String): Boolean =
    exitCode == 0 && output.lineSequence().any { line -> "--identity-graph" in line }

internal fun snapshotBridgeArguments(
    titlesEnabled: Boolean,
    titlesSupported: Boolean = true,
    identityGraphSupported: Boolean = true
): List<String> = buildList {
    add("--snapshot")
    // Cold client environments (first SSH handshakes over tailnet, endpoint
    // evidence subprocesses) need more than the 5s default before hosts are
    // ready; the bridge clamps at 30s.
    add("--startup-timeout")
    add("15")
    if (identityGraphSupported) add("--identity-graph")
    if (titlesEnabled && titlesSupported) add("--session-titles")
}

internal fun stdioBridgeArguments(
    titlesEnabled: Boolean,
    titlesSupported: Boolean = true,
    identityGraphSupported: Boolean = true
): List<String> = buildList {
    add("--stdio")
    if (identityGraphSupported) add("--identity-graph")
    if (titlesEnabled && titlesSupported) add("--session-titles")
}
