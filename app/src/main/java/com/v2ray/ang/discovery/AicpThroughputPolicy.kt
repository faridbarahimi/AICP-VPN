package com.v2ray.ang.discovery

/** Pure ranking rules for measured bidirectional throughput; all rates are bits per second. */
internal object AicpThroughputPolicy {
    const val MAX_METRIC_AGE_MILLIS = 2L * 60L * 60L * 1000L
    private val benchmarkableOutboundProtocols = setOf(
        "vless", "vmess", "trojan", "shadowsocks", "shadowsocks2022", "socks", "http", "wireguard",
        "hysteria", "hysteria2", "tuic", "ssh", "masque",
    )

    fun isBenchmarkableOutbound(protocol: String?): Boolean =
        protocol?.trim()?.lowercase()?.let { it in benchmarkableOutboundProtocols } ?: false

    fun median(values: List<Long>): Long {
        val sorted = values.filter { it >= 0L }.sorted()
        if (sorted.isEmpty()) return 0L
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else ((sorted[middle - 1].toDouble() + sorted[middle].toDouble()) / 2.0).toLong()
    }

    fun coefficientOfVariationPercent(values: List<Long>): Double {
        val positive = values.filter { it > 0L }
        if (positive.size < 2) return 0.0
        val mean = positive.average()
        if (mean <= 0.0) return 0.0
        val variance = positive.sumOf { value ->
            val delta = value.toDouble() - mean
            delta * delta
        } / positive.size
        return (kotlin.math.sqrt(variance) / mean * 100.0).coerceIn(0.0, 500.0)
    }

    fun score(
        downloadBitsPerSecond: Long,
        uploadBitsPerSecond: Long,
        downloadVariationPercent: Double = 0.0,
        uploadVariationPercent: Double = 0.0,
        loadedLatencyMillis: Long = 0L,
        probeLossPercent: Double = 0.0,
    ): Double {
        if (downloadBitsPerSecond <= 0L || uploadBitsPerSecond <= 0L) return 0.0
        val throughput = kotlin.math.sqrt(downloadBitsPerSecond.toDouble() * uploadBitsPerSecond.toDouble())
        val variation = (downloadVariationPercent.coerceIn(0.0, 500.0) + uploadVariationPercent.coerceIn(0.0, 500.0)) / 200.0
        val confidenceFactor = 1.0 / (1.0 + variation)
        val latencyFactor = if (loadedLatencyMillis > 0L) 1.0 / (1.0 + loadedLatencyMillis / 1_000.0) else 1.0
        val lossFraction = probeLossPercent.coerceIn(0.0, 100.0) / 100.0
        val lossFactor = (1.0 - lossFraction) * (1.0 - lossFraction)
        return throughput * confidenceFactor * latencyFactor * lossFactor
    }

    /**
     * Smart-routing latency discount for a profile's measured real-ping. Throughput alone can crown a
     * high-bandwidth but slow-to-respond route; this keeps interactive latency in the decision while
     * still letting a clearly higher-throughput profile win. Unmeasured/non-positive latency is neutral.
     */
    fun latencyRoutingFactor(latencyMillis: Long): Double =
        if (latencyMillis <= 0L) 1.0 else 1.0 / (1.0 + latencyMillis / LATENCY_ROUTING_SCALE_MS)

    fun routingScore(throughputScore: Double, latencyMillis: Long): Double =
        if (throughputScore <= 0.0) 0.0 else throughputScore * latencyRoutingFactor(latencyMillis)

    private const val LATENCY_ROUTING_SCALE_MS = 1_500.0

    fun isFresh(
        downloadBitsPerSecond: Long,
        uploadBitsPerSecond: Long,
        measuredAtMillis: Long,
        nowMillis: Long,
        maxAgeMillis: Long = MAX_METRIC_AGE_MILLIS,
    ): Boolean = downloadBitsPerSecond > 0L && uploadBitsPerSecond > 0L && measuredAtMillis > 0L &&
        nowMillis >= measuredAtMillis && nowMillis - measuredAtMillis <= maxAgeMillis

    /** Ascending sort key: fresh throughput first, then measured latency, then unmeasured entries. */
    fun sortKey(
        downloadBitsPerSecond: Long,
        uploadBitsPerSecond: Long,
        measuredAtMillis: Long,
        nowMillis: Long,
        latencyMillis: Long,
        downloadVariationPercent: Double = 0.0,
        uploadVariationPercent: Double = 0.0,
        loadedLatencyMillis: Long = 0L,
        probeLossPercent: Double = 0.0,
    ): Long {
        if (isFresh(downloadBitsPerSecond, uploadBitsPerSecond, measuredAtMillis, nowMillis)) {
            val measuredScore = routingScore(
                score(
                    downloadBitsPerSecond, uploadBitsPerSecond, downloadVariationPercent, uploadVariationPercent,
                    loadedLatencyMillis, probeLossPercent,
                ),
                latencyMillis,
            )
            if (measuredScore > 0.0) return -measuredScore.toLong().coerceAtLeast(1L)
        }
        return if (latencyMillis > 0L) Long.MAX_VALUE / 2L + latencyMillis else Long.MAX_VALUE
    }
}
