package dev.mimir.android.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.mimir.core.NetworkKind
import dev.mimir.core.NetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidNetworkState(
    context: Context,
) : NetworkState, AutoCloseable {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val _current = MutableStateFlow(determineCurrentNetwork())
    override val current: StateFlow<NetworkKind> = _current.asStateFlow()
    private var callback: ConnectivityManager.NetworkCallback? = null

    init {
        registerCallback()
    }

    private fun determineCurrentNetwork(): NetworkKind {
        val cm = connectivityManager ?: return NetworkKind.NONE
        val activeNetwork = cm.activeNetwork ?: return NetworkKind.NONE
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkKind.NONE
        return capabilitiesToKind(capabilities)
    }

    private fun capabilitiesToKind(capabilities: NetworkCapabilities): NetworkKind {
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkKind.NONE
        }
        return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            NetworkKind.WIFI
        } else {
            NetworkKind.METERED
        }
    }

    private fun registerCallback() {
        val cm = connectivityManager ?: return
        try {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = cm.getNetworkCapabilities(network)
                    _current.value = caps?.let { capabilitiesToKind(it) } ?: NetworkKind.NONE
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    _current.value = capabilitiesToKind(networkCapabilities)
                }

                override fun onLost(network: Network) {
                    _current.value = determineCurrentNetwork()
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (_: Exception) {
            // Ignored in restricted environments or unit test shadows without connectivity callback support
        }
    }

    override fun close() {
        val cm = connectivityManager ?: return
        val cb = callback ?: return
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        } finally {
            callback = null
        }
    }
}
