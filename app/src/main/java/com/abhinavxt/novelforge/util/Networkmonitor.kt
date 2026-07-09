package com.abhinavxt.novelforge.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reactive connectivity monitor.
 *
 * Wraps [ConnectivityManager.NetworkCallback] into a [StateFlow<Boolean>]
 * so Compose UI can observe "am I online" without polling or listening
 * to broadcasts.
 *
 * ── Why a singleton-ish pattern (held by Application) ──
 * The Android docs warn about leaking ConnectivityManager callbacks.
 * Instantiating NetworkMonitor once at the Application level and
 * calling [start] in Application.onCreate + [stop] never (process
 * outlives it) is the safe shape.
 *
 * ── Accuracy ──
 * "Online" here means: there's at least one active network with the
 * VALIDATED capability, i.e. the network is actually reachable, not
 * just "WiFi signal exists but the captive portal hasn't been resolved."
 * Without VALIDATED, users on airport-WiFi-with-portal would see
 * "online" and then fail every request.
 *
 * There's a brief window after losing network where the callback hasn't
 * fired yet (typically < 1s). During that window ops that try to hit
 * the network will fail with IOException — this is fine, the failure
 * path displays an error. The banner exists to communicate intent, not
 * to gate operations.
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager

    private val _isOnline = MutableStateFlow(currentOnlineStatus())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    /**
     * Set of networks we consider "online right now." We have to track
     * this as a set because a device can have multiple active networks
     * simultaneously (WiFi + cellular, for instance, during handover).
     * onLost for one doesn't mean we're offline — onLost for ALL does.
     */
    private val activeNetworks = mutableSetOf<Network>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(activeNetworks) {
                activeNetworks.add(network)
            }
            _isOnline.value = true
        }

        override fun onLost(network: Network) {
            val stillOnline = synchronized(activeNetworks) {
                activeNetworks.remove(network)
                activeNetworks.isNotEmpty()
            }
            _isOnline.value = stillOnline
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            // A network may exist but not yet be validated. If we see
            // validated → treat as online. If we see NOT validated for
            // all our tracked networks → treat as offline (captive portal).
            val validated = capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            )
            synchronized(activeNetworks) {
                if (validated) {
                    activeNetworks.add(network)
                } else {
                    activeNetworks.remove(network)
                }
            }
            _isOnline.value = synchronized(activeNetworks) {
                activeNetworks.isNotEmpty()
            }
        }
    }

    /**
     * Register the callback. Call once, from Application.onCreate.
     * Idempotent — calling twice is a no-op.
     */
    fun start() {
        val cm = connectivityManager ?: return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Logger.e("NetworkMonitor", "registerNetworkCallback failed", e)
            // Fall back to whatever the one-shot check said.
        }
    }

    /**
     * Unregister. In practice you never call this — the monitor lives
     * for the process. Provided for completeness and for tests.
     */
    fun stop() {
        val cm = connectivityManager ?: return
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            // Not registered, probably. Ignore.
        }
    }

    /**
     * One-shot synchronous check, used for the initial StateFlow value
     * before any callbacks fire.
     */
    private fun currentOnlineStatus(): Boolean {
        val cm = connectivityManager ?: return true  // fail-open
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}