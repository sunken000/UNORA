package app.unora.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.unora.party.PartyManager

class NetworkMonitor(context: Context, private val partyManager: PartyManager) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = partyManager.onNetworkChanged()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = partyManager.onNetworkChanged()
    }

    fun start() = connectivity.registerDefaultNetworkCallback(callback)
    fun stop() = runCatching { connectivity.unregisterNetworkCallback(callback) }
}
