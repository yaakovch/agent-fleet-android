package com.termux.app

import com.termux.app.fleet.FleetPairingReview
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingReviewUiStateTest {
    private val review = FleetPairingReview(
        "pair-1",
        "Phone",
        "Android",
        "phone.tailnet.ts.net",
        "100.64.0.10",
        "{\"id\":\"phone-1\"}"
    )

    @Test
    fun decisionRequiresTheVisibleProposalAndItsExactSnapshotRevision() {
        val ready = PairingReviewUiState(
            requestId = "pair-1",
            review = review,
            snapshotRevision = "revision-1"
        )
        assertEquals(PairingDecisionReadiness.Ready, pairingDecisionReadiness(ready, "pair-1", "revision-1"))
        assertEquals(
            PairingDecisionReadiness.Busy,
            pairingDecisionReadiness(ready.copy(loading = true), "pair-1", "revision-1")
        )
        assertEquals(
            PairingDecisionReadiness.NeedsReview,
            pairingDecisionReadiness(ready.copy(error = "failed"), "pair-1", "revision-1")
        )
        assertEquals(
            PairingDecisionReadiness.NeedsReview,
            pairingDecisionReadiness(ready, "pair-1", "revision-2")
        )
    }
}
