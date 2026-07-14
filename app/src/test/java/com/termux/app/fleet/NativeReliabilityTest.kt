package com.termux.app.fleet

import com.termux.app.terminal.TermuxTerminalSessionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NativeReliabilityTest {
    @Test
    fun hardLimitDefaultsToOneMinuteAfterReset() {
        val reset = Instant.parse("2026-07-14T08:00:00Z").toEpochMilli()
        assertEquals(reset + 60_000, defaultLimitScheduleTime("2026-07-14T08:00:00Z", reset - 30_000))
    }

    @Test
    fun expiredResetStillSchedulesInTheFuture() {
        val now = Instant.parse("2026-07-14T09:00:00Z").toEpochMilli()
        assertEquals(now + 60_000, defaultLimitScheduleTime("2026-07-14T08:00:00Z", now))
    }

    @Test
    fun routineLegacyToastsAreHiddenForManagedNativeSessions() {
        assertFalse(TermuxTerminalSessionClient.shouldShowRoutineSessionToast(false, true))
        assertFalse(TermuxTerminalSessionClient.shouldShowRoutineSessionToast(true, false))
        assertTrue(TermuxTerminalSessionClient.shouldShowRoutineSessionToast(false, false))
    }
}
