package com.example.localwebdavsync.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

class NetworkStateProvider(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var lastWifiDisconnectedAt: Long = 0L

    fun isWifiConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun wasWifiRecentlyDisconnected(windowMs: Long): Boolean {
        val disconnectedAt = lastWifiDisconnectedAt
        return disconnectedAt > 0L && System.currentTimeMillis() - disconnectedAt <= windowMs
    }

    fun registerWifiCallback(
        onAvailable: () -> Unit,
        onLost: () -> Unit = {}
    ) {
        unregisterWifiCallback()
        var lastWifiConnected = isWifiConnected()
        val stateLock = Any()

        fun handleWifiStateChange(wifiConnected: Boolean) {
            val nextState = synchronized(stateLock) {
                if (wifiConnected == lastWifiConnected) {
                    null
                } else {
                    lastWifiConnected = wifiConnected
                    wifiConnected
                }
            }
            when (nextState) {
                true -> onAvailable()
                false -> {
                    lastWifiDisconnectedAt = System.currentTimeMillis()
                    onLost()
                }
                null -> Unit
            }
        }

        val defaultCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handleWifiStateChange(isWifiConnected())
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                handleWifiStateChange(isWifiConnected())
            }

            override fun onLost(network: Network) {
                handleWifiStateChange(isWifiConnected())
            }

            override fun onUnavailable() {
                handleWifiStateChange(false)
            }
        }
        val wifiCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handleWifiStateChange(isWifiConnected())
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                handleWifiStateChange(
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        isWifiConnected()
                )
            }

            override fun onLost(network: Network) {
                handleWifiStateChange(false)
            }

            override fun onUnavailable() {
                handleWifiStateChange(false)
            }
        }
        defaultNetworkCallback = defaultCallback
        wifiNetworkCallback = wifiCallback
        connectivityManager.registerDefaultNetworkCallback(defaultCallback)
        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build(),
            wifiCallback
        )
    }

    fun unregisterWifiCallback() {
        defaultNetworkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        wifiNetworkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        defaultNetworkCallback = null
        wifiNetworkCallback = null
    }
}
