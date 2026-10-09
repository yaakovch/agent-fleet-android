package com.termux.app.fleet

import java.util.UUID

internal fun createSessionWithStaleRetry(
    snapshot: FleetSnapshot,
    hostId: String,
    execute: (FleetSnapshot, String) -> FleetSnapshot,
    refresh: () -> FleetSnapshot,
    newKey: () -> String = { UUID.randomUUID().toString() }
): FleetSnapshot {
    fun create(current: FleetSnapshot): FleetSnapshot {
        if (current.isStale || current.hosts.none { it.id == hostId && it.status in setOf("healthy", "online") }) {
            throw FleetUnavailableException("The selected host is offline. Your selections were kept; refresh and try again.", "host_offline")
        }
        return execute(current, newKey())
    }
    return try {
        create(snapshot)
    } catch (error: FleetUnavailableException) {
        if (error.code != "stale_revision") throw error
        // This rejection confirms that creation did not execute. A changed
        // expectedRevision changes the request hash, so use a new key only here.
        create(refresh())
    }
}

internal fun sessionCreationMayHaveCompleted(error: Throwable): Boolean =
    error !is FleetUnavailableException || error.code !in setOf("stale_revision", "host_offline", "invalid_request", "backpressure", "fleet_loading")
