package com.termux.app.fleet

import org.junit.Assert.*
import org.junit.Test

class SessionCreationRecoveryTest {
    private fun snapshot(revision: String, status: String = "healthy") = FleetSnapshot(
        revision, "", listOf(FleetHost("gaming", "Gaming", status, "wsl", null, emptySet())),
        emptyList(), emptyList(), emptyList()
    )

    @Test fun staleRejectionRefreshesAndRetriesOnceWithANewKey() {
        val attempts = mutableListOf<Pair<String, String>>()
        val keys = listOf("first", "second").iterator()
        val result = createSessionWithStaleRetry(snapshot("old"), "gaming", { current, key ->
            attempts += current.revision to key
            if (current.revision == "old") throw FleetUnavailableException("changed", "stale_revision")
            current
        }, { snapshot("new") }, { keys.next() })
        assertEquals("new", result.revision)
        assertEquals(listOf("old" to "first", "new" to "second"), attempts)
    }

    @Test fun uncertainTimeoutOfflineAndInvalidRequestsNeverRetry() {
        listOf("unsafe_state", "timeout", "host_offline", "invalid_request").forEach { code ->
            var executions = 0
            var refreshes = 0
            val failure = FleetUnavailableException("failed", code)
            try {
                createSessionWithStaleRetry(snapshot("one"), "gaming", { _, _ -> executions++; throw failure },
                    { refreshes++; snapshot("two") })
                fail("Expected $code")
            } catch (error: FleetUnavailableException) { assertSame(failure, error) }
            assertEquals(code, 1, executions)
            assertEquals(code, 0, refreshes)
        }
    }

    @Test fun offlineHostAfterRefreshPreventsTheSecondCreation() {
        var executions = 0
        try {
            createSessionWithStaleRetry(snapshot("one"), "gaming", { _, _ ->
                executions++; throw FleetUnavailableException("changed", "stale_revision")
            }, { snapshot("two", "offline") })
            fail("Expected offline")
        } catch (error: FleetUnavailableException) { assertEquals("host_offline", error.code) }
        assertEquals(1, executions)
    }

    @Test fun repeatedStaleRejectionStopsAfterTwoAttempts() {
        var executions = 0
        try {
            createSessionWithStaleRetry(snapshot("one"), "gaming", { _, _ ->
                executions++; throw FleetUnavailableException("changed", "stale_revision")
            }, { snapshot("two") })
            fail("Expected stale")
        } catch (error: FleetUnavailableException) { assertEquals("stale_revision", error.code) }
        assertEquals(2, executions)
    }
    @Test fun onlyDefiniteRejectionsAllowAnotherManualAttempt() {
        listOf("stale_revision", "host_offline", "invalid_request", "backpressure").forEach {
            assertFalse(sessionCreationMayHaveCompleted(FleetUnavailableException("rejected", it)))
        }
        listOf("unsafe_state", "timeout", "bridge_disconnected", "internal_failure", "").forEach {
            assertTrue(sessionCreationMayHaveCompleted(FleetUnavailableException("unknown", it)))
        }
        assertTrue(sessionCreationMayHaveCompleted(java.io.IOException("disconnected")))
    }

}
