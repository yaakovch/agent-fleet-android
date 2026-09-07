package com.termux.app.fleet

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class FleetNetworkMonitorTest {
    @Test fun routeChangesRetryOnceAndBackgroundCallbacksCannotRestartDiscovery() {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = shadowOf(connectivity)
        shadow.setNetworkCallbacksEnabled(false)
        shadow.clearAllNetworks()
        val existing = ShadowNetwork.newInstance(101)
        shadow.addNetwork(existing, null)
        var retries = 0
        val monitor = FleetNetworkMonitor(context, Handler(Looper.getMainLooper())) { retries++ }
        try {
            monitor.start()
            val callbacks = shadow.networkCallbacks.toList()
            assertEquals("Observe both VPN routes and the underlying network", 2, callbacks.size)
            callbacks.forEach { it.onAvailable(existing) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals("Initial registration must not restart a cold handshake", 0, retries)

            val connected = ShadowNetwork.newInstance(202)
            callbacks.forEach { it.onAvailable(connected) }
            callbacks.first().onLost(existing)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals("Coalesce one route transition into one retry", 1, retries)

            callbacks.first().onLost(connected)
            monitor.stop()
            callbacks.forEach { it.onAvailable(existing) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(1, retries)
            assertTrue(shadow.networkCallbacks.isEmpty())
        } finally {
            monitor.stop()
        }
    }
}
