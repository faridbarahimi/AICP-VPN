package com.v2ray.ang.dto.entities

data class ServerAffiliationInfo(
    var testDelayMillis: Long = 0L,
    /** True only after the live egress was verified against the Frankfurt target. */
    var aicpFrankfurtVerified: Boolean = false,
    /** Last measured candidate download throughput, bits per second; zero means unavailable. */
    var downloadBitsPerSecond: Long = 0L,
    /** Last measured candidate upload throughput, bits per second; zero means unavailable. */
    var uploadBitsPerSecond: Long = 0L,
    /** Epoch milliseconds of the last throughput attempt. */
    var throughputMeasuredAtMillis: Long = 0L,
    var throughputDownloadBytes: Long = 0L,
    var throughputUploadBytes: Long = 0L,
    var throughputDownloadDurationMillis: Long = 0L,
    var throughputUploadDurationMillis: Long = 0L,
    /** Number of successful bidirectional samples represented by the stored median rate. */
    var throughputSampleCount: Int = 0,
    /** Coefficient of variation across download samples, as a percentage. */
    var throughputDownloadVariationPercent: Double = 0.0,
    /** Coefficient of variation across upload samples, as a percentage. */
    var throughputUploadVariationPercent: Double = 0.0,
    /** Median HTTP probe latency while download/upload traffic was active; zero means unavailable. */
    var throughputLoadedLatencyMillis: Long = 0L,
    /** Failed loaded-latency probes as a percentage of attempts; zero means no failures or no probe samples. */
    var throughputProbeLossPercent: Double = 0.0,
    /** Non-sensitive short diagnostic for a failed throughput attempt. */
    var throughputError: String? = null,
)
