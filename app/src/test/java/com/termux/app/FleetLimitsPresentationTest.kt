package com.termux.app

import com.termux.app.fleet.FleetLimitWindow
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class FleetLimitsPresentationTest {
    private val now = Instant.parse("2026-10-15T12:00:00Z").toEpochMilli()
    private fun label(reset: String, minutes: Int = 300, time: Long = now, zone: String = "UTC") =
        quotaResetLabel(FleetLimitWindow(25.0, 75.0, reset, minutes), time, ZoneId.of(zone), Locale.US)

    @Test fun quotaPercentageIsExplicitlyRemainingRatherThanUsed() {
        assertEquals("51% left", quotaRemainingLabel(51.0))
        assertEquals("100% left", quotaRemainingLabel(100.0))
    }

    @Test fun usesActualFiveHourAndWeeklyResetInstants() {
        assertEquals("5h resets in 2h 15m · Thu, 15 Oct 14:15", label("2026-10-15T14:15:00Z"))
        assertEquals("Weekly resets in 3d 4h · Sun, 18 Oct 16:00", label("2026-10-18T16:00:00Z", 10080))
    }

    @Test fun acceptsOffsetsAndFractionsAndUsesLocalCalendar() {
        assertEquals("5h resets in 2h 15m · Thu, 15 Oct 17:15", label("2026-10-15T17:15:00.125+03:00", zone = "Asia/Jerusalem"))
        assertEquals("5h resets in 12h 0m · Fri, 16 Oct 03:00", label("2026-10-16T00:00:00Z", zone = "Asia/Jerusalem"))
    }

    @Test fun missingOrInvalidResetDoesNotInventATime() {
        for (reset in listOf("", "unknown", "2026-99-01T00:00:00Z")) {
            assertEquals("5h reset time unknown", label(reset))
        }
    }

    @Test fun onlyMarksResetDueWhenItsInstantHasArrived() {
        assertEquals("5h resets in <1m · Thu, 15 Oct 12:00", label("2026-10-15T12:00:20Z"))
        assertEquals("5h reset due · Thu, 15 Oct 12:00", label("2026-10-15T12:00:00Z"))
        assertEquals("5h reset due · Wed, 14 Oct 12:00", label("2026-10-14T12:00:00Z"))
    }

    @Test fun countdownAdvancesWithoutMovingTheResetOrChangingUsage() {
        val window = FleetLimitWindow(25.0, 75.0, "2026-10-15T13:00:00Z", 300)
        assertEquals("5h resets in 1h 0m · Thu, 15 Oct 13:00", quotaResetLabel(window, now, ZoneId.of("UTC"), Locale.US))
        assertEquals("5h resets in 59m · Thu, 15 Oct 13:00", quotaResetLabel(window, now + 60_000, ZoneId.of("UTC"), Locale.US))
        assertEquals(75.0, window.remainingPercent, 0.0)
    }

    @Test fun daylightSavingChangeUsesElapsedTimeBetweenInstants() {
        val before = Instant.parse("2026-03-08T06:30:00Z").toEpochMilli()
        assertEquals("5h resets in 1h 0m · Sun, 8 Mar 03:30", label("2026-03-08T07:30:00Z", time = before, zone = "America/New_York"))
    }
}
