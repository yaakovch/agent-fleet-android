package com.termux.app.fleet

import android.content.Context

/**
 * Rollback switch for the milestone-3 Android control supervisor. The shared,
 * long-lived foreground bridge is the default; disabling it restores the
 * previous one-process-per-request adapter without changing the wire protocol.
 */
object ClientSupervisorSettings {
    private const val PREFERENCES = "agent_fleet_runtime"
    private const val SHARED_CONTROL_KEY = "shared_control_supervisor_v1"

    fun usesSharedControl(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(SHARED_CONTROL_KEY, true)

    fun setUsesSharedControl(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SHARED_CONTROL_KEY, enabled)
            .apply()
        if (!enabled) FleetControlSupervisor.setForeground(context.applicationContext, false)
    }
}
