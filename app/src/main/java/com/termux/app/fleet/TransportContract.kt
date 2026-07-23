package com.termux.app.fleet

data class TransportRecovery(
    val title: String,
    val action: String,
    val actionKind: String
)

object TransportContract {
    val recovery: Map<String, TransportRecovery> = mapOf(
        "NETWORK_UNREACHABLE" to TransportRecovery(
            "Private network unavailable", "Retry when Tailscale is connected", "retry"
        ),
        "DNS_UNAVAILABLE" to TransportRecovery(
            "Endpoint name unavailable", "Retry endpoint lookup", "retry"
        ),
        "SSH_AUTH_REQUIRED" to TransportRecovery(
            "SSH authentication required", "Review SSH access", "review"
        ),
        "SSH_CHECK_REQUIRED" to TransportRecovery(
            "SSH approval required", "Complete browser approval", "review"
        ),
        "HOST_KEY_CHANGED" to TransportRecovery(
            "Endpoint identity changed", "Re-verify before connecting", "review"
        ),
        "TTY_UNAVAILABLE" to TransportRecovery(
            "Terminal unavailable on this SSH engine", "Select OpenSSH", "review"
        ),
        "HOST_RUNTIME_MISSING" to TransportRecovery(
            "Host runtime missing", "Repair the host runtime", "review"
        ),
        "HOST_RUNTIME_INCOMPATIBLE" to TransportRecovery(
            "Host runtime incompatible", "Update or roll back", "rollback"
        ),
        "TMUX_UNAVAILABLE" to TransportRecovery(
            "tmux unavailable", "Repair the host session service", "review"
        )
    )

    private val legacyCodes = mapOf(
        "connection_failed" to "NETWORK_UNREACHABLE",
        "heartbeat_timeout" to "NETWORK_UNREACHABLE",
        "unreachable" to "NETWORK_UNREACHABLE",
        "auth_failure" to "SSH_AUTH_REQUIRED",
        "protocol_error" to "HOST_RUNTIME_INCOMPATIBLE",
        "missing_host_runtime" to "HOST_RUNTIME_MISSING"
    )

    fun stableCode(value: String): String? = when {
        value in recovery -> value
        else -> legacyCodes[value]
    }

    fun recoveryFor(value: String): TransportRecovery? =
        stableCode(value)?.let(recovery::get)
}

fun selectedTransportEndpoint(snapshot: FleetSnapshot, host: FleetPhysicalHost): FleetEndpoint? {
    host.endpointIds.forEach { endpointId ->
        snapshot.endpoints.firstOrNull { it.id == endpointId }?.let { return it }
    }
    return snapshot.endpoints.firstOrNull { it.physicalHostId == host.id }
}

fun transportEndpointLabel(endpoint: FleetEndpoint?): String {
    if (endpoint == null) return "No selected endpoint"
    val engine = if (endpoint.sshEngine == "openssh") "OpenSSH" else "Tailscale SSH"
    val network = when (endpoint.network) {
        "tailnet" -> "Tailnet"
        "direct" -> "Direct SSH"
        else -> "Local"
    }
    return "$engine over $network"
}

fun transportRecoveryDetail(snapshot: FleetSnapshot, host: FleetPhysicalHost): String? {
    val endpoint = selectedTransportEndpoint(snapshot, host)
    TransportContract.recoveryFor(endpoint?.errorCode.orEmpty().ifBlank { host.errorCode })?.let {
        return "${it.title} · ${it.action}"
    }
    if (endpoint != null && endpoint.identityState != "verified") {
        return "${transportEndpointLabel(endpoint)} · Endpoint identity needs verification"
    }
    return if (host.status == "healthy") null else "${host.status} · Showing last known data"
}
