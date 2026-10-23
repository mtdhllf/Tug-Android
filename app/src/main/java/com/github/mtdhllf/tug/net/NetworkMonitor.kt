package com.github.mtdhllf.tug.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 当前网络状态。 */
data class NetworkState(
    val online: Boolean,
    val wifi: Boolean,
)

/**
 * 网络状态监听（默认网络回调）。
 *
 * 只关心"是否在线 / 是否 Wi-Fi"，供"仅 Wi-Fi"与断网暂停策略使用。
 */
class NetworkMonitor(context: Context) {
    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()

        override fun onLost(network: Network) = refresh()

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = refresh()
    }

    init {
        runCatching { connectivity?.registerDefaultNetworkCallback(callback) }
    }

    fun start() {
        refresh()
    }

    fun close() {
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
    }

    private fun refresh() {
        _state.value = readState()
    }

    private fun readState(): NetworkState {
        val manager = connectivity ?: return NetworkState(online = false, wifi = false)
        val network = manager.activeNetwork ?: return NetworkState(online = false, wifi = false)
        val caps = manager.getNetworkCapabilities(network)
            ?: return NetworkState(online = false, wifi = false)
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return NetworkState(online = online, wifi = wifi)
    }
}
