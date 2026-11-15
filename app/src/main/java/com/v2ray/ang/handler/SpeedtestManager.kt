package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.IPAPIInfo
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

internal class BoundedProbeExecutor(maxWorkers: Int) : AutoCloseable {
    private val executor = ThreadPoolExecutor(
        maxWorkers,
        maxWorkers,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
    ) { task -> Thread(task, "VpnBoundedProbe").apply { isDaemon = true } }

    init {
        require(maxWorkers > 0)
        executor.allowCoreThreadTimeOut(true)
    }

    fun <T> run(timeoutMs: Int, block: () -> T): T? {
        if (timeoutMs <= 0) return null
        val task = FutureTask(block)
        try {
            executor.execute(task)
            return task.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            task.cancel(true)
        } catch (_: ExecutionException) {
            return null
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return null
        } catch (_: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
        }
        return null
    }

    override fun close() {
        executor.shutdownNow()
    }
}

object SpeedtestManager {
    // DNS resolution is not covered by Socket.connect(timeout). Bound it on a small daemon pool,
    // and reject excess probes rather than queueing work that can no longer meet its deadline.
    private val socketProbeExecutor = BoundedProbeExecutor(8)

    data class RemoteEndpointInfo(
        val country: String?,
        val ipAddress: String?,
    )

    /**
     * Measures the time taken to establish a TCP connection to a given URL and port.
     *
     * @param url The URL to connect to.
     * @param port The port to connect to.
     * @return The connection time in milliseconds, or -1 if the connection failed.
     */
    fun socketConnectTime(url: String, port: Int, timeoutMs: Int = 1500): Long {
        if (url.isBlank() || port !in 1..65_535 || timeoutMs <= 0) return -1L
        val startedNanos = System.nanoTime()
        val activeSocket = AtomicReference<Socket?>()
        val result = socketProbeExecutor.run(timeoutMs) {
            try {
                // Resolve inside the same bounded task; InetSocketAddress(host, port) resolves
                // synchronously before connect timeout starts and can otherwise stall a ping scan.
                val addresses = InetAddress.getAllByName(url)
                for (address in addresses) {
                    val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
                    val remainingMillis = timeoutMs - elapsedMillis
                    if (remainingMillis <= 0L) return@run -1L
                    val socket = Socket()
                    activeSocket.set(socket)
                    try {
                        socket.connect(InetSocketAddress(address, port), remainingMillis.toInt().coerceAtLeast(1))
                        return@run TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
                    } catch (_: IOException) {
                        // Try another resolved address only while the original deadline remains.
                    } finally {
                        runCatching { socket.close() }
                        activeSocket.compareAndSet(socket, null)
                    }
                }
                -1L
            } catch (_: Exception) {
                -1L
            }
        }
        if (result == null) {
            activeSocket.getAndSet(null)?.let { runCatching { it.close() } }
            return -1L
        }
        return result
    }

    fun getRemoteIPInfo(): RemoteEndpointInfo? {
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
            .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val httpPort = SettingsManager.getHttpPort()
        if (httpPort == 0) return null
        val content = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 5000,
                httpPort = httpPort,
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword
            )
        ) ?: return null
        val ipInfo = JsonUtil.fromJsonSafe(content, IPAPIInfo::class.java) ?: return null

        val ip = listOf(
            ipInfo.ip,
            ipInfo.clientIp,
            ipInfo.ip_addr,
            ipInfo.query
        ).firstOrNull { !it.isNullOrBlank() }

        val country = listOf(
            ipInfo.country_code,
            ipInfo.country,
            ipInfo.countryCode,
            ipInfo.location?.country_code
        ).firstOrNull { !it.isNullOrBlank() }

        return RemoteEndpointInfo(
            country = country,
            ipAddress = ip,
        )
    }
}
