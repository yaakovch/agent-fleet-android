package com.termux.app.fleet

private val RETRYABLE_FLEET_CODES = setOf("bridge_disconnected", "host_offline", "timeout")

fun isRetryableRepositoryFailure(error: Throwable): Boolean =
    (error as? FleetUnavailableException)?.code in RETRYABLE_FLEET_CODES
