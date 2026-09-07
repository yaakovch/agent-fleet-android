package com.termux.app.fleet

/** Local bridge failures must never be presented as evidence that the VPN is off. */
internal fun fleetFailureCode(error: Exception): String =
    TransportContract.stableCode((error as? FleetUnavailableException)?.code.orEmpty())
        ?: if (error.message.orEmpty().lineSequence().any { it.startsWith("REGISTRY_INVALID:") }) {
            "REGISTRY_INVALID"
        } else "LOCAL_RUNTIME_UNAVAILABLE"

/** One bounded automatic repair, followed by a fresh production bridge request. */
internal class FleetRecoveryLoader(
    private val fetch: () -> FleetSnapshot,
    private val repairConfiguration: () -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onRepair: () -> Unit = {}
) {
    private var nextRepairAt = Long.MIN_VALUE

    fun retryNow() { nextRepairAt = Long.MIN_VALUE }

    private fun verifiedSnapshot(): FleetSnapshot = fetch().also { snapshot ->
        if (snapshot.hosts.any { it.errorCode == "REGISTRY_INVALID" }) {
            throw FleetUnavailableException("Saved fleet information needs repair.", "REGISTRY_INVALID")
        }
    }

    fun load(): FleetSnapshot {
        try {
            return verifiedSnapshot().also { nextRepairAt = Long.MIN_VALUE }
        } catch (error: Exception) {
            if (fleetFailureCode(error) != "REGISTRY_INVALID" || now() < nextRepairAt) throw error
            nextRepairAt = now() + 60_000
            onRepair()
            try {
                repairConfiguration()
            } catch (_: Exception) {
                throw FleetUnavailableException(
                    "Saved fleet information could not be restored automatically. Open Diagnostics for details, or tap Repair and retry.",
                    "REGISTRY_INVALID"
                )
            }
            return verifiedSnapshot().also { nextRepairAt = Long.MIN_VALUE }
        }
    }
}
