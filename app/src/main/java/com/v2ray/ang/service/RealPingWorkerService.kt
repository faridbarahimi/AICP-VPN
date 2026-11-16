package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.core.AetherDelayTester
import com.v2ray.ang.core.CoreConfigContextBuilder
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.ui.main.AicpThroughputTester
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.AppConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** A probe that throws is a measured failure, not an unmeasured zero-result profile. */
internal inline fun runRealPingProbe(
    probe: () -> Long,
    onFailure: (Throwable) -> Unit = {},
): Long = try {
    probe()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    if (error is VirtualMachineError || error is ThreadDeath) throw error
    onFailure(error)
    -1L
}

/** A native ping can block its worker thread; a separate watchdog cancels its native batch on deadline. */
internal object RealPingProbeDeadline {
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "RealPingDeadline").apply { isDaemon = true }
    }

    suspend fun <T> run(timeoutMillis: Long, onTimeout: () -> Unit, probe: suspend () -> T): T? {
        require(timeoutMillis > 0L) { "Ping deadline must be positive" }
        val completed = AtomicBoolean(false)
        val watchdog = scheduler.schedule({
            if (completed.compareAndSet(false, true)) {
                runCatching(onTimeout)
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS)
        return try {
            withTimeoutOrNull(timeoutMillis) { probe() }
        } finally {
            completed.set(true)
            watchdog.cancel(false)
        }
    }
}

internal object RealPingExecutionLimiter {
    private val customConfigMutex = Mutex()

    suspend fun <T> run(configType: EConfigType, block: () -> T): T {
        // Custom profiles bypass speed-test trimming and start complete Xray configs.
        // Parallel teardown can abort the native probe process, so serialize their
        // JNI measurements globally across batches.
        return if (configType == EConfigType.CUSTOM) {
            customConfigMutex.withLock { block() }
        } else {
            block()
        }
    }
}

/**
 * Worker that runs a batch of real-ping tests independently.
 * Each batch owns its own CoroutineScope/dispatcher and can be cancelled separately.
 */
internal object RealPingTimeoutPolicy {
    const val TCP_REACHABILITY_TIMEOUT_MS = 1_800
    const val NATIVE_PING_TIMEOUT_MS = 16_000L
    const val AETHER_PING_TIMEOUT_MS = 14_000L
}

class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onlyTcp: Boolean = false,
    private val onEvent: (RealPingEvent) -> Unit = {}
) {
    private val job = SupervisorJob()
    private val concurrency = SettingsManager.getRealPingConcurrency()
    private val dispatcher = Executors.newFixedThreadPool(if (onlyTcp) concurrency * 2 else concurrency).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    // Names the measurements of this batch in the native core, so that cancel() ends them and no other batch's
    private val batch = UUID.randomUUID().toString()
    // Each native measurement has an isolated cancellation key. Cancelling one slow profile must
    // never cancel healthy profiles that happen to share this worker's subscription scan.
    private val activeProbeBatches = ConcurrentHashMap.newKeySet<String>()

    // A latency endpoint alone can answer 204/empty and still say nothing about useful data transfer.
    // Every positive candidate must also download a bounded 32 KiB payload through the same outbound.
    private fun payloadProbeUrls(): List<String> {
        val nonce = UUID.randomUUID().toString()
        return listOf(
            "https://speed.cloudflare.com/__down?bytes=32768&aicp_probe=$nonce",
            "https://httpbin.org/bytes/32768?seed=${nonce.hashCode().toUInt()}"
        )
    }

    private val completedCount = AtomicInteger(0)

    fun start() {
        val jobs = guids.mapIndexed { index, guid ->
            val probeBatch = "$batch:$index"
            scope.launch {
                try {
                    val result = runRealPingProbe(
                        probe = { if (onlyTcp) startTcping(guid) else startRealPing(guid, probeBatch) },
                        onFailure = { error -> LogUtil.w(AppConfig.TAG, "Real ping failed for ${guid.take(8)}; recording unreachable result (${error.javaClass.simpleName})") },
                    )
                    if (scope.isActive) {
                        onEvent(RealPingEvent.Result(guid, result))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } finally {
                    activeProbeBatches.remove(probeBatch)
                    val completed = completedCount.incrementAndGet()
                    val remaining = (guids.size - completed).coerceAtLeast(0)
                    if (scope.isActive) {
                        // The existing status text is explicitly phrased as "tasks left".
                        onEvent(RealPingEvent.Progress("$remaining / ${guids.size}"))
                    }
                }
            }
        }

        scope.launch {
            try {
                joinAll(*jobs.toTypedArray())
                if (isActive) {
                    onEvent(RealPingEvent.Finish("0"))
                }
            } catch (_: CancellationException) {
                // If cancelled, don't send finish event to avoid confusion
            } finally {
                close()
            }
        }
    }

    fun cancel() {
        job.cancel()
        // A measurement blocks its thread in the native core, where a cancelled coroutine does not reach it. The
        // native call runs on a thread of its own: cancel() is often called on the main thread of a service.
        thread(name = "RealPingCancel") {
            activeProbeBatches.toList().forEach(CoreNativeManager::cancelOutboundDelays)
        }
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private suspend fun startRealPing(guid: String, probeBatch: String): Long {
        val retFailure = -1L

        val config = MmkvManager.decodeServerConfig(guid) ?: return retFailure
        // An Aether profile is measured through its core alone, unless its subscription chains it with
        // other hops: then it is measured as the chain it runs in, as every chained profile is.
        if (config.configType == EConfigType.AETHER && !CoreConfigContextBuilder.isChained(config)) {
            return withTimeoutOrNull(RealPingTimeoutPolicy.AETHER_PING_TIMEOUT_MS) {
                AetherDelayTester.measureWithPayload(
                    context = context,
                    guid = guid,
                    profile = config,
                    latencyUrls = listOf(SettingsManager.getDelayTestUrl(), SettingsManager.getDelayTestUrl(true)),
                    payloadUrls = payloadProbeUrls(),
                )
            } ?: retFailure
        }

        val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status) {
            return retFailure
        }
        val aether = configResult.aetherCore
        if (aether != null) {
            // The configuration reaches the internet through an Aether outbound, so it is measured behind
            // that core: the live session, or a test tunnel on the same port, which its Aether outbounds
            // dial either way, and which dials out through a hop of the chain, as the session's would.
            // Its own server is not probed: it is only reachable through that core.
            return AetherDelayTester.measureVia(context, guid, aether, configResult.content) { port, deadline ->
                var latency = -1L
                val latencyDeadline = minOf(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))
                for (url in listOf(SettingsManager.getDelayTestUrl(), SettingsManager.getDelayTestUrl(true))) {
                    if (System.nanoTime() >= latencyDeadline) break
                    latency = AetherDelayTester.cancellableRequestDelay(port, url, latencyDeadline)
                    if (latency >= 0L) break
                }
                if (latency <= 0L) {
                    -1L
                } else {
                    val payloadVerified = AetherDelayTester.verifyPayloadUrls(port, payloadProbeUrls(), deadline)
                    if (payloadVerified) latency else -1L
                }
            }
        }
        if (!config.configType.isComplexType()
            && config.configType != EConfigType.HYSTERIA2
            && config.configType != EConfigType.WIREGUARD
            && config.alpn?.startsWith("h3") != true
            && config.server.isNotNullEmpty()
            && config.serverPort?.toIntOrNull() != null
        ) {
            val url = config.server.orEmpty()
            val port = config.serverPort.orEmpty().toInt()
            val tcpTime = SpeedtestManager.socketConnectTime(url, port, RealPingTimeoutPolicy.TCP_REACHABILITY_TIMEOUT_MS)
            if (tcpTime <= -1L) {
                return retFailure
            }
        }

        return measureOutboundDelayWithFallback(configResult.content, probeBatch, config.configType)
    }

    private suspend fun measureOutboundDelayWithFallback(config: String, batch: String, configType: EConfigType): Long =
        RealPingProbeDeadline.run(
            RealPingTimeoutPolicy.NATIVE_PING_TIMEOUT_MS,
            { if (activeProbeBatches.contains(batch)) CoreNativeManager.cancelOutboundDelays(batch) },
        ) {
            RealPingExecutionLimiter.run(configType) {
                activeProbeBatches.add(batch)
                try {
                    val primary = CoreNativeManager.measureOutboundDelay(config, SettingsManager.getDelayTestUrl(), batch)
                    val latency = if (primary >= 0L) primary else
                        CoreNativeManager.measureOutboundDelay(config, SettingsManager.getDelayTestUrl(true), batch)
                    if (latency < 0L) {
                        -1L
                    } else {
                        val payloadDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
                        val verified = runCatching {
                            AicpThroughputTester.verifyRealPayload(context, config, payloadProbeUrls(), payloadDeadline)
                        }.getOrDefault(false)
                        if (verified) latency else -1L
                    }
                } finally {
                    activeProbeBatches.remove(batch)
                }
            }
        } ?: -1L

    private fun startTcping(guid: String): Long {
        val retFailure = -1L

        val config = MmkvManager.decodeServerConfig(guid) ?: return retFailure
        if (config.configType == EConfigType.AETHER) {
            return AetherDelayTester.reachability(config)
        }
        if (!config.configType.isComplexType()
            && config.configType != EConfigType.HYSTERIA2
            && config.configType != EConfigType.WIREGUARD
            && config.alpn?.split(',')?.all { it.trim().startsWith("h3") } != true
            && config.server.isNotNullEmpty()
            && config.serverPort?.toIntOrNull() != null
        ) {
            val url = config.server.orEmpty()
            val port = config.serverPort.orEmpty().toInt()
            val tcpTime = SpeedtestManager.socketConnectTime(url, port, RealPingTimeoutPolicy.TCP_REACHABILITY_TIMEOUT_MS)

            return tcpTime
        }

        return retFailure
    }

}
