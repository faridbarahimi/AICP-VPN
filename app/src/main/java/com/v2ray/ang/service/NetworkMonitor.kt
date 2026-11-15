package com.v2ray.ang.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.v2ray.ang.AppConfig
import com.v2ray.ang.extension.delay
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Watches the network that carries the tunnel and reports topology changes.
 *
 * Cellular -> Wi-Fi is a make-before-break handover: the new network is announced while the old one
 * is still connected, so the socket to the server is never reset and the core keeps using a dead
 * connection. Deciding that a handover happened is what this class is for, acting on it is not.
 *
 * Only used from Android P and above, see CoreServiceManager.startNetworkMonitor().
 * [onHandover] is invoked in the supplied service-owned scope after the debounce window and may block.
 */
class NetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val onUnderlyingNetworksChanged: (Array<Network>?) -> Unit,
    private val onHandover: () -> Unit,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val HANDOVER_DEBOUNCE_MS = 1000L
    }

    private val callbackState = NetworkCallbackState<Network>()
    private var handoverJob: Job? = null
    @Volatile private var registered = false

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private val request by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!registered) return
            val previous = callbackState.onAvailable(network)
            onUnderlyingNetworksChanged(arrayOf(network))
            if (previous != null && previous != network) scheduleHandover(network)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            // Android may deliver a late capability callback for an upstream that has already been replaced.
            if (registered && callbackState.isCurrent(network)) {
                onUnderlyingNetworksChanged(arrayOf(network))
            }
        }

        override fun onLost(network: Network) {
            // Ignore a stale loss for A after onAvailable(B); only the current upstream may be cleared.
            if (registered && callbackState.onLost(network)) {
                onUnderlyingNetworksChanged(null)
            }
        }
    }

    /**
     * Starts watching. Safe to call more than once, only the first call registers.
     */
    fun register() {
        if (registered) return
        try {
            registered = true
            connectivity.requestNetwork(request, callback)
        } catch (e: Exception) {
            registered = false
            callbackState.clear()
            LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to request network", e)
        }
    }

    /**
     * Stops watching and drops the tracked state. Safe to call more than once.
     */
    fun unregister() {
        unregisterInternal()?.cancel()
    }

    /** Cancels the pending handover and waits until its callback can no longer run. */
    suspend fun unregisterAndJoin() {
        unregisterInternal()?.cancelAndJoin()
    }

    private fun unregisterInternal(): Job? {
        val wasRegistered = registered
        registered = false
        callbackState.clear()
        val pending = synchronized(this) {
            handoverJob.also { handoverJob = null }
        }
        if (wasRegistered) {
            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "NetworkMonitor: Failed to unregister callback", e)
            }
        }
        return pending
    }

    private fun scheduleHandover(network: Network) {
        LogUtil.i(AppConfig.TAG, "NetworkMonitor: Upstream changed")
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(HANDOVER_DEBOUNCE_MS)
                if (registered && callbackState.isCurrent(network)) onHandover()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to handle upstream change (${e.javaClass.simpleName})")
            }
        }
        val accepted = synchronized(this) {
            if (registered && callbackState.isCurrent(network)) {
                handoverJob?.cancel()
                handoverJob = job
                true
            } else false
        }
        if (accepted) job.start() else job.cancel()
    }
}

/** Small, thread-safe state machine so callback ordering can be tested without Android networking. */
internal class NetworkCallbackState<T : Any> {
    private var current: T? = null

    @Synchronized
    fun onAvailable(network: T): T? {
        val previous = current
        current = network
        return previous
    }

    @Synchronized
    fun isCurrent(network: T): Boolean = current == network

    @Synchronized
    fun onLost(network: T): Boolean {
        if (current != network) return false
        current = null
        return true
    }

    @Synchronized
    fun clear() {
        current = null
    }
}
