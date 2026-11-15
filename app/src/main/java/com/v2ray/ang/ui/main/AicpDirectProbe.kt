package com.v2ray.ang.ui.main

import android.net.Network
import com.v2ray.ang.handler.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * One bounded request that leaves through the physical network, bypassing the tunnel (socket and DNS are bound
 * to that Network). It is the real "direct" reference for Smart Connect and runs only when a tunnel sample
 * already looks bad, so it adds no steady traffic.
 */
internal object AicpDirectProbe {
    private const val TIMEOUT_MS = 1_500L

    /** Latency in ms, or -1 when the direct network could not complete the request. */
    suspend fun measure(network: Network): Long = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        var client: OkHttpClient? = null
        try {
            // Build inside the guarded block too: a stale/disconnected Network may reject its socket factory.
            val probeClient = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .socketFactory(network.socketFactory)
                .dns(Dns { host -> network.getAllByName(host).toList() })
                .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build()
            client = probeClient
            val request = Request.Builder()
                .url(SettingsManager.getDelayTestUrl())
                .header("Cache-Control", "no-cache")
                .build()
            probeClient.newCall(request).execute().use { response ->
                val hasBody = if (response.code == 204) false else response.body.byteStream().use { it.read() >= 0 }
                if (AicpTunnelHealthProbe.isHealthyResponse(response.code, response.header("Content-Type"), hasBody)) {
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt).coerceAtLeast(1L)
                } else -1L
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            -1L
        } finally {
            client?.let { probeClient ->
                runCatching { probeClient.connectionPool.evictAll() }
                runCatching { probeClient.dispatcher.executorService.shutdown() }
            }
        }
    }
}
