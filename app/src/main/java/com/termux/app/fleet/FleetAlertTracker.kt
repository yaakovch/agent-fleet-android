package com.termux.app.fleet

const val MAX_FLEET_ALERT_HOST_STATES = 256
const val MAX_FLEET_ALERT_SCHEDULE_STATES = 500
const val MAX_FLEET_ALERT_ATTENTION_IDS = 500
const val MAX_FLEET_ALERT_PAIRING_STATES = 256
const val MAX_FLEET_ALERTS_PER_UPDATE = 16
const val MAX_CONCRETE_FLEET_ALERTS_PER_UPDATE = MAX_FLEET_ALERTS_PER_UPDATE - 1

class VerifiedExpectedHostRuntimeVersion private constructor(val value: String) {
    companion object {
        private val VERSION = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$")

        /** Only call after the expected release/runtime source has passed signature and digest verification. */
        fun fromVerifiedSource(value: String): VerifiedExpectedHostRuntimeVersion {
            require(VERSION.matches(value)) { "Verified expected host runtime version is invalid" }
            return VerifiedExpectedHostRuntimeVersion(value)
        }
    }
}

sealed interface FleetAlertTarget {
    data object Dashboard : FleetAlertTarget
    data class Session(val id: String) : FleetAlertTarget
    data class Host(val id: String) : FleetAlertTarget
    data class PairingReview(val requestId: String) : FleetAlertTarget
}

data class FleetAlert(
    val category: FleetAlertCategory?,
    val title: String,
    val body: String,
    val target: FleetAlertTarget
)

data class FleetAlertTrackerState(
    val hosts: Int,
    val schedules: Int,
    val versionDrifts: Int,
    val recentAttention: Int,
    val recentPairing: Int
)

/**
 * Converts successive authoritative snapshots into one-shot, foreground in-app alerts.
 * Callers must invoke it only from the foreground snapshot observer. Every observed snapshot
 * advances state even while paused or a category is disabled, preventing replay on resume.
 */
class FleetAlertTracker {
    private var baselineReady = false
    private var hostStates = linkedMapOf<String, String>()
    private var scheduleStates = linkedMapOf<String, String>()
    private var versionDrifts = linkedMapOf<String, String>()
    private var attentionIds = linkedSetOf<String>()
    private var pairingStates = linkedMapOf<String, String>()

    fun process(
        snapshot: FleetSnapshot,
        settings: FleetAlertSettings,
        nowEpochMillis: Long,
        expectedHostRuntimeVersion: VerifiedExpectedHostRuntimeVersion? = null
    ): List<FleetAlert> {
        if (!baselineReady) {
            observeBaseline(snapshot, expectedHostRuntimeVersion)
            baselineReady = true
            return emptyList()
        }

        val candidates = mutableListOf<FleetAlert>()

        val nextAttentionIds = linkedSetOf<String>()
        snapshot.attention.take(MAX_FLEET_ALERT_ATTENTION_IDS).forEach { attention ->
            if (!nextAttentionIds.add(attention.id)) return@forEach
            if (attention.id in attentionIds) return@forEach
            if (
                attention.kind == "hard-limit" &&
                attention.state in ACTIVE_ATTENTION_STATES &&
                settings.hardLimits
            ) {
                candidates += FleetAlert(
                    category = FleetAlertCategory.HardLimits,
                    title = "${fleetAlertAgentLabel(attention.agent)} usage limit detected",
                    body = buildString {
                        append(attention.hostId)
                        if (attention.resetAt != null) append(" · resets ").append(attention.resetAt)
                    },
                    target = FleetAlertTarget.Session(attention.sessionId)
                )
            }
        }
        attentionIds = nextAttentionIds

        val nextHostStates = linkedMapOf<String, String>()
        snapshot.hosts.take(MAX_FLEET_ALERT_HOST_STATES).forEach { host ->
            if (nextHostStates.containsKey(host.id)) return@forEach
            nextHostStates[host.id] = host.status
            val previous = hostStates[host.id]
            if (!settings.hostState || previous == null || previous == host.status) return@forEach
            if (host.status == "offline") {
                candidates += FleetAlert(
                    category = FleetAlertCategory.HostState,
                    title = "${host.name} is offline",
                    body = "The host is unavailable and live actions are paused.",
                    target = FleetAlertTarget.Host(host.id)
                )
            } else if (previous == "offline" && host.status == "healthy") {
                candidates += FleetAlert(
                    category = FleetAlertCategory.HostState,
                    title = "${host.name} recovered",
                    body = "The host is connected and live actions are available again.",
                    target = FleetAlertTarget.Host(host.id)
                )
            }
        }
        hostStates = nextHostStates

        val nextScheduleStates = linkedMapOf<String, String>()
        snapshot.schedules.take(MAX_FLEET_ALERT_SCHEDULE_STATES).forEach { schedule ->
            if (nextScheduleStates.containsKey(schedule.id)) return@forEach
            nextScheduleStates[schedule.id] = schedule.status
            val previous = scheduleStates[schedule.id]
            if (previous == null || previous == schedule.status || schedule.status == "pending") return@forEach
            if (schedule.status == "delivered" && settings.deliverySuccess) {
                candidates += FleetAlert(
                    category = FleetAlertCategory.DeliverySuccess,
                    title = "Scheduled continue delivered",
                    body = "${schedule.hostId} · Scheduled message",
                    target = FleetAlertTarget.Session(schedule.sessionId)
                )
            } else if (schedule.status in FAILED_SCHEDULE_STATES && settings.deliveryFailures) {
                candidates += FleetAlert(
                    category = FleetAlertCategory.DeliveryFailures,
                    title = "Scheduled continue ${schedule.status}",
                    body = fleetDeliveryFailureBody(schedule.outcomeCode),
                    target = FleetAlertTarget.Session(schedule.sessionId)
                )
            }
        }
        scheduleStates = nextScheduleStates

        val newlyDrifted = observeVersionDrift(snapshot, expectedHostRuntimeVersion)
        if (settings.versionDrift && newlyDrifted.isNotEmpty() && expectedHostRuntimeVersion != null) {
            candidates += versionDriftAlert(newlyDrifted, expectedHostRuntimeVersion.value)
        }

        val nextPairingStates = linkedMapOf<String, String>()
        val newPairingRequests = mutableListOf<FleetPairingRequest>()
        snapshot.pairingRequests.take(MAX_FLEET_ALERT_PAIRING_STATES).forEach { request ->
            if (nextPairingStates.containsKey(request.id)) return@forEach
            nextPairingStates[request.id] = request.status
            if (request.status == "awaiting-review" && pairingStates[request.id] != "awaiting-review") {
                newPairingRequests += request
            }
        }
        pairingStates = nextPairingStates
        if (settings.pairing && newPairingRequests.isNotEmpty()) {
            candidates += pairingAlert(newPairingRequests)
        }

        if (settings.isPaused(nowEpochMillis)) return emptyList()
        return boundFleetAlerts(candidates)
    }

    fun state(): FleetAlertTrackerState = FleetAlertTrackerState(
        hosts = hostStates.size,
        schedules = scheduleStates.size,
        versionDrifts = versionDrifts.size,
        recentAttention = attentionIds.size,
        recentPairing = pairingStates.size
    )

    private fun observeBaseline(
        snapshot: FleetSnapshot,
        expectedHostRuntimeVersion: VerifiedExpectedHostRuntimeVersion?
    ) {
        hostStates = linkedMapOf<String, String>().apply {
            snapshot.hosts.take(MAX_FLEET_ALERT_HOST_STATES).forEach { put(it.id, it.status) }
        }
        scheduleStates = linkedMapOf<String, String>().apply {
            snapshot.schedules.take(MAX_FLEET_ALERT_SCHEDULE_STATES).forEach { put(it.id, it.status) }
        }
        attentionIds = snapshot.attention.take(MAX_FLEET_ALERT_ATTENTION_IDS).mapTo(linkedSetOf()) { it.id }
        pairingStates = snapshot.pairingRequests.take(MAX_FLEET_ALERT_PAIRING_STATES)
            .associateTo(linkedMapOf()) { it.id to it.status }
        observeVersionDrift(snapshot, expectedHostRuntimeVersion)
    }

    private fun observeVersionDrift(
        snapshot: FleetSnapshot,
        expectedHostRuntimeVersion: VerifiedExpectedHostRuntimeVersion?
    ): List<FleetHost> {
        val next = linkedMapOf<String, String>()
        val seenHostIds = mutableSetOf<String>()
        val newlyDrifted = mutableListOf<FleetHost>()
        val expectedVersion = expectedHostRuntimeVersion?.value
        if (expectedVersion != null) {
            snapshot.hosts.take(MAX_FLEET_ALERT_HOST_STATES).forEach { host ->
                if (!seenHostIds.add(host.id)) return@forEach
                if (host.wtmuxVersion.isBlank() || host.wtmuxVersion == "unknown" || host.wtmuxVersion == expectedVersion) {
                    return@forEach
                }
                val fingerprint = "$expectedVersion\u0000${host.wtmuxVersion}"
                next[host.id] = fingerprint
                if (versionDrifts[host.id] != fingerprint) newlyDrifted += host
            }
        }
        versionDrifts = next
        return newlyDrifted
    }
}

private fun versionDriftAlert(hosts: List<FleetHost>, expectedVersion: String): FleetAlert {
    if (hosts.size == 1) {
        val host = hosts.single()
        return FleetAlert(
            category = FleetAlertCategory.VersionDrift,
            title = "${host.name} runtime version drift",
            body = "Installed ${host.wtmuxVersion} · expected $expectedVersion",
            target = FleetAlertTarget.Host(host.id)
        )
    }
    val names = hosts.take(3).joinToString(", ") { it.name }
    val remainder = if (hosts.size > 3) " and ${hosts.size - 3} more" else ""
    return FleetAlert(
        category = FleetAlertCategory.VersionDrift,
        title = "${hosts.size} hosts have runtime version drift",
        body = "$names$remainder · expected $expectedVersion",
        target = FleetAlertTarget.Dashboard
    )
}

private fun pairingAlert(requests: List<FleetPairingRequest>): FleetAlert {
    if (requests.size == 1) {
        val request = requests.single()
        return FleetAlert(
            category = FleetAlertCategory.Pairing,
            title = "Pairing request from ${request.deviceName}",
            body = "${request.platform} · review the verified device proposal",
            target = FleetAlertTarget.PairingReview(request.id)
        )
    }
    return FleetAlert(
        category = FleetAlertCategory.Pairing,
        title = "${requests.size} pairing requests need review",
        body = "Open Fleet to review the verified device proposals.",
        target = FleetAlertTarget.Dashboard
    )
}

private fun boundFleetAlerts(candidates: List<FleetAlert>): List<FleetAlert> {
    if (candidates.size <= MAX_FLEET_ALERTS_PER_UPDATE) return candidates
    val visible = candidates.take(MAX_CONCRETE_FLEET_ALERTS_PER_UPDATE).toMutableList()
    visible += FleetAlert(
        category = null,
        title = "${candidates.size - visible.size} more fleet changes",
        body = "Open Agent Fleet to review the remaining changes.",
        target = FleetAlertTarget.Dashboard
    )
    return visible
}

private fun fleetAlertAgentLabel(agent: String): String = when (agent) {
    "codex" -> "Codex"
    "claude" -> "Claude"
    else -> "Coding agent"
}

private fun fleetDeliveryFailureBody(outcomeCode: String): String = when (outcomeCode) {
    "delivery_lock_failed" -> "The delivery lock could not be acquired."
    "tmux_delivery_failed" -> "The session delivery command failed."
    "boot_changed" -> "The host restarted before delivery completed."
    "delivery_outcome_unknown" -> "The delivery outcome could not be verified."
    else -> "The scheduled action could not be delivered."
}

private val ACTIVE_ATTENTION_STATES = setOf("detected", "offering", "offered")
private val FAILED_SCHEDULE_STATES = setOf("failed", "interrupted")
