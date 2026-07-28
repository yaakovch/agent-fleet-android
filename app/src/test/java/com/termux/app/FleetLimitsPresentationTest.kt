package com.termux.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FleetLimitsPresentationTest {
    @Test
    fun quotaPercentageIsExplicitlyRemainingRatherThanUsed() {
        assertEquals("51% left", quotaRemainingLabel(51.0))
        assertEquals("100% left", quotaRemainingLabel(100.0))
    }
}
