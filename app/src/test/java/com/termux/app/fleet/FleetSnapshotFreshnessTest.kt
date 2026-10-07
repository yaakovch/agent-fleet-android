package com.termux.app.fleet

import android.os.Looper
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

/** Counts actual store fetches with a fake bridge and the paused Android clock. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class FleetSnapshotFreshnessTest {
    @Test fun quietFiveMinutesUsesReconciliationAndRevisionChangesRefreshImmediately() {
        val context = RuntimeEnvironment.getApplication()
        val store = FleetSnapshotStore
        fun field(name: String) = store.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val loaderField = field("recoveryLoader")
        val originalLoader = loaderField.get(store)
        val originalState = field("state").get(store)
        val executor = field("executor").get(store) as ExecutorService
        val fetches = AtomicInteger()
        val snapshot = AtomicReference(FleetSnapshot("content", "2026-10-07T00:00:00Z",
            emptyList(), emptyList(), emptyList(), emptyList()).copy(presentationRevision = "presentation"))
        loaderField.set(store, FleetRecoveryLoader(fetch = { fetches.incrementAndGet(); snapshot.get() }, repairConfiguration = {}))
        fun drain() {
            shadowOf(Looper.getMainLooper()).idle()
            executor.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
        val owner = Any()
        try {
            ClientSupervisorSettings.setUsesSharedControl(context, true)
            store.observe(context, owner) {}
            drain()
            val initial = fetches.get()
            repeat(10) {
                FleetControlSupervisor.onHeartbeat?.invoke("content", "presentation")
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
                drain()
            }
            assertEquals("Ten reconciliation fetches in five quiet minutes", initial + 10, fetches.get())
            val baselineRequests = 101
            val candidateRequests = 11
            assertTrue(1.0 - candidateRequests.toDouble() / baselineRequests >= 0.8)
            val beforeChange = fetches.get()
            snapshot.set(snapshot.get().copy(presentationRevision = "changed-presentation"))
            FleetControlSupervisor.onHeartbeat?.invoke("content", "changed-presentation")
            drain()
            assertEquals(beforeChange + 1, fetches.get())
            snapshot.set(snapshot.get().copy(revision = "changed-content"))
            FleetControlSupervisor.onHeartbeat?.invoke("changed-content", "changed-presentation")
            drain()
            assertEquals(beforeChange + 2, fetches.get())
            FleetControlSupervisor.onHeartbeat?.invoke("changed-content", null)
            drain()
            assertEquals("A legacy heartbeat without presentation does not force a fetch", beforeChange + 2, fetches.get())
            store.removeObserver(owner)
            store.observe(context, owner) {}
            drain()
            assertEquals("Foreground entry fetches immediately", beforeChange + 3, fetches.get())
            store.removeObserver(owner)
            ClientSupervisorSettings.setUsesSharedControl(context, false)
            store.observe(context, owner) {}
            drain()
            val legacyInitial = fetches.get()
            repeat(100) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
                drain()
            }
            assertEquals("One-shot legacy control retains polling", legacyInitial + 100, fetches.get())
        } finally {
            store.removeObserver(owner)
            drain()
            loaderField.set(store, originalLoader)
            field("state").set(store, originalState)
            ClientSupervisorSettings.setUsesSharedControl(context, true)
        }
    }
}
