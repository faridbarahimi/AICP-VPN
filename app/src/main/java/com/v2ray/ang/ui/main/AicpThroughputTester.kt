package com.v2ray.ang.ui.main

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.AetherCore
import com.v2ray.ang.core.AetherDelayTester
import com.v2ray.ang.core.CoreConfigContextBuilder
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.discovery.AicpThroughputPolicy
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.nio.channels.OverlappingFileLockException
import java.net.Proxy
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private data class CandidateSelection(
    val guids: List<String>,
    val explorationGuids: List<String>,
    val cursorKey: String?,
    val cursor: Int,
    val poolSize: Int,
)

internal data class AicpThroughputMeasurement(
    val downloadBitsPerSecond: Long = 0L,
    val uploadBitsPerSecond: Long = 0L,
    val downloadBytes: Long = 0L,
    val uploadBytes: Long = 0L,
    val downloadDurationMillis: Long = 0L,
    val uploadDurationMillis: Long = 0L,
    val measuredAtMillis: Long = System.currentTimeMillis(),
    val sampleCount: Int = 0,
    val downloadVariationPercent: Double = 0.0,
    val uploadVariationPercent: Double = 0.0,
    val loadedLatencyMillis: Long = 0L,
    val probeLossPercent: Double = 0.0,
    val error: String? = null,
) {
    val successful: Boolean get() = downloadBitsPerSecond > 0L && uploadBitsPerSecond > 0L
}

/**
 * Measures a small, bounded bidirectional sample through each candidate's own local SOCKS exit.
 * It uses the shared Xray delay instance via openExit; it never creates a second Xray core in-process.
 */
internal object AicpThroughputTester {
    private const val DOWNLOAD_BYTES = 512 * 1024
    private const val UPLOAD_BYTES = 128 * 1024
    private const val SAMPLE_COUNT = 2
    private const val MIN_REMEASURE_INTERVAL_MS = 90L * 60L * 1000L
    private const val LOADED_PROBE_INTERVAL_MS = 250L
    private const val LOADED_PROBE_URL = "https://speed.cloudflare.com/__down?bytes=0"
    private const val MAX_CANDIDATES_UNMETERED = 8
    private const val MAX_CANDIDATES_METERED = 6
    private const val CLOUDFLARE_DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=$DOWNLOAD_BYTES"
    private const val CLOUDFLARE_UPLOAD_URL = "https://speed.cloudflare.com/__up"
    private val batchMutex = Mutex()

    /**
     * Only the Smart Connect recovery cycle is time-boxed (recovery must not wait on a long benchmark).
     * Manual Refresh and country scans are NOT capped: they must measure every reachable profile in their list.
     */
    const val RECOVERY_BUDGET_MS = 15_000L

    /** Lowest measured latency first, so a time-boxed batch measures the likeliest winners before it stops. */
    fun latencyFirst(records: List<Pair<String, Long>>): List<String> =
        records.filter { it.second > 0L }.sortedBy { it.second }.map { it.first }.distinct()

    fun budgetExpired(elapsedMillis: Long, budgetMillis: Long): Boolean =
        budgetMillis > 0L && elapsedMillis >= budgetMillis

    /** Remaining time for one Smart Connect recovery cycle shared across all benchmark groups. */
    fun remainingRecoveryBudgetMillis(startedNanos: Long, nowNanos: Long): Long {
        val elapsedMillis = ((nowNanos - startedNanos).coerceAtLeast(0L) / 1_000_000L)
        return (RECOVERY_BUDGET_MS - elapsedMillis).coerceAtLeast(0L)
    }

    /** Returns the number of candidate records updated (successful samples plus explicit failures). */
    suspend fun measureForGroup(
        context: Context,
        groupId: String,
        network: Network,
        force: Boolean = false,
        eligibleGuids: Set<String>? = null,
        budgetMillis: Long = 0L,
    ): Int = batchMutex.withLock {
        withContext(Dispatchers.IO) {
            val lockFile = java.io.File(context.noBackupFilesDir, "aicp-throughput-batch.lock")
            lockFile.parentFile?.mkdirs()
            RandomAccessFile(lockFile, "rw").use { lockFileHandle ->
                val processLock = try {
                    lockFileHandle.channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (processLock == null) {
                    LogUtil.i(AppConfig.TAG, "AICP throughput batch skipped: another process owns the benchmark lease")
                    return@withContext 0
                }
                try {
                    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val capabilities = connectivity.getNetworkCapabilities(network) ?: return@withContext 0
                    if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) ||
                        !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                        !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return@withContext 0
                    val isUnmetered = capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    val previousNetwork = connectivity.boundNetworkForProcess
                    if (!connectivity.bindProcessToNetwork(network)) return@withContext 0
                    try {
                        val guids = MmkvManager.decodeServerList(groupId).toList()
                        val selection = selectCandidates(groupId, guids, isUnmetered, force, eligibleGuids)
                        val candidates = selection.guids
                        val processedGuids = mutableSetOf<String>()
                        val startedNanos = System.nanoTime()
                        val deadlineNanos = if (budgetMillis > 0L) {
                            startedNanos + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
                        } else null
                        var successful = 0
                        var updated = 0
                        var skipped = 0
                        for (guid in candidates) {
                            currentCoroutineContext().ensureActive()
                            if (budgetExpired((System.nanoTime() - startedNanos) / 1_000_000L, budgetMillis)) {
                                LogUtil.i(AppConfig.TAG, "AICP throughput batch stopped at its ${budgetMillis}ms budget; unmeasured profiles keep latency order")
                                break
                            }
                            processedGuids += guid
                            // Forced batches use one real bidirectional sample per profile; only Smart Connect
                            // supplies a shared deadline, while manual Refresh and country scans remain uncapped.
                            val measurement = measureCandidate(
                                context, guid, quick = force, deadlineNanos = deadlineNanos,
                            )
                            if (measurement == null) {
                                skipped++
                                continue
                            }
                            updated++
                            MmkvManager.encodeServerThroughput(
                                guid = guid,
                                downloadBitsPerSecond = measurement.downloadBitsPerSecond,
                                uploadBitsPerSecond = measurement.uploadBitsPerSecond,
                                measuredAtMillis = measurement.measuredAtMillis,
                                downloadBytes = measurement.downloadBytes,
                                uploadBytes = measurement.uploadBytes,
                                downloadDurationMillis = measurement.downloadDurationMillis,
                                uploadDurationMillis = measurement.uploadDurationMillis,
                                sampleCount = measurement.sampleCount,
                                downloadVariationPercent = measurement.downloadVariationPercent,
                                uploadVariationPercent = measurement.uploadVariationPercent,
                                loadedLatencyMillis = measurement.loadedLatencyMillis,
                                probeLossPercent = measurement.probeLossPercent,
                                error = measurement.error,
                            )
                            if (measurement.successful) successful++
                        }
                        if (selection.cursorKey != null && selection.explorationGuids.isNotEmpty() && selection.poolSize > 0) {
                            val exploredCount = selection.explorationGuids.count { it in processedGuids }
                            if (exploredCount > 0) {
                                MmkvManager.encodeSettings(selection.cursorKey, (selection.cursor + exploredCount) % selection.poolSize)
                            }
                        }
                        LogUtil.i(AppConfig.TAG, "AICP throughput batch group=$groupId unmetered=$isUnmetered attempted=${processedGuids.size}/${candidates.size} updated=$updated successful=$successful skipped=$skipped")
                        updated
                    } finally {
                        connectivity.bindProcessToNetwork(previousNetwork)
                    }
                } finally {
                    processLock.release()
                }
            }
        }
    }

    private fun selectCandidates(
        groupId: String,
        guids: List<String>,
        isUnmetered: Boolean,
        force: Boolean,
        eligibleGuids: Set<String>?,
    ): CandidateSelection {
        val now = System.currentTimeMillis()
        val records = guids.mapNotNull { guid ->
            val info = MmkvManager.decodeServerAffiliationInfo(guid) ?: return@mapNotNull null
            if (info.testDelayMillis <= 0L || (eligibleGuids != null && guid !in eligibleGuids)) return@mapNotNull null
            guid to info
        }
        val dueRecords = if (force) records else records.filter { (_, info) ->
            val measuredAt = info.throughputMeasuredAtMillis
            measuredAt <= 0L || now < measuredAt || now - measuredAt >= MIN_REMEASURE_INTERVAL_MS
        }
        if (dueRecords.isEmpty()) return CandidateSelection(emptyList(), emptyList(), null, 0, 0)
        // Explicit refreshes and country-scan batches must measure every tested, reachable candidate.
        // The bounded rotating shortlist is reserved for background/periodic maintenance.
        if (force) return CandidateSelection(
            latencyFirst(dueRecords.map { it.first to it.second.testDelayMillis }), emptyList(), null, 0, dueRecords.size,
        )
        val topThroughputCount = if (isUnmetered) 3 else 2
        val byThroughput = dueRecords.filter { (_, info) ->
            AicpThroughputPolicy.isFresh(info.downloadBitsPerSecond, info.uploadBitsPerSecond, info.throughputMeasuredAtMillis, now)
        }.sortedByDescending { (_, info) -> throughputScore(info) }
            .take(topThroughputCount).map { it.first }
        val topLatencyCount = when {
            byThroughput.isEmpty() && isUnmetered -> 5
            byThroughput.isEmpty() -> 3
            isUnmetered -> 3
            else -> 2
        }
        val byLatency = dueRecords.sortedBy { it.second.testDelayMillis }.take(topLatencyCount).map { it.first }
        val chosen = (byThroughput + byLatency).distinct().toMutableList()
        val explorationPool = dueRecords.map { it.first }.filterNot { it in chosen }
        var cursorKey: String? = null
        var cursor = 0
        var explorationGuids = emptyList<String>()
        if (explorationPool.isNotEmpty()) {
            cursorKey = "aicp_throughput_exploration_${groupId}_v1"
            cursor = MmkvManager.decodeSettingsInt(cursorKey, 0).coerceAtLeast(0) % explorationPool.size
            val explorationCount = 2
            val takeCount = minOf(explorationCount, explorationPool.size)
            explorationGuids = List(takeCount) { offset -> explorationPool[(cursor + offset) % explorationPool.size] }
            chosen += explorationGuids
        }
        val maxCandidates = if (isUnmetered) MAX_CANDIDATES_UNMETERED else MAX_CANDIDATES_METERED
        return CandidateSelection(chosen.take(maxCandidates), explorationGuids, cursorKey, cursor, explorationPool.size)
    }

    /** Sorts a country bank by fresh bidirectional throughput, then by native latency. */
    fun sortGroupByThroughput(context: Context, groupId: String) {
        AngConfigManager.sortByThroughputForSub(groupId)
    }

    /** Returns the best fresh measured candidate from the supplied, already-tested set. */
    fun fastestCandidate(
        context: Context,
        groupId: String,
        maxDelayMillis: Long,
        eligibleGuids: Set<String>? = null,
    ): Pair<String, Long>? {
        val now = System.currentTimeMillis()
        return MmkvManager.decodeServerList(groupId).asSequence()
            .filter { eligibleGuids == null || it in eligibleGuids }
            .mapNotNull { guid ->
                val info = MmkvManager.decodeServerAffiliationInfo(guid) ?: return@mapNotNull null
                if (info.testDelayMillis <= 0L || info.testDelayMillis > maxDelayMillis) return@mapNotNull null
                if (!AicpThroughputPolicy.isFresh(
                        info.downloadBitsPerSecond,
                        info.uploadBitsPerSecond,
                        info.throughputMeasuredAtMillis,
                        now,
                    )) return@mapNotNull null
                Triple(
                    guid, info.testDelayMillis,
                    AicpThroughputPolicy.routingScore(throughputScore(info), info.testDelayMillis),
                ).takeIf { it.third > 0.0 }
            }
            .maxByOrNull { it.third }
            ?.let { it.first to it.second }
    }

    fun downloadBitsPerSecond(guid: String): Long =
        MmkvManager.decodeServerAffiliationInfo(guid)?.downloadBitsPerSecond?.coerceAtLeast(0L) ?: 0L

    private fun throughputScore(info: ServerAffiliationInfo): Double =
        AicpThroughputPolicy.score(
            info.downloadBitsPerSecond,
            info.uploadBitsPerSecond,
            info.throughputDownloadVariationPercent,
            info.throughputUploadVariationPercent,
            info.throughputLoadedLatencyMillis,
            info.throughputProbeLossPercent,
        )

    private suspend fun measureCandidate(
        context: Context,
        guid: String,
        quick: Boolean = false,
        deadlineNanos: Long? = null,
    ): AicpThroughputMeasurement? = withContext(Dispatchers.IO) {
            val profile = MmkvManager.decodeServerConfig(guid)
                ?: return@withContext null
            // A policy group is a selector, not an independently dialable endpoint; its concrete members
            // are measured as individual candidates when they are present in this bank.
            if (profile.configType == EConfigType.POLICYGROUP) return@withContext null
            try {
                // Do not add benchmark traffic to a live Aether session: it shares the active tunnel's
                // SOCKS path and could contaminate the 3-second health probe. Preserve its cached sample.
                if (profile.configType == EConfigType.AETHER && guid == MmkvManager.getSelectServer() &&
                    hasActiveVpnNetwork(context)) return@withContext null
                if (profile.configType == EConfigType.AETHER && !CoreConfigContextBuilder.isChained(profile)) {
                    var measurement: AicpThroughputMeasurement? = null
                    AetherDelayTester.measureVia(context, guid, AetherCore.of(profile)) { port, deadline ->
                        val effectiveDeadline = deadlineNanos?.let { minOf(it, deadline) } ?: deadline
                        val measured = measureViaSocks(port, effectiveDeadline, quick)
                        measurement = measured
                        if (measured.successful) 1L else -1L
                    }
                    return@withContext measurement
                }

                val config = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
                if (!config.status) return@withContext failure(config.errorMessage)
                val aetherCore = config.aetherCore
                if (aetherCore != null) {
                    var measurement: AicpThroughputMeasurement? = null
                    AetherDelayTester.measureVia(context, guid, aetherCore, config.content) { port, deadline ->
                        val effectiveDeadline = deadlineNanos?.let { minOf(it, deadline) } ?: deadline
                        val measured = measureViaSocks(port, effectiveDeadline, quick)
                        measurement = measured
                        if (measured.successful) 1L else -1L
                    }
                    return@withContext measurement
                }

                val exitConfig = withExitNodeTag(config.content)
                val port = CoreNativeManager.openExit(context, exitConfig)
                try {
                    measureViaSocks(port, deadlineNanos, quick)
                } finally {
                    CoreNativeManager.closeExit()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure(error.message ?: error.javaClass.simpleName)
            }
        }

    /** openExit consumes the outbound tagged exit-node; ordinary profiles expose their primary as proxy. */
    private fun hasActiveVpnNetwork(context: Context): Boolean = runCatching {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }.getOrDefault(true)

    /**
     * Verify useful data transfer through this exact Xray outbound. The caller supplies an absolute
     * deadline; the SOCKS probe requires HTTP 200, non-HTML and at least 16 KiB actually read.
     */
    internal fun verifyRealPayload(
        context: Context,
        content: String,
        urls: List<String>,
        deadlineNanos: Long,
    ): Boolean {
        val exitConfig = withExitNodeTag(content)
        val port = CoreNativeManager.openExit(context, exitConfig)
        return try {
            AetherDelayTester.requestAnyPayload(port, urls, deadlineNanos)
        } finally {
            CoreNativeManager.closeExit()
        }
    }

    private fun withExitNodeTag(content: String): String {
        val root = JsonParser.parseString(content).asJsonObject
        val outbounds = root.getAsJsonArray("outbounds") ?: throw IOException("outbounds missing")
        val primary = outbounds.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IOException("primary outbound missing")
        val protocol = primary.get("protocol")?.takeIf { it.isJsonPrimitive }?.asString
        if (!AicpThroughputPolicy.isBenchmarkableOutbound(protocol)) {
            throw IOException("custom profile has no explicit primary proxy outbound")
        }
        val tagKey = primary.keySet().firstOrNull { it.equals("tag", ignoreCase = true) } ?: "tag"
        primary.addProperty(tagKey, AppConfig.TAG_EXIT_NODE)
        return JsonUtil.toJsonPretty(root) ?: root.toString()
    }

    private data class SingleSample(
        val downloadBitsPerSecond: Long,
        val uploadBitsPerSecond: Long,
        val downloadBytes: Long,
        val uploadBytes: Long,
        val downloadDurationMillis: Long,
        val uploadDurationMillis: Long,
        val error: String? = null,
    ) {
        val successful: Boolean get() = downloadBitsPerSecond > 0L && uploadBitsPerSecond > 0L
    }

    private class LoadedLatencyMonitor(proxy: Proxy) : AutoCloseable {
        private val attempts = AtomicInteger(0)
        private val failures = AtomicInteger(0)
        private val latencies = Collections.synchronizedList(mutableListOf<Long>())
        private val client = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(1L, TimeUnit.SECONDS)
            .readTimeout(1L, TimeUnit.SECONDS)
            .callTimeout(1_500L, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "AICP-throughput-loaded-probe").apply { isDaemon = true }
        }

        fun start() {
            scheduler.scheduleWithFixedDelay({
                attempts.incrementAndGet()
                val started = System.nanoTime()
                try {
                    client.newCall(
                        Request.Builder().url(LOADED_PROBE_URL)
                            .header("Cache-Control", "no-cache")
                            .header("Connection", "close")
                            .get().build()
                    ).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("loaded probe HTTP ${response.code}")
                    }
                    latencies.add(((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L))
                } catch (_: Exception) {
                    failures.incrementAndGet()
                }
            }, 0L, LOADED_PROBE_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }

        fun medianLatencyMillis(): Long = synchronized(latencies) { AicpThroughputPolicy.median(latencies.toList()) }

        fun lossPercent(): Double {
            val total = attempts.get()
            return if (total <= 0) 0.0 else failures.get().toDouble() * 100.0 / total
        }

        override fun close() {
            scheduler.shutdownNow()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun measureViaSocks(port: Int, deadlineNanos: Long?, quick: Boolean = false): AicpThroughputMeasurement {
        if (port !in 1..65_535) return failure("invalid local SOCKS port")
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        val transferClient = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(3L, TimeUnit.SECONDS)
            .readTimeout(8L, TimeUnit.SECONDS)
            .writeTimeout(8L, TimeUnit.SECONDS)
            .callTimeout(8L, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val samples = mutableListOf<SingleSample>()
        val errors = mutableListOf<String>()
        val loadedProbe = LoadedLatencyMonitor(proxy)
        try {
            loadedProbe.start()
            for (index in 0 until (if (quick) 1 else SAMPLE_COUNT)) {
                val remainingMillis = deadlineNanos?.let { (it - System.nanoTime()) / 1_000_000L }
                if (remainingMillis != null && remainingMillis < 2_500L) {
                    errors += "sample ${index + 1}: Aether measurement budget expired"
                    break
                }
                val sample = measureSingleSample(transferClient, deadlineNanos, quick)
                if (sample.successful) {
                    samples += sample
                } else {
                    errors += "sample ${index + 1}: ${sample.error ?: "incomplete transfer"}"
                    // A failed transfer is overwhelmingly likely to fail again on the same profile.
                    // Do not spend a second full timeout retrying an unreachable/unstable endpoint.
                    break
                }
            }
        } finally {
            loadedProbe.close()
            transferClient.dispatcher.cancelAll()
            transferClient.connectionPool.evictAll()
            transferClient.dispatcher.executorService.shutdown()
        }
        if (samples.isEmpty()) return failure(errors.joinToString("; ").ifBlank { "no successful throughput samples" })
        val downloadRates = samples.map { it.downloadBitsPerSecond }
        val uploadRates = samples.map { it.uploadBitsPerSecond }
        return AicpThroughputMeasurement(
            downloadBitsPerSecond = AicpThroughputPolicy.median(downloadRates),
            uploadBitsPerSecond = AicpThroughputPolicy.median(uploadRates),
            downloadBytes = samples.sumOf { it.downloadBytes },
            uploadBytes = samples.sumOf { it.uploadBytes },
            downloadDurationMillis = samples.sumOf { it.downloadDurationMillis },
            uploadDurationMillis = samples.sumOf { it.uploadDurationMillis },
            measuredAtMillis = System.currentTimeMillis(),
            sampleCount = samples.size,
            downloadVariationPercent = AicpThroughputPolicy.coefficientOfVariationPercent(downloadRates),
            uploadVariationPercent = AicpThroughputPolicy.coefficientOfVariationPercent(uploadRates),
            loadedLatencyMillis = loadedProbe.medianLatencyMillis(),
            probeLossPercent = loadedProbe.lossPercent(),
            error = errors.takeIf { it.isNotEmpty() }?.joinToString("; ")?.take(200),
        )
    }

    private fun measureSingleSample(client: OkHttpClient, deadlineNanos: Long?, quick: Boolean = false): SingleSample {
        val remainingMillis = deadlineNanos?.let { (it - System.nanoTime()) / 1_000_000L }
        val timeoutMillis = if (remainingMillis == null) {
            if (quick) 3_000L else 4_000L
        } else minOf(if (quick) 3_000L else 4_000L, remainingMillis / 2L)
        if (timeoutMillis < 1_000L) return SingleSample(0L, 0L, 0L, 0L, 0L, 0L, "insufficient measurement budget")
        val sampleClient = client.newBuilder()
            .connectTimeout(minOf(3_000L, timeoutMillis), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        var downloadBps = 0L
        var uploadBps = 0L
        var downloaded = 0L
        var uploaded = 0L
        var downloadMillis = 0L
        var uploadMillis = 0L
        val errors = mutableListOf<String>()
        try {
            sampleClient.newCall(Request.Builder().url(CLOUDFLARE_DOWNLOAD_URL).get().build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("download HTTP ${response.code}")
                val started = System.nanoTime()
                val input = response.body.byteStream()
                input.use { stream ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        downloaded += count
                    }
                }
                downloadMillis = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            }
            if (downloaded != DOWNLOAD_BYTES.toLong()) throw IOException("download returned $downloaded of $DOWNLOAD_BYTES bytes")
            downloadBps = (downloaded.toDouble() * 8_000.0 / downloadMillis).toLong()
        } catch (error: Exception) {
            errors += "download: ${error.message ?: error.javaClass.simpleName}"
        }

        // Download and upload share a sample budget, but an unreachable download endpoint is
        // enough to reject this profile. Avoid an additional upload timeout against the same host.
        if (downloadBps <= 0L) {
            return SingleSample(0L, 0L, downloaded, 0L, downloadMillis, 0L, errors.joinToString("; ").take(200))
        }

        val uploadRemainingMillis = deadlineNanos?.let { (it - System.nanoTime()) / 1_000_000L }
        if (uploadRemainingMillis == null || uploadRemainingMillis > 1_500L) {
            try {
                val payload = ByteArray(UPLOAD_BYTES)
                val body = object : RequestBody() {
                    var transferNanos: Long = 0L
                    override fun contentType() = "application/octet-stream".toMediaType()
                    override fun contentLength() = payload.size.toLong()
                    override fun writeTo(sink: BufferedSink) {
                        val started = System.nanoTime()
                        sink.write(payload)
                        sink.flush()
                        transferNanos = System.nanoTime() - started
                    }
                }
                sampleClient.newCall(Request.Builder().url(CLOUDFLARE_UPLOAD_URL).post(body).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("upload HTTP ${response.code}")
                    response.body.close()
                }
                uploadMillis = (body.transferNanos / 1_000_000L).coerceAtLeast(1L)
                uploaded = UPLOAD_BYTES.toLong()
                uploadBps = (uploaded.toDouble() * 8_000.0 / uploadMillis).toLong()
            } catch (error: Exception) {
                errors += "upload: ${error.message ?: error.javaClass.simpleName}"
            }
        } else {
            errors += "upload: measurement budget expired"
        }
        return SingleSample(downloadBps, uploadBps, downloaded, uploaded, downloadMillis, uploadMillis,
            errors.takeIf { it.isNotEmpty() }?.joinToString("; ")?.take(200))
    }

    private fun failure(message: String) = AicpThroughputMeasurement(error = message.take(200))
}
