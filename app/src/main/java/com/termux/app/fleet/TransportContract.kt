package com.termux.app.fleet

data class TransportRecovery(
    val title: String,
    val action: String,
    val actionKind: String
)

object TransportContract {
    val recovery: Map<String, TransportRecovery> = mapOf(
        "ENDPOINT_UNSUPPORTED" to TransportRecovery("No compatible host route", "Verify an OpenSSH endpoint for this host", "review"),
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
        ),
        "ENDPOINT_REVERIFY_REQUIRED" to TransportRecovery("Endpoint verification required", "Verify this host before connecting", "review"),
        "ENDPOINT_TRUST_UNAVAILABLE" to TransportRecovery("Host key unavailable", "Retry when the host is reachable", "retry"),
        "HANDSHAKE_TIMEOUT" to TransportRecovery("Host handshake timed out", "Retry the host connection", "retry"),
        "HEARTBEAT_TIMEOUT" to TransportRecovery("Host contact lost", "Retry the host connection", "retry"),
        "SNAPSHOT_TIMEOUT" to TransportRecovery("Session inventory timed out", "Retry session discovery", "retry"),
        "LOCAL_RUNTIME_UNAVAILABLE" to TransportRecovery("Local runtime unavailable", "Repair the built-in runtime", "review"),
        "REGISTRY_INVALID" to TransportRecovery("Fleet configuration unavailable", "Review or restore the last verified configuration", "review")
    )

    private val legacyCodes = mapOf(
        "bridge_disconnected" to "NETWORK_UNREACHABLE",
        "runtime_unavailable" to "LOCAL_RUNTIME_UNAVAILABLE",
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
