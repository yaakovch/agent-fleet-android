package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ReusableRepositoryBridgeTest {
    private data class FakeBridge(var alive: Boolean = true)

    @Test
    fun reusesLiveBridgeAndClosesItAtBrowserEnd() {
        val closed = mutableListOf<FakeBridge>()
        val owner = ReusableRepositoryBridge<FakeBridge>({ it.alive }, closed::add)
        val first = owner.acquire { FakeBridge() }
        assertSame(first, owner.acquire { FakeBridge() })
        owner.close()
        assertEquals(listOf(first), closed)
    }

    @Test
    fun replacesDeadBridgeAndDiscardsFailedConnection() {
        val closed = mutableListOf<FakeBridge>()
        val owner = ReusableRepositoryBridge<FakeBridge>({ it.alive }, closed::add)
        val first = owner.acquire { FakeBridge() }
        first.alive = false
        val second = owner.acquire { FakeBridge() }
        assertNotSame(first, second)
        assertEquals(listOf(first), closed)
        owner.discard(second)
        val third = owner.acquire { FakeBridge() }
        assertNotSame(second, third)
        assertEquals(listOf(first, second), closed)
    }
}
