package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelControlRequestGateTest {
    @Test
    fun passivePollingBacksOffUnlessAChangeIsPending() {
        assertEquals(30_000L, modelControlPollDelayMillis(hasPendingChange = false))
        assertEquals(3_000L, modelControlPollDelayMillis(hasPendingChange = true))
    }

    @Test
    fun explicitCatalogRefreshRunsAfterAnActiveBackgroundPoll() {
        val gate = ModelControlRequestGate()
        val background = gate.beginRefresh(includeCatalog = false)
        assertEquals(ModelControlRefreshDisposition.STARTED, background.disposition)

        val explicit = gate.beginRefresh(includeCatalog = true)
        assertEquals(ModelControlRefreshDisposition.QUEUED, explicit.disposition)
        assertNull(explicit.ticket)
        assertTrue(gate.complete(requireNotNull(background.ticket)))

        val catalog = gate.beginRefresh(includeCatalog = true)
        assertEquals(ModelControlRefreshDisposition.STARTED, catalog.disposition)
        assertNotNull(catalog.ticket)
        assertFalse(gate.complete(requireNotNull(catalog.ticket)))
    }

    @Test
    fun repeatedBackgroundPollsDoNotReplaceTheActiveRequest() {
        val gate = ModelControlRequestGate()
        val active = gate.beginRefresh(includeCatalog = false)

        val repeated = gate.beginRefresh(includeCatalog = false)

        assertEquals(ModelControlRefreshDisposition.IGNORED, repeated.disposition)
        assertFalse(gate.complete(requireNotNull(active.ticket)))
    }

    @Test
    fun resetMakesALateCompletionUnableToConsumeANewRequest() {
        val gate = ModelControlRequestGate()
        val stale = requireNotNull(gate.beginRefresh(includeCatalog = false).ticket)
        gate.reset()
        val current = requireNotNull(gate.beginRefresh(includeCatalog = true).ticket)

        assertFalse(gate.complete(stale))
        assertFalse(gate.complete(current))
    }
}
