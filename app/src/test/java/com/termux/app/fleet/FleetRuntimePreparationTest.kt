package com.termux.app.fleet

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class FleetRuntimePreparationTest {
    @Test fun nestedOperationsKeepPreparationActiveAndFailureReleasesIt() {
        assertFalse(FleetRuntimePreparation.active)
        runCatching {
            FleetRuntimePreparation.mutate {
                assertTrue(FleetRuntimePreparation.active)
                FleetRuntimePreparation.mutate { assertTrue(FleetRuntimePreparation.active) }
                assertTrue(FleetRuntimePreparation.active)
                error("Preparation failed")
            }
        }
        assertFalse(FleetRuntimePreparation.active)
    }

    @Test fun independentManagersCannotReplaceConfigurationConcurrently() {
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        try {
            val first = executor.submit { FleetRuntimePreparation.mutate { entered.countDown(); release.await(5, TimeUnit.SECONDS) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = executor.submit { FleetRuntimePreparation.mutate { secondEntered.countDown() } }
            assertTrue(FleetRuntimePreparation.active)
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertEquals(0L, secondEntered.count)
            assertFalse(FleetRuntimePreparation.active)
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
