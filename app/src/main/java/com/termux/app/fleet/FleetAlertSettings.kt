package com.termux.app.fleet

import android.content.Context

const val FLEET_ALERT_PAUSE_DURATION_MILLIS = 60L * 60L * 1_000L

enum class FleetAlertCategory(val canonicalId: String) {
    HardLimits("hardLimits"),
    DeliveryFailures("deliveryFailures"),
    DeliverySuccess("deliverySuccess"),
    HostState("hostState"),
    VersionDrift("versionDrift"),
    Pairing("pairing");

    companion object {
        fun fromCanonicalId(value: String): FleetAlertCategory? = entries.firstOrNull { it.canonicalId == value }
    }
}

data class FleetAlertSettings(
    val hardLimits: Boolean = true,
    val deliveryFailures: Boolean = true,
    val deliverySuccess: Boolean = true,
    val hostState: Boolean = true,
    val versionDrift: Boolean = true,
    val pairing: Boolean = true,
    val pauseUntilEpochMillis: Long? = null
) {
    fun isEnabled(category: FleetAlertCategory): Boolean = when (category) {
        FleetAlertCategory.HardLimits -> hardLimits
        FleetAlertCategory.DeliveryFailures -> deliveryFailures
        FleetAlertCategory.DeliverySuccess -> deliverySuccess
        FleetAlertCategory.HostState -> hostState
        FleetAlertCategory.VersionDrift -> versionDrift
        FleetAlertCategory.Pairing -> pairing
    }

    fun isPaused(nowEpochMillis: Long): Boolean = pauseUntilEpochMillis?.let { it > nowEpochMillis } == true
}

/** Persists only alert choices and the pause deadline; observed event content never reaches preferences. */
class FleetAlertSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): FleetAlertSettings = FleetAlertSettings(
        hardLimits = boolean(KEY_HARD_LIMITS),
        deliveryFailures = boolean(KEY_DELIVERY_FAILURES),
        deliverySuccess = boolean(KEY_DELIVERY_SUCCESS),
        hostState = boolean(KEY_HOST_STATE),
        versionDrift = boolean(KEY_VERSION_DRIFT),
        pairing = boolean(KEY_PAIRING),
        pauseUntilEpochMillis = (preferences.all[KEY_PAUSE_UNTIL] as? Number)?.toLong()?.takeIf { it > 0L }
    )

    fun save(value: FleetAlertSettings) {
        preferences.edit()
            .putBoolean(KEY_HARD_LIMITS, value.hardLimits)
            .putBoolean(KEY_DELIVERY_FAILURES, value.deliveryFailures)
            .putBoolean(KEY_DELIVERY_SUCCESS, value.deliverySuccess)
            .putBoolean(KEY_HOST_STATE, value.hostState)
            .putBoolean(KEY_VERSION_DRIFT, value.versionDrift)
            .putBoolean(KEY_PAIRING, value.pairing)
            .also { editor ->
                val pauseUntil = value.pauseUntilEpochMillis?.takeIf { it > 0L }
                if (pauseUntil == null) editor.remove(KEY_PAUSE_UNTIL) else editor.putLong(KEY_PAUSE_UNTIL, pauseUntil)
            }
            .apply()
    }

    fun setCategory(category: FleetAlertCategory, enabled: Boolean): FleetAlertSettings {
        val current = load()
        val updated = when (category) {
            FleetAlertCategory.HardLimits -> current.copy(hardLimits = enabled)
            FleetAlertCategory.DeliveryFailures -> current.copy(deliveryFailures = enabled)
            FleetAlertCategory.DeliverySuccess -> current.copy(deliverySuccess = enabled)
            FleetAlertCategory.HostState -> current.copy(hostState = enabled)
            FleetAlertCategory.VersionDrift -> current.copy(versionDrift = enabled)
            FleetAlertCategory.Pairing -> current.copy(pairing = enabled)
        }
        save(updated)
        return updated
    }

    fun pauseForOneHour(nowEpochMillis: Long): FleetAlertSettings {
        val until = if (nowEpochMillis > Long.MAX_VALUE - FLEET_ALERT_PAUSE_DURATION_MILLIS) {
            Long.MAX_VALUE
        } else {
            nowEpochMillis + FLEET_ALERT_PAUSE_DURATION_MILLIS
        }
        return load().copy(pauseUntilEpochMillis = until).also(::save)
    }

    fun resume(): FleetAlertSettings = load().copy(pauseUntilEpochMillis = null).also(::save)

    private fun boolean(key: String): Boolean = preferences.all[key] as? Boolean ?: true

    private companion object {
        const val PREFERENCES = "agent_fleet_alerts_v1"
        const val KEY_HARD_LIMITS = "hard_limits"
        const val KEY_DELIVERY_FAILURES = "delivery_failures"
        const val KEY_DELIVERY_SUCCESS = "delivery_success"
        const val KEY_HOST_STATE = "host_state"
        const val KEY_VERSION_DRIFT = "version_drift"
        const val KEY_PAIRING = "pairing"
        const val KEY_PAUSE_UNTIL = "pause_until_epoch_millis"
    }
}
