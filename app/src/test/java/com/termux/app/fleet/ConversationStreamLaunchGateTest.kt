package com.termux.app.fleet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStreamLaunchGateTest {
    @Test
    fun repeatedStartWhileLaunchIsPendingIsRejected() {
        val gate = ConversationStreamLaunchGate()
        val first = gate.begin(eligible = true)

        assertNotNull(first)
        assertNull(gate.begin(eligible = true))
        assertTrue(gate.promote(requireNotNull(first), eligible = true) {})
        assertNull(gate.begin(eligible = true))

        gate.finish(first)
        assertNotNull(gate.begin(eligible = true))
    }

    @Test
    fun cancelledSlowLaunchCannotReplaceOrClearNewLaunch() {
        val gate = ConversationStreamLaunchGate()
        val stale = requireNotNull(gate.begin(eligible = true))
        gate.cancel()
        val current = requireNotNull(gate.begin(eligible = true))

        assertFalse(gate.promote(stale, eligible = true) {})
        gate.finish(stale)
        assertNull(gate.begin(eligible = true))

        gate.finish(current)
        assertNotNull(gate.begin(eligible = true))
    }

    @Test
    fun processRegistrationAndCancellationShareTheGate() {
        val gate = ConversationStreamLaunchGate()
        val ticket = requireNotNull(gate.begin(eligible = true))
        var registered = false
        var cancelledRegisteredProcess = false

        assertTrue(gate.promote(ticket, eligible = true) { registered = true })
        gate.cancel {
            cancelledRegisteredProcess = registered
            registered = false
        }

        assertTrue(cancelledRegisteredProcess)
        assertFalse(registered)
    }

    @Test
    fun ineligiblePromotionReleasesPendingLaunch() {
        val gate = ConversationStreamLaunchGate()
        val ticket = requireNotNull(gate.begin(eligible = true))

        assertFalse(gate.promote(ticket, eligible = false) {})
        assertNotNull(gate.begin(eligible = true))
    }
}
