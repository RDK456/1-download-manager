package com.downloadhub.app.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Small connectivity helper used for the "download on Wi-Fi only" setting. */
class NetworkMonitor(context: Context) {
    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun isConnected(): Boolean {
        val manager = connectivity ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** True when the active network looks like metered mobile data. */
    fun isMetered(): Boolean {
        val manager = connectivity ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return true
        return !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    fun isWifiOnly(): Boolean = isConnected() && !isMetered()
}
