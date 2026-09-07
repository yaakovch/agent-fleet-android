package com.termux.app.fleet

private const val CACHED_SESSION_DISPLAY_MS = 5 * 60_000L

/** The cache is presentation state; only fresh host inventory permits actions. */
fun staleFleetSnapshot(previous: FleetSnapshot, errorCode: String): FleetSnapshot = previous.copy(
    isStale = true,
    hosts = previous.hosts.map { it.copy(status = "offline", errorCode = errorCode) },
    physicalHosts = previous.physicalHosts.map { it.copy(status = "offline", errorCode = errorCode) },
    endpoints = previous.endpoints.map { it.copy(status = "offline", errorCode = errorCode) },
    executionTargets = previous.executionTargets.map { it.copy(status = "unknown") }
)

fun reconcileFleetSnapshot(previous: FleetSnapshot?, fresh: FleetSnapshot, now: Long = System.currentTimeMillis()): FleetSnapshot {
    if (previous == null || previous.fleetId != fresh.fleetId) return fresh
    val unresolved = fresh.hosts.filter { it.status !in setOf("healthy", "online") }.map { it.id }.toSet()
    val freshIds = fresh.sessions.map { it.id }.toSet()
    val retained = previous.sessions.filter { session ->
        now - (previous.cachedSessionSince[session.id] ?: previous.receivedAtMillis) <= CACHED_SESSION_DISPLAY_MS &&
        session.hostId in unresolved && session.id !in freshIds && fresh.physicalHosts.any {
            it.id == session.physicalHostId && session.hostId in it.legacyHostIds
        }
    }
    return fresh.copy(sessions = fresh.sessions + retained, cachedSessionSince = retained.associate {
        it.id to (previous.cachedSessionSince[it.id] ?: previous.receivedAtMillis)
    })
}
