package com.termux.app.fleet

data class HostRuntimeRecovery(
    val title: String,
    val action: String,
    val actionKind: ActionKind
) {
    enum class ActionKind { Retry, Refresh, Review, Terminal, Rollback }
}

object HostRuntimeContract {
    val controlErrorCodes = setOf(
        "invalid_request",
        "not_found",
        "conflict",
        "stale_revision",
        "unsupported",
        "unavailable",
        "timeout",
        "resource_limit",
        "tmux_unavailable",
        "helper_unavailable",
        "unsafe_state",
        "internal_failure"
    )

    val recovery = mapOf(
        "invalid_request" to HostRuntimeRecovery(
            "Host request rejected", "Review the request", HostRuntimeRecovery.ActionKind.Review
        ),
        "not_found" to HostRuntimeRecovery(
            "Host resource not found", "Refresh host state", HostRuntimeRecovery.ActionKind.Refresh
        ),
        "conflict" to HostRuntimeRecovery(
            "Host state changed", "Refresh host state", HostRuntimeRecovery.ActionKind.Refresh
        ),
        "stale_revision" to HostRuntimeRecovery(
            "Host state changed", "Refresh host state", HostRuntimeRecovery.ActionKind.Refresh
        ),
        "unsupported" to HostRuntimeRecovery(
            "Host capability unsupported", "Update or roll back", HostRuntimeRecovery.ActionKind.Rollback
        ),
        "unavailable" to HostRuntimeRecovery(
            "Host capability unavailable", "Retry host capability", HostRuntimeRecovery.ActionKind.Retry
        ),
        "timeout" to HostRuntimeRecovery(
            "Host operation timed out", "Retry operation", HostRuntimeRecovery.ActionKind.Retry
        ),
        "resource_limit" to HostRuntimeRecovery(
            "Host operation limit reached", "Retry after current work", HostRuntimeRecovery.ActionKind.Retry
        ),
        "tmux_unavailable" to HostRuntimeRecovery(
            "Host session service unavailable", "Repair tmux", HostRuntimeRecovery.ActionKind.Review
        ),
        "helper_unavailable" to HostRuntimeRecovery(
            "Host runtime incomplete", "Repair the host runtime", HostRuntimeRecovery.ActionKind.Rollback
        ),
        "unsafe_state" to HostRuntimeRecovery(
            "Host state could not be verified", "Refresh host state", HostRuntimeRecovery.ActionKind.Refresh
        ),
        "internal_failure" to HostRuntimeRecovery(
            "Host operation failed safely", "Retry host capability", HostRuntimeRecovery.ActionKind.Retry
        ),
        "SESSION_UNAVAILABLE" to HostRuntimeRecovery(
            "Session unavailable", "Refresh sessions", HostRuntimeRecovery.ActionKind.Refresh
        ),
        "PROVIDER_UNAVAILABLE" to HostRuntimeRecovery(
            "Native provider unavailable", "Open Terminal", HostRuntimeRecovery.ActionKind.Terminal
        ),
        "TRANSFER_REJECTED" to HostRuntimeRecovery(
            "Transfer rejected", "Review the transfer", HostRuntimeRecovery.ActionKind.Review
        ),
        "REPOSITORY_UNAVAILABLE" to HostRuntimeRecovery(
            "Repository unavailable", "Refresh repository", HostRuntimeRecovery.ActionKind.Refresh
        )
    )

    fun recoveryFor(code: String): HostRuntimeRecovery? = recovery[code]
}
