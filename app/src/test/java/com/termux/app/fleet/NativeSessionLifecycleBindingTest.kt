package com.termux.app.fleet

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeSessionLifecycleBindingTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle = registry
    }

    @Test
    fun stoppedRetainedWorkspaceStopsAndRestartsItsController() {
        val owner = Owner()
        val binding = NativeSessionLifecycleBinding()
        var starts = 0
        var stops = 0
        var closes = 0
        owner.lifecycle.addObserver(binding)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        binding.attach(
            owner.lifecycle.currentState,
            start = { starts++ },
            stop = { stops++ },
            close = { closes++ }
        )
        assertEquals(1, starts)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertEquals(1, stops)
        assertEquals(0, closes)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(2, starts)

        binding.detach()
        binding.detach()
        assertEquals(1, closes)
    }

    @Test
    fun controllerAttachedWhileStoppedWaitsForForeground() {
        val owner = Owner()
        val binding = NativeSessionLifecycleBinding()
        var starts = 0
        var stops = 0
        owner.lifecycle.addObserver(binding)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        binding.attach(
            owner.lifecycle.currentState,
            start = { starts++ },
            stop = { stops++ },
            close = {}
        )
        assertEquals(0, starts)
        assertEquals(0, stops)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertEquals(1, starts)
        assertEquals(1, stops)
    }
}
