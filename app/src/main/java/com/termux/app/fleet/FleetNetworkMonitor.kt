package com.termux.app.fleet

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler

/** Watch VPN routes explicitly: a tailnet need not provide validated Internet access. */
internal class FleetNetworkMonitor(context: Context, private val main: Handler, changed: () -> Unit) {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val notification = Runnable(changed)
    @Volatile private var active = false
    private val registered = mutableListOf<ConnectivityManager.NetworkCallback>()

    private fun callback(existing: Set<Network>) = object : ConnectivityManager.NetworkCallback() {
        private var initialized = false
        override fun onAvailable(network: Network) {
            // Registration reports the current network too. That is not a
            // route change and must not restart an in-flight cold handshake.
            if (initialized || network !in existing) notifyChange()
            initialized = true
        }
        override fun onLost(network: Network) = notifyChange()
    }

    private fun notifyChange() {
        main.post {
            if (active) {
                main.removeCallbacks(notification)
                main.postDelayed(notification, 500)
            }
        }
    }

    fun start() {
        if (active) return
        active = true
        val existing = runCatching { connectivity.allNetworks.toSet() }.getOrDefault(emptySet())
        val vpn = callback(existing)
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder()
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(), vpn
            )
            registered += vpn
        }
        val underlying = callback(existing)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivity.registerDefaultNetworkCallback(underlying)
            } else {
                connectivity.registerNetworkCallback(NetworkRequest.Builder().build(), underlying)
            }
            registered += underlying
        }
    }

    fun stop() {
        active = false
        main.removeCallbacks(notification)
        registered.forEach { runCatching { connectivity.unregisterNetworkCallback(it) } }
        registered.clear()
    }
}
