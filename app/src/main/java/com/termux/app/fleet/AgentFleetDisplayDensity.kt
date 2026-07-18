package com.termux.app.fleet

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AgentFleetDisplayDensity(
    val nativeBodySp: Int = DEFAULT_NATIVE_BODY_SP,
    val drawerTitleSp: Int = DEFAULT_DRAWER_TITLE_SP,
    val terminalShortcutHeightDp: Int = DEFAULT_TERMINAL_SHORTCUT_HEIGHT_DP
) {
    val nativeMetadataSp: Int get() = (nativeBodySp - 3).coerceAtLeast(11)
    val nativeCodeSp: Int get() = (nativeBodySp - 1).coerceAtLeast(12)
    val nativeHeadingSp: Int get() = (nativeBodySp + 2).coerceAtMost(22)
    val drawerMetadataSp: Int get() = (drawerTitleSp - 3).coerceAtLeast(11)
    val drawerRowHeightDp: Int get() = (46 + (drawerTitleSp - 15) * 2).coerceIn(42, 52)

    fun bounded() = copy(
        nativeBodySp = nativeBodySp.coerceIn(MIN_NATIVE_BODY_SP, MAX_NATIVE_BODY_SP),
        drawerTitleSp = drawerTitleSp.coerceIn(MIN_DRAWER_TITLE_SP, MAX_DRAWER_TITLE_SP),
        terminalShortcutHeightDp = terminalShortcutHeightDp
            .coerceIn(MIN_TERMINAL_SHORTCUT_HEIGHT_DP, MAX_TERMINAL_SHORTCUT_HEIGHT_DP)
            .let { it - (it - MIN_TERMINAL_SHORTCUT_HEIGHT_DP) % 2 }
    )

    companion object {
        const val MIN_NATIVE_BODY_SP = 13
        const val MAX_NATIVE_BODY_SP = 20
        const val DEFAULT_NATIVE_BODY_SP = 15
        const val MIN_DRAWER_TITLE_SP = 13
        const val MAX_DRAWER_TITLE_SP = 18
        const val DEFAULT_DRAWER_TITLE_SP = 15
        const val MIN_TERMINAL_SHORTCUT_HEIGHT_DP = 28
        const val MAX_TERMINAL_SHORTCUT_HEIGHT_DP = 40
        const val DEFAULT_TERMINAL_SHORTCUT_HEIGHT_DP = 30
    }
}

object AgentFleetDisplayDensityStore {
    const val PREFERENCES = "agent_fleet_display_density"
    private const val NATIVE_BODY_SP = "native-body-sp"
    private const val DRAWER_TITLE_SP = "drawer-title-sp"
    private const val TERMINAL_SHORTCUT_HEIGHT_DP = "terminal-shortcut-height-dp"
    private val changes = MutableStateFlow(AgentFleetDisplayDensity())
    @Volatile private var loaded = false

    @Synchronized
    fun observe(context: Context): StateFlow<AgentFleetDisplayDensity> {
        if (!loaded) {
            changes.value = load(context)
            loaded = true
        }
        return changes.asStateFlow()
    }

    fun load(context: Context): AgentFleetDisplayDensity {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return AgentFleetDisplayDensity(
            nativeBodySp = preferences.getInt(NATIVE_BODY_SP, AgentFleetDisplayDensity.DEFAULT_NATIVE_BODY_SP),
            drawerTitleSp = preferences.getInt(DRAWER_TITLE_SP, AgentFleetDisplayDensity.DEFAULT_DRAWER_TITLE_SP),
            terminalShortcutHeightDp = preferences.getInt(
                TERMINAL_SHORTCUT_HEIGHT_DP,
                AgentFleetDisplayDensity.DEFAULT_TERMINAL_SHORTCUT_HEIGHT_DP
            )
        ).bounded()
    }

    fun save(context: Context, value: AgentFleetDisplayDensity) {
        val bounded = value.bounded()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putInt(NATIVE_BODY_SP, bounded.nativeBodySp)
            .putInt(DRAWER_TITLE_SP, bounded.drawerTitleSp)
            .putInt(TERMINAL_SHORTCUT_HEIGHT_DP, bounded.terminalShortcutHeightDp)
            .apply()
        loaded = true
        changes.value = bounded
    }

    fun defaults() = AgentFleetDisplayDensity()

    internal fun resetForTest() {
        loaded = false
        changes.value = AgentFleetDisplayDensity()
    }
}
