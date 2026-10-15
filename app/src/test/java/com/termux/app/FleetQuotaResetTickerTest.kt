package com.termux.app

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class FleetQuotaResetTickerTest {
    @Test fun refreshesInForegroundAndStopsWhenHiddenOrDisposed() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.CREATED
        var time = 120_000L
        val seen = mutableListOf<Long>()
        val ticker = QuotaResetTicker(owner.lifecycle, { time }, { seen.add(it) })
        assertEquals(emptyList<Long>(), seen)
        owner.registry.currentState = Lifecycle.State.STARTED
        assertEquals(listOf(120_000L), seen)
        time += 60_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertEquals(listOf(120_000L, 180_000L), seen)
        owner.registry.currentState = Lifecycle.State.CREATED
        time += 120_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        assertEquals(2, seen.size)
        owner.registry.currentState = Lifecycle.State.STARTED
        assertEquals(300_000L, seen.last())
        ticker.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        owner.registry.currentState = Lifecycle.State.CREATED
        owner.registry.currentState = Lifecycle.State.STARTED
        assertEquals(3, seen.size)
    }
}
