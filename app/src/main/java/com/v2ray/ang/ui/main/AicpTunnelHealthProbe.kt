package com.v2ray.ang.ui.main

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.AicpEgressVerifier as CoreAicpEgressVerifier
import com.v2ray.ang.handler.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Bounded data-plane checks for Android VPN Network plus a local SOCKS diagnostic probe. */
internal object AicpTunnelHealthProbe {
    private const val MAX_TIMEOUT_MS = 2_000L
    @Volatile private var cachedPort: Int = -1
    @Volatile private var cachedTimeoutMillis: Long = -1L
    @Volatile private var cachedClient: OkHttpClient? = null

    /** Measures real device traffic through Android's VPN Network; local SOCKS success is not sufficient. */
    suspend fun measureVpnNetworkPayload(context: Context, timeoutMillis: Long): Long = withContext(Dispatchers.IO) {
        val totalTimeout = timeoutMillis.coerceIn(500L, MAX_TIMEOUT_MS).toInt()
        val startedAt = System.nanoTime()
        val verified = runCatching {
            CoreAicpEgressVerifier.verifyVpnNetworkPayload(context, totalTimeout)
        }.getOrDefault(false)
        if (verified) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt).coerceAtLeast(1L) else -1L
    }

    suspend fun measure(timeoutMillis: Long): Long = withContext(Dispatchers.IO) {
        val totalTimeout = timeoutMillis.coerceIn(500L, MAX_TIMEOUT_MS)
        val port = SettingsManager.getSocksPort()
        val primaryUrl = SettingsManager.getDelayTestUrl()
        val fallbackUrl = SettingsManager.getDelayTestUrl(true)
        if (primaryUrl == fallbackUrl || totalTimeout < 1_000L) {
            return@withContext measureUrl(primaryUrl, port, totalTimeout)
        }
        val primaryBudget = totalTimeout / 2L
        val fallbackBudget = totalTimeout - primaryBudget
        val primary = measureUrl(primaryUrl, port, primaryBudget)
        if (primary in 1L..AicpSmartConnectPolicy.UNHEALTHY_LATENCY_THRESHOLD_MS) return@withContext primary
        val fallback = measureUrl(fallbackUrl, port, fallbackBudget)
        selectProbeLatency(primary, fallback)
    }

    private fun measureUrl(url: String, port: Int, timeoutMillis: Long): Long {
        val startedAt = System.nanoTime()
        return try {
            val client = clientFor(port, timeoutMillis)
            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                val hasBody = if (response.code == 204) false else response.body?.byteStream()?.use { it.read() >= 0 } ?: false
                if (isHealthyResponse(response.code, response.header("Content-Type"), hasBody)) {
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                } else {
                    -1L
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            -1L
        }
    }

    internal fun selectProbeLatency(primaryMillis: Long, fallbackMillis: Long): Long {
        if (primaryMillis in 1L..AicpSmartConnectPolicy.UNHEALTHY_LATENCY_THRESHOLD_MS) return primaryMillis
        if (fallbackMillis in 1L..AicpSmartConnectPolicy.UNHEALTHY_LATENCY_THRESHOLD_MS) return fallbackMillis
        return listOf(primaryMillis, fallbackMillis).filter { it > 0L }.minOrNull() ?: -1L
    }

    internal fun isHealthyResponse(statusCode: Int, contentType: String?, hasBody: Boolean): Boolean {
        if (statusCode == 204) return true
        if (statusCode !in 200..299 || !hasBody) return false
        return !contentType.orEmpty().trim().startsWith("text/html", ignoreCase = true)
    }

    @Synchronized
    private fun clientFor(port: Int, timeoutMillis: Long): OkHttpClient {
        if (cachedClient == null || cachedPort != port || cachedTimeoutMillis != timeoutMillis) {
            cachedClient?.let { previous ->
                previous.connectionPool.evictAll()
                previous.dispatcher.executorService.shutdown()
            }
            cachedPort = port
            cachedTimeoutMillis = timeoutMillis
            cachedClient = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(AppConfig.LOOPBACK, port)))
                .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
        return cachedClient!!
    }
}
