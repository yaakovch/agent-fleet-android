package com.termux.app.fleet

/** Every manager instance in this process shares the same installation owner. */
internal object FleetRuntimePreparation {
    val lock = Any()
    @Volatile var active = false
        private set

    fun <T> mutate(operation: () -> T): T = synchronized(lock) {
        val nested = active
        active = true
        try { operation() }
        finally { active = nested }
    }
}
