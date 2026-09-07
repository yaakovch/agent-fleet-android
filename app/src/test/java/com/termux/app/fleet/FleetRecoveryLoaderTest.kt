package com.termux.app.fleet

import org.junit.Assert.*
import org.junit.Test

class FleetRecoveryLoaderTest {
    private fun snapshot() = FleetSnapshot("ready", "2026-09-07T00:00:00Z",
        listOf(FleetHost("host", "Host", "healthy", "wsl", null, emptySet())),
        emptyList(), emptyList(), emptyList())

    @Test fun repairedConfigurationIsFollowedByFreshDiscovery() {
        var damaged = true
        var requests = 0
        var repairs = 0
        val loader = FleetRecoveryLoader(
            fetch = {
                requests++
                if (damaged) throw FleetUnavailableException("Invalid saved registry", "REGISTRY_INVALID")
                snapshot()
            },
            repairConfiguration = { repairs++; damaged = false }
        )
        assertEquals("ready", loader.load().revision)
        assertEquals(1, repairs)
        assertEquals(2, requests)
    }

    @Test fun aRunningBridgeReportingConfigurationFailureAlsoRecovers() {
        var damaged = true
        val loader = FleetRecoveryLoader(
            fetch = {
                snapshot().let { value ->
                    if (damaged) value.copy(hosts = value.hosts.map { it.copy(errorCode = "REGISTRY_INVALID", status = "offline") })
                    else value
                }
            },
            repairConfiguration = { damaged = false }
        )
        assertTrue(loader.load().hosts.all { it.errorCode.isBlank() })
    }

    @Test fun unrecoverableConfigurationUsesBoundedAutomaticRetriesAndAnExplicitRetry() {
        var clock = 1L
        var repairs = 0
        val loader = FleetRecoveryLoader(
            fetch = { throw FleetUnavailableException("Invalid saved registry", "REGISTRY_INVALID") },
            repairConfiguration = { repairs++; error("No matching verified artifact") },
            now = { clock }
        )
        repeat(10) {
            val failure = runCatching { loader.load() }.exceptionOrNull() as FleetUnavailableException
            assertEquals("REGISTRY_INVALID", failure.code)
            assertFalse(failure.message.orEmpty().contains("Tailscale"))
            clock += 3_000
        }
        assertEquals(1, repairs)
        clock = 60_001
        assertTrue(runCatching { loader.load() }.isFailure)
        assertEquals(2, repairs)
        loader.retryNow()
        assertTrue(runCatching { loader.load() }.isFailure)
        assertEquals(3, repairs)
    }

    @Test fun networkAndIdentityFailuresDoNotRewriteTheConfiguration() {
        for (code in listOf("NETWORK_UNREACHABLE", "HOST_KEY_CHANGED", "SSH_AUTH_REQUIRED")) {
            val loader = FleetRecoveryLoader(
                fetch = { throw FleetUnavailableException("Host unavailable", code) },
                repairConfiguration = { fail("Network and trust failures must not reinstall host metadata") }
            )
            val failure = runCatching { loader.load() }.exceptionOrNull() as FleetUnavailableException
            assertEquals(code, failure.code)
        }
    }

    @Test fun anUntypedLocalFailureIsNotEvidenceThatTailscaleIsDisconnected() {
        assertEquals("LOCAL_RUNTIME_UNAVAILABLE", fleetFailureCode(java.io.IOException("Process could not start")))
        assertEquals("REGISTRY_INVALID", fleetFailureCode(FleetUnavailableException("REGISTRY_INVALID: saved metadata failed validation")))
    }
}
