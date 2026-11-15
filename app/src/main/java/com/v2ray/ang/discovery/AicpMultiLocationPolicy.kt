package com.v2ray.ang.discovery

/** Bounded, deterministic recovery order. This selects discovery targets, never claims verified egress. */
internal object AicpMultiLocationPolicy {
    private val preferredRecoveryOrder = listOf("DE", "NL", "FR", "GB", "US", "CH", "SE", "SG")

    fun candidateCountries(preferredCountry: String, supportedCountries: Collection<String>): List<String> {
        val supported = supportedCountries.map { it.trim().uppercase() }.filter { it.matches(Regex("[A-Z]{2}")) }.toSet()
        val preferred = preferredCountry.trim().uppercase()
        require(preferred in supported) { "Unsupported preferred country" }
        return buildList {
            add(preferred)
            preferredRecoveryOrder.filter { it != preferred && it in supported }.forEach(::add)
            supported.sorted().filter { it != preferred && it !in this }.forEach(::add)
        }.distinct()
    }

    const val MAX_ACCEPTED_REAL_PING_MS = 200L
    const val FAILOVER_THRESHOLD_MS = 300L
    const val TEST_BATCH_SIZE = 40

    /** A failed live tunnel preempts only an in-flight Multi-Location candidate batch; its batch is requeued. */
    fun shouldPreemptBatchForSmartConnectFailover(
        multiLocationActive: Boolean,
        awaitingContinue: Boolean,
        batchInFlight: Boolean,
    ): Boolean = multiLocationActive && !awaitingContinue && batchInFlight

    /** A failover is considered only when a positive real-ping is strictly above 300 ms. */
    fun shouldFailOver(delayMillis: Long?): Boolean = delayMillis != null && delayMillis > FAILOVER_THRESHOLD_MS

    /** A failed tunnel probe is a profile failure only when a non-VPN network still has internet. */
    fun shouldFailOver(delayMillis: Long?, underlyingInternetAvailable: Boolean): Boolean =
        delayMillis != null && (delayMillis > FAILOVER_THRESHOLD_MS || (delayMillis <= 0L && underlyingInternetAvailable))

    /** Only a positive measured real-ping at or below 200 ms qualifies. */
    fun qualifiesRealPing(delayMillis: Long?): Boolean = delayMillis != null && delayMillis in 1L..MAX_ACCEPTED_REAL_PING_MS

    /** During a country scan, only measured <=200 ms candidates survive into the qualified country bank. */
    fun shouldPruneMultiLocationCandidate(delayMillis: Long?): Boolean = !qualifiesRealPing(delayMillis)

    /** Source-bank deletions must remove their Multi-Location copy; legacy untagged entries use conservative fallback. */
    fun shouldRemoveMultiLocationCopy(
        hasKnownOrigin: Boolean,
        removedFromOriginBank: Boolean,
        identityStillAvailable: Boolean,
        removedFromAnyBank: Boolean,
    ): Boolean = if (hasKnownOrigin) removedFromOriginBank
        else !identityStillAvailable || removedFromAnyBank

    /** Test every candidate without sending an unbounded batch to the service at once. */
    fun candidateBatches(candidates: List<String>, batchSize: Int = TEST_BATCH_SIZE): List<List<String>> {
        require(batchSize > 0) { "Batch size must be positive" }
        return candidates.chunked(batchSize)
    }
}
