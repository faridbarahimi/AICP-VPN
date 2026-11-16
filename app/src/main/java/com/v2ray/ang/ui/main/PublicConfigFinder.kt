package com.v2ray.ang.ui.main

import android.net.Network
import com.google.gson.JsonParser
import com.v2ray.ang.discovery.PublicConfigSourceEndpoint
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorCompletionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Fetches country-labelled public feeds. Country labels are upstream GeoIP classifications, not a guarantee of exit IP. */
internal object PublicConfigFinder {
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_CONFIGS = 300
    internal const val MAX_CONFIGS_FOR_REFRESH = MAX_CONFIGS
    private val uriPattern = Regex("(?i)^(vless|vmess|trojan|ss|hysteria2|hy2|tuic)://")

    data class SourceFetchOutcome(
        val sourceId: String,
        val succeeded: Boolean,
        val responseBytes: Int,
        val candidateCount: Int = 0,
        val failureCategory: String? = null,
    )

    data class Result(
        val configs: List<String>,
        val successfulSources: Int,
        val failedSources: Int,
        val sourceOutcomes: List<SourceFetchOutcome> = emptyList(),
    )

    fun sourceUrls(countryCode: String): List<String> = PublicConfigCatalog.sourceUrls(countryCode)

    fun collectConfigs(payloads: List<String>, limit: Int = MAX_CONFIGS): List<String> {
        // Preserve diversity across protocol families instead of letting the largest VLESS feed dominate.
        val lanes = LinkedHashMap<Pair<Int, String>, ArrayDeque<String>>()
        val seen = HashSet<String>()
        payloads.forEachIndexed { sourceIndex, payload ->
            decodePayload(payload).lineSequence().forEach lineLoop@{ raw ->
                val line = raw.trim().removePrefix("\uFEFF")
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@lineLoop
                val candidate = line.substringBefore('|').trim()
                val match = uriPattern.find(candidate) ?: return@lineLoop
                val identity = candidate.substringBefore('#').trim()
                if (identity.isEmpty() || !seen.add(identity)) return@lineLoop
                val protocol = match.groupValues[1].lowercase()
                lanes.getOrPut(sourceIndex to protocol) { ArrayDeque() }.addLast(candidate)
            }
        }
        val result = ArrayList<String>(limit)
        val laneOrder = lanes.keys.toList()
        while (result.size < limit && laneOrder.any { lanes[it]?.isNotEmpty() == true }) {
            laneOrder.forEach { key ->
                if (result.size < limit) lanes[key]?.removeFirstOrNull()?.let(result::add)
            }
        }
        return result
    }

    private fun decodePayload(payload: String): String {
        // The upstream subscription stores each URI as its own Base64 line; decode line-by-line.
        // Also accept plain URI feeds and a single whole-file Base64 payload.
        val trimmed = payload.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return ""
        val lines = trimmed.lineSequence().toList()
        val decodedLines = lines.map { raw ->
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty() || line.startsWith("#") || uriPattern.containsMatchIn(line)) return@map line
            decodeBase64Line(line) ?: line
        }
        val joined = decodedLines.joinToString("\n")
        if (containsSupportedUri(joined)) return joined
        // Some providers Base64-encode the entire subscription including comment headers.
        decodeBase64Text(trimmed)?.takeIf(::containsSupportedUri)?.let { return it }
        return joined
    }

    private fun containsSupportedUri(text: String): Boolean =
        text.lineSequence().any { uriPattern.containsMatchIn(it.trimStart()) }

    private fun decodeBase64Line(value: String): String? =
        decodeBase64Text(value)?.takeIf { uriPattern.containsMatchIn(it.trimStart()) }

    private fun decodeBase64Text(value: String): String? {
        val compact = value.filterNot(Char::isWhitespace)
        if (compact.isEmpty() || compact.any { !(it.isLetterOrDigit() || it in "+/=_-") }) return null
        return runCatching {
            val bytes = try { Base64.getDecoder().decode(compact) } catch (_: IllegalArgumentException) {
                Base64.getUrlDecoder().decode(compact)
            }
            bytes.toString(Charsets.UTF_8)
        }.getOrNull()
    }

    /**
     * Fallback for mobile networks where Android's captive-portal validation endpoint is blocked.
     * Uses fixed public source/connectivity hosts over the selected physical network.
     */
    fun hasReachableSourceHost(network: Network?): Boolean {
        // A null Network must never fall back to URL.openConnection(), which may route over VPN.
        if (network == null) return false
        val probes = listOf(
            "https://raw.githubusercontent.com/Au1rxx/free-vpn-subscriptions/main/README.md",
            "https://cdn.jsdelivr.net/gh/Au1rxx/free-vpn-subscriptions@main/README.md",
            "https://www.cloudflare.com/cdn-cgi/trace"
        )
        return probes.any { source ->
            runCatching {
                val url = URL(source)
                require(url.protocol == "https" && url.host in setOf("raw.githubusercontent.com", "cdn.jsdelivr.net", "raw.githack.com", "www.cloudflare.com"))
                (network.openConnection(url) as HttpURLConnection).run {
                    connectTimeout = 2_500
                    readTimeout = 2_500
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "AICP-VPN-ConnectivityProbe/1.0")
                    try {
                        if (responseCode != HttpURLConnection.HTTP_OK) return@run false
                        inputStream.use { it.read() >= 0 }
                    } finally {
                        disconnect()
                    }
                }
            }.getOrDefault(false)
        }
    }

    fun fetch(countryCode: String, network: Network?): Result {
        // Fail closed if the physical network disappeared between availability check and fetch.
        // Do not let the default URLConnection route discovery through the active VPN tunnel.
        requireNotNull(network) { "No underlying non-VPN network available for direct config discovery" }
        return fetchFromSources(PublicConfigCatalog.sourceEndpoints(countryCode)) {
            fetchTextWithMirrorFallback(it, network)
        }
    }

    /** Interactive fetch: cancellation interrupts the bounded source executor and stops mirror retries. */
    suspend fun fetchCancellable(
        countryCode: String,
        network: Network?,
        onProgress: (completedSources: Int, totalSources: Int, foundConfigs: Int) -> Unit = { _, _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        requireNotNull(network) { "No underlying non-VPN network available for direct config discovery" }
        runInterruptible {
            fetchFromSources(
                PublicConfigCatalog.sourceEndpoints(countryCode),
                fetchBody = { fetchTextWithMirrorFallback(it, network, connectTimeoutMillis = 3_000, readTimeoutMillis = 3_000) },
                onProgress = onProgress,
            )
        }
    }

    /** Injectable boundary for deterministic tests of independent provider success/failure. */
    internal fun fetchFromSources(
        sources: List<PublicConfigSourceEndpoint>,
        onProgress: (completedSources: Int, totalSources: Int, foundConfigs: Int) -> Unit = { _, _, _ -> },
        fetchBody: (String) -> String,
    ): Result {
        if (sources.isEmpty()) return Result(emptyList(), successfulSources = 0, failedSources = 0)
        val executor = Executors.newFixedThreadPool(sources.size.coerceAtMost(3).coerceAtLeast(1))
        data class Fetched(val source: PublicConfigSourceEndpoint, val body: String?, val failure: String?)
        val fetched: List<Fetched> = try {
            val completion = ExecutorCompletionService<Fetched>(executor)
            sources.forEach { source -> completion.submit(Callable {
                runCatching { fetchBody(source.url) }
                    .fold(
                        onSuccess = { body -> Fetched(source, body, null) },
                        onFailure = { error -> Fetched(source, null, error.javaClass.simpleName.ifBlank { "fetch_failed" }) },
                    )
            }) }
            val completed = ArrayList<Fetched>(sources.size)
            repeat(sources.size) {
                val item = completion.take().get()
                completed += item
                val foundSoFar = collectConfigs(completed.mapNotNull { fetchedItem ->
                    fetchedItem.body?.let { decodeSourcePayload(fetchedItem.source, it) }
                }).size
                onProgress(completed.size, sources.size, foundSoFar)
            }
            // Preserve the configured source order for deterministic lane balancing and output.
            completed.sortedBy { item -> sources.indexOf(item.source) }
        } finally {
            executor.shutdownNow()
        }
        val outcomes = fetched.map { item ->
            val sourceConfigs = item.body?.let { body ->
                collectConfigs(listOf(decodeSourcePayload(item.source, body)))
            }.orEmpty()
            SourceFetchOutcome(
                sourceId = item.source.id,
                succeeded = item.body != null,
                responseBytes = item.body?.toByteArray(Charsets.UTF_8)?.size ?: 0,
                candidateCount = sourceConfigs.size,
                failureCategory = item.failure,
            )
        }
        return Result(
            configs = collectConfigs(fetched.mapNotNull { item -> item.body?.let { decodeSourcePayload(item.source, it) } }),
            successfulSources = outcomes.count { it.succeeded },
            failedSources = outcomes.count { !it.succeeded },
            sourceOutcomes = outcomes,
        )
    }

    /** Extracts the selected country only from structured country-indexed sources. */
    private fun decodeSourcePayload(source: PublicConfigSourceEndpoint, body: String): String = when (source.id) {
        "lagzian-ss-collector" -> runCatching {
            val country = requireNotNull(source.countryCode).lowercase(java.util.Locale.ROOT)
            val root = JsonParser.parseString(body).asJsonObject
            val countryData = root.getAsJsonObject("per_country_proto")?.getAsJsonObject(country)
                ?: return@runCatching ""
            countryData.entrySet().flatMap { (_, values) ->
                if (!values.isJsonArray) emptyList() else values.asJsonArray.mapNotNull { value ->
                    value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                }
            }.joinToString("\n").replace("&amp;", "&")
        }.getOrDefault("")
        else -> body
    }

    private fun fetchTextWithMirrorFallback(
        source: String,
        network: Network,
        connectTimeoutMillis: Int = 10_000,
        readTimeoutMillis: Int = 12_000,
    ): String {
        val mirrors = listOfNotNull(source, toJsDelivrMirror(source), toRawGithackMirror(source)).distinct()
        return fetchFirstAvailable(mirrors) { candidate ->
            val separator = if (candidate.contains("?")) "&" else "?"
            val uncachedCandidate = "$candidate${separator}_aicp_refresh=${System.currentTimeMillis()}"
            fetchText(uncachedCandidate, network, connectTimeoutMillis, readTimeoutMillis)
        }
    }

    /** HTTP status failure for a feed; 404/410 on the origin is final because mirrors serve the same path. */
    internal class FeedHttpException(val status: Int) : IllegalStateException("Feed returned HTTP $status") {
        val isFinal: Boolean get() = status == 404 || status == 410
    }

    /** Try mirrors in order; stop immediately on a final HTTP failure instead of burning every timeout. */
    internal fun fetchFirstAvailable(mirrors: List<String>, fetchOne: (String) -> String): String {
        var lastFailure: Exception? = null
        mirrors.forEachIndexed { index, candidate ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Public config fetch cancelled")
            try {
                return fetchOne(candidate)
            } catch (error: Exception) {
                // Only the origin's 404/410 is authoritative; a mirror 404 may just be an uncached path.
                if (index == 0 && error is FeedHttpException && error.isFinal) throw error
                if (error is InterruptedException || Thread.currentThread().isInterrupted) {
                    Thread.currentThread().interrupt()
                    throw error
                }
                lastFailure?.let(error::addSuppressed)
                lastFailure = error
            }
        }
        throw lastFailure ?: IllegalStateException("No permitted source URL")
    }

    private fun toJsDelivrMirror(source: String): String? = runCatching {
        val url = URL(source)
        if (url.protocol != "https" || url.host != "raw.githubusercontent.com") return null
        val parts = url.path.removePrefix("/").split('/')
        if (parts.size < 4 || parts.any { it.isBlank() || it == "." || it == ".." }) return null
        "https://cdn.jsdelivr.net/gh/${parts[0]}/${parts[1]}@${parts[2]}/${parts.drop(3).joinToString("/")}"
    }.getOrNull()

    /** Secondary public mirror for networks that block GitHub Raw and jsDelivr. */
    private fun toRawGithackMirror(source: String): String? = runCatching {
        val url = URL(source)
        if (url.protocol != "https") return null
        val parts = url.path.removePrefix("/").split('/')
        when (url.host) {
            "raw.githubusercontent.com" -> {
                if (parts.size < 4 || parts.any { it.isBlank() || it == "." || it == ".." }) return null
                "https://raw.githack.com/${parts[0]}/${parts[1]}/${parts[2]}/${parts.drop(3).joinToString("/")}"
            }
            "cdn.jsdelivr.net" -> {
                if (parts.size < 5 || parts[0] != "gh" || parts.any { it.isBlank() || it == "." || it == ".." }) return null
                val repoAndRef = parts[2].split("@", limit = 2)
                if (repoAndRef.size != 2) return null
                "https://raw.githack.com/${parts[1]}/${repoAndRef[0]}/${repoAndRef[1]}/${parts.drop(3).joinToString("/")}"
            }
            else -> null
        }
    }.getOrNull()

    private fun fetchText(
        source: String,
        network: Network,
        connectTimeoutMillis: Int = 10_000,
        readTimeoutMillis: Int = 12_000,
    ): String {
        val url = URL(source)
        require(url.protocol == "https" && url.host in setOf("raw.githubusercontent.com", "cdn.jsdelivr.net", "raw.githack.com")) { "Feed host is not allowed" }
        require(!url.path.split('/').any { it == ".." }) { "Feed path is not allowed" }
        val connection = (network.openConnection(url) as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", "AICP-VPN-PublicConfigFinder/1.0")
            setRequestProperty("Accept", "text/plain, */*")
            setRequestProperty("Cache-Control", "no-cache, no-store")
            setRequestProperty("Pragma", "no-cache")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw FeedHttpException(status)
            val declaredLength = connection.contentLengthLong
            require(declaredLength <= MAX_BYTES || declaredLength < 0) { "Feed too large" }
            BufferedInputStream(connection.inputStream).use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Public config fetch cancelled")
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_BYTES) { "Feed too large" }
                    out.write(buffer, 0, read)
                }
                return out.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }
}


/** Startup/update refresh throttle. It only gates network work; it never claims a proxy is usable. */
internal object AicpRefreshPolicy {
    const val COOLDOWN_MS = 19L * 60L * 1000L

    fun shouldRefresh(
        hasUsableInternet: Boolean,
        lastSuccessMillis: Long,
        nowMillis: Long,
        force: Boolean = false,
    ): Boolean = hasUsableInternet && (
        force || lastSuccessMillis <= 0L || nowMillis < lastSuccessMillis || nowMillis - lastSuccessMillis >= COOLDOWN_MS
    )
}

/** Latency-only policy for country banks; a fast probe is not proof that device traffic traverses the VPN. */
internal object AicpFinderQualification {
    const val MAX_DELAY_MS = Long.MAX_VALUE

    // A slow but working profile is still a valid fallback. Prune only a measured failure or an absent result.
    // Null/zero means unmeasured or skipped; only a negative measured result is a confirmed failure.
    fun shouldPrune(delayMillis: Long?): Boolean = delayMillis != null && delayMillis < 0L

    fun fastestLatencyCandidate(
        results: Map<String, Long?>,
        maxDelayMillis: Long = MAX_DELAY_MS,
    ): Pair<String, Long>? {
        if (maxDelayMillis < 1L) return null
        var best: Pair<String, Long>? = null
        results.forEach { (guid, delay) ->
            if (delay != null && delay in 1L..maxDelayMillis && delay < (best?.second ?: Long.MAX_VALUE)) {
                best = guid to delay
            }
        }
        return best
    }
}


/** Existing-list refresh treats any positive real ping as reachable; latency affects order, not validity. */

/** Exact selected-list boundary for automatic recovery; Multi-Location is one cross-country list. */
internal object AicpFailoverScopePolicy {
    fun candidates(selectedGroupId: String, activeGuid: String, selectedGroupGuids: List<String>): List<String> =
        if (selectedGroupId.isBlank()) emptyList()
        else selectedGroupGuids.asSequence().filter { it != activeGuid }.distinct().toList()
}

internal object AicpExistingListRefreshPolicy {
    fun isAicpGroup(groupId: String): Boolean =
        groupId.startsWith("aicp-finder-") || groupId == "aicp-multilocation"

    fun shouldBenchmarkExistingList(groupId: String): Boolean = groupId.isNotBlank()

    /** A blank ID is the synthetic All-servers page; sort its real subscription indexes individually. */
    fun groupsToSort(groupId: String, availableGroupIds: List<String>): List<String> =
        if (groupId.isBlank()) availableGroupIds.filter { it.isNotBlank() }.distinct() else listOf(groupId)

    fun shouldSelectFastestAfterRefresh(groupId: String, tunnelRunning: Boolean): Boolean =
        shouldBenchmarkExistingList(groupId) && !tunnelRunning

    fun shouldRemove(delayMillis: Long?): Boolean = delayMillis != null && delayMillis < 0L
}


/** Guarded recovery when the active VPN service unexpectedly stops. */
internal object AicpAutoReconnectPolicy {
    const val DISCONNECT_RECONNECT_DELAY_MS = 3_000L
    const val RECOVERY_IDLE_POLL_MS = 250L
    const val RECOVERY_START_TIMEOUT_POLLS = 120 // 30 seconds for refresh preparation to start pinging

    fun shouldSchedule(wasRunning: Boolean, manualStopRequested: Boolean, smartConnectEnabled: Boolean): Boolean =
        wasRunning && !manualStopRequested && smartConnectEnabled

    fun shouldWaitForIdle(isTesting: Boolean, isRefreshingConfigs: Boolean, multiLocationActive: Boolean): Boolean =
        isTesting || isRefreshingConfigs || multiLocationActive
}

/** Timing and eligibility contract for the connected-tunnel monitor. */
internal object AicpSmartConnectPolicy {
    // Smart Connect probes the live tunnel every 60s when healthy and every 5s while recovering.
    const val NORMAL_PROBE_INTERVAL_MS = 60_000L
    const val RECOVERY_PROBE_INTERVAL_MS = 5_000L
    const val ACTIVE_TUNNEL_PROBE_TIMEOUT_MS = 5_000L
    const val UNHEALTHY_PERSISTENCE_MS = 10_000L
    const val HEALTHY_PROBES_TO_EXIT_RECOVERY = 2
    const val UNHEALTHY_LATENCY_THRESHOLD_MS = 300L
    const val MIN_FAILOVER_IMPROVEMENT_PERCENT = 10L
    const val MIN_DIRECT_SPEED_BYTES_PER_SECOND = 256L * 1024L
    const val MIN_SPEED_GAP_BYTES_PER_SECOND = 192L * 1024L
    const val MAX_TUNNEL_TO_DIRECT_RATIO = 0.35
    const val DEGRADED_SAMPLES_TO_FAILOVER = 3

    /** Compare like-for-like upload/download counters from the two existing speed graphs. */
    fun isTunnelMuchSlower(
        directDownloadBytesPerSecond: Double,
        tunnelDownloadBytesPerSecond: Double,
        directUploadBytesPerSecond: Double,
        tunnelUploadBytesPerSecond: Double,
    ): Boolean {
        fun degraded(direct: Double, tunnel: Double): Boolean =
            direct.isFinite() && tunnel.isFinite() &&
                direct >= MIN_DIRECT_SPEED_BYTES_PER_SECOND &&
                direct - tunnel.coerceAtLeast(0.0) >= MIN_SPEED_GAP_BYTES_PER_SECOND &&
                (tunnel <= 0.0 || tunnel <= direct * MAX_TUNNEL_TO_DIRECT_RATIO)
        return degraded(directDownloadBytesPerSecond, tunnelDownloadBytesPerSecond) ||
            degraded(directUploadBytesPerSecond, tunnelUploadBytesPerSecond)
    }

    /** A replacement must beat the active profile by a material download-speed margin. */
    fun isBetterSpeedCandidate(activeDownloadBitsPerSecond: Long, candidateDownloadBitsPerSecond: Long): Boolean {
        if (candidateDownloadBitsPerSecond <= 0L) return false
        if (activeDownloadBitsPerSecond <= 0L) return true
        val requiredGain = maxOf(512_000L, activeDownloadBitsPerSecond / 2L)
        return candidateDownloadBitsPerSecond - activeDownloadBitsPerSecond >= requiredGain
    }

    /** A tunnel-health failure ranks by live latency; speed-only degradation uses throughput evidence. */
    fun shouldPreferLatencyFailover(unhealthyDelayMillis: Long?): Boolean = unhealthyDelayMillis != null

    /** A failover must be measurably better; a dead tunnel accepts any positive candidate. */
    fun isBetterFailoverCandidate(activeDelayMillis: Long?, candidateDelayMillis: Long?): Boolean {
        if (candidateDelayMillis == null || candidateDelayMillis <= 0L) return false
        if (activeDelayMillis == null || activeDelayMillis <= 0L) return true
        if (activeDelayMillis <= UNHEALTHY_LATENCY_THRESHOLD_MS) return false
        val requiredImprovement = maxOf(25L, activeDelayMillis / MIN_FAILOVER_IMPROVEMENT_PERCENT)
        return candidateDelayMillis <= activeDelayMillis - requiredImprovement
    }

    fun probeIntervalMs(recoveryMode: Boolean): Long =
        if (recoveryMode) RECOVERY_PROBE_INTERVAL_MS else NORMAL_PROBE_INTERVAL_MS

    /** A single spike is not a failover reason; degradation must persist across elapsed time. */
    fun hasSustainedUnhealthy(
        delayMillis: Long?,
        unhealthySinceMillis: Long?,
        nowMillis: Long,
    ): Boolean = isUnhealthy(delayMillis) && unhealthySinceMillis != null &&
        nowMillis >= unhealthySinceMillis && nowMillis - unhealthySinceMillis >= UNHEALTHY_PERSISTENCE_MS

    fun isUnhealthy(delayMillis: Long?): Boolean =
        delayMillis != null && (delayMillis > UNHEALTHY_LATENCY_THRESHOLD_MS || delayMillis <= 0L)

    fun isHealthy(delayMillis: Long?): Boolean =
        delayMillis != null && delayMillis in 1L..UNHEALTHY_LATENCY_THRESHOLD_MS

    fun nextHealthyProbeCount(recoveryMode: Boolean, currentCount: Int, delayMillis: Long?): Int =
        if (!recoveryMode || !isHealthy(delayMillis)) 0 else currentCount + 1

    fun shouldExitRecovery(healthyProbeCount: Int): Boolean =
        healthyProbeCount >= HEALTHY_PROBES_TO_EXIT_RECOVERY

    /** A tunnel failure is a profile failure only when a real direct probe confirmed the physical network works. */
    fun mayFailOverWithDirect(directReachable: Boolean?): Boolean = directReachable == true

    /** A delayed health result belongs only to the profile that was selected when its probe began. */
    fun isCurrentProfile(probedGuid: String?, selectedGuid: String?): Boolean =
        !probedGuid.isNullOrBlank() && probedGuid == selectedGuid

    /** A recovery result may only apply while the original profile and list are still selected. */
    fun isCurrentRecoveryContext(
        sourceGuid: String?,
        selectedGuid: String?,
        sourceGroupId: String?,
        selectedGroupId: String?,
    ): Boolean = isCurrentProfile(sourceGuid, selectedGuid) &&
        sourceGroupId != null && selectedGroupId != null &&
        (sourceGroupId == selectedGroupId || selectedGroupId.isBlank())

    fun mayApplyRecoveryResult(
        sourceGuid: String?,
        selectedGuid: String?,
        sourceGroupId: String?,
        selectedGroupId: String?,
        recoveryWasActive: Boolean,
        tunnelStillRunning: Boolean,
        smartConnectStillEnabled: Boolean,
    ): Boolean = recoveryWasActive && tunnelStillRunning && smartConnectStillEnabled &&
        isCurrentRecoveryContext(sourceGuid, selectedGuid, sourceGroupId, selectedGroupId)

    fun shouldProbe(
        enabled: Boolean,
        connected: Boolean,
        currentTestInFlight: Boolean,
        probeInFlight: Boolean,
        recoveryInFlight: Boolean,
        selectedGroupExists: Boolean,
    ): Boolean = enabled && connected && !currentTestInFlight && !probeInFlight &&
        !recoveryInFlight && selectedGroupExists
}
