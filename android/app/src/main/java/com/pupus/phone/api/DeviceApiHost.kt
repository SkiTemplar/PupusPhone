package com.pupus.phone.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pupus.phone.NetworkAddresses

/**
 * Runs one [DeviceApiServer] per address chosen by [BindPolicy] and re-evaluates
 * the set whenever a network (including the Tailscale VPN) appears, changes or goes.
 * All methods run on the main thread.
 */
class DeviceApiHost(
    context: Context,
    private val handler: RouteHandler,
    private val isLanAllowed: () -> Boolean,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val servers = HashMap<String, DeviceApiServer>()
    private val rebindTask = Runnable { rebind() }
    private var isRunning = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleRebind()
        override fun onLost(network: Network) = scheduleRebind()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = scheduleRebind()
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        rebind()
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            connectivity.registerNetworkCallback(request, callback)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Cannot watch network changes; bind set is fixed until restart", e)
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        main.removeCallbacks(rebindTask)
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Network callback was not registered", e)
        }
        servers.values.forEach { it.stop() }
        servers.clear()
    }

    /** Recomputes the listen addresses now (e.g. after toggling "Permitir red local"). */
    fun rebind() {
        if (!isRunning) return
        val desired = BindPolicy.select(NetworkAddresses.ipv4(), isLanAllowed())
        val plan = BindPlan.between(servers.keys.toSet(), desired)
        plan.toStop.forEach { host -> servers.remove(host)?.stop() }
        plan.toStart.forEach { host ->
            val server = DeviceApiServer(host, DeviceApiServer.PORT, handler)
            if (server.startSafely()) servers[host] = server
        }
        if (plan.toStart.isNotEmpty() || plan.toStop.isNotEmpty()) Log.i(TAG, "Device API bound to ${servers.keys}")
    }

    private fun scheduleRebind() {
        main.removeCallbacks(rebindTask)
        main.postDelayed(rebindTask, DEBOUNCE_MS)
    }

    private companion object {
        const val TAG = "PupusApi"
        const val DEBOUNCE_MS = 500L
    }
}
