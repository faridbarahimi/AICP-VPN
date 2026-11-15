package com.v2ray.ang.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AicpThroughputPolicyTest {
    @Test
    fun `balanced score uses geometric mean and rejects missing direction`() {
        assertEquals(50_000_000.0, AicpThroughputPolicy.score(100_000_000L, 25_000_000L), 0.01)
        assertEquals(0.0, AicpThroughputPolicy.score(100_000_000L, 0L), 0.0)
        assertEquals(0.0, AicpThroughputPolicy.score(0L, 25_000_000L), 0.0)
    }

    @Test
    fun `custom config benchmark requires an explicit proxy outbound`() {
        assertTrue(AicpThroughputPolicy.isBenchmarkableOutbound("vless"))
        assertTrue(AicpThroughputPolicy.isBenchmarkableOutbound("wireguard"))
        assertFalse(AicpThroughputPolicy.isBenchmarkableOutbound("freedom"))
        assertFalse(AicpThroughputPolicy.isBenchmarkableOutbound("blackhole"))
        assertFalse(AicpThroughputPolicy.isBenchmarkableOutbound("selector"))
        assertFalse(AicpThroughputPolicy.isBenchmarkableOutbound(null))
    }

    @Test
    fun `median and variation describe repeated samples robustly`() {
        assertEquals(200L, AicpThroughputPolicy.median(listOf(100L, 300L)))
        assertEquals(200L, AicpThroughputPolicy.median(listOf(100L, 200L, 300L)))
        assertEquals(50.0, AicpThroughputPolicy.coefficientOfVariationPercent(listOf(100L, 300L)), 0.01)
        assertEquals(0.0, AicpThroughputPolicy.coefficientOfVariationPercent(listOf(100L)), 0.0)
    }

    @Test
    fun `ranking penalizes unstable loaded and lossy samples`() {
        val base = AicpThroughputPolicy.score(100_000_000L, 50_000_000L)
        val unstable = AicpThroughputPolicy.score(100_000_000L, 50_000_000L, 80.0, 80.0)
        val loaded = AicpThroughputPolicy.score(100_000_000L, 50_000_000L, loadedLatencyMillis = 900L)
        val lossy = AicpThroughputPolicy.score(100_000_000L, 50_000_000L, probeLossPercent = 20.0)
        assertTrue(base > unstable)
        assertTrue(base > loaded)
        assertTrue(base > lossy)
    }

    @Test
    fun `freshness rejects stale and future measurements`() {
        val now = 10_000_000L
        assertTrue(AicpThroughputPolicy.isFresh(100L, 50L, now - 1_000L, now, 2_000L))
        assertFalse(AicpThroughputPolicy.isFresh(100L, 50L, now - 2_001L, now, 2_000L))
        assertFalse(AicpThroughputPolicy.isFresh(100L, 50L, now + 1L, now, 2_000L))
        assertFalse(AicpThroughputPolicy.isFresh(100L, 0L, now - 1_000L, now, 2_000L))
    }

    @Test
    fun `sort key ranks faster fresh throughput before latency-only profiles`() {
        val now = 10_000_000L
        val fast = AicpThroughputPolicy.sortKey(100_000_000L, 25_000_000L, now, now, 150L)
        val slow = AicpThroughputPolicy.sortKey(25_000_000L, 10_000_000L, now, now, 40L)
        val latencyOnly = AicpThroughputPolicy.sortKey(0L, 0L, 0L, now, 20L)
        assertTrue(fast < slow)
        assertTrue(slow < latencyOnly)
    }
}

class AicpSmartRoutingScoreTest {
    private val now = 1_000_000_000L

    @Test
    fun `unmeasured latency is neutral and high latency is discounted`() {
        assertEquals(1.0, AicpThroughputPolicy.latencyRoutingFactor(0L), 0.0)
        assertEquals(1.0, AicpThroughputPolicy.latencyRoutingFactor(-5L), 0.0)
        assertTrue(AicpThroughputPolicy.latencyRoutingFactor(100L) > AicpThroughputPolicy.latencyRoutingFactor(3_000L))
        assertEquals(0.0, AicpThroughputPolicy.routingScore(0.0, 100L), 0.0)
    }

    @Test
    fun `slightly faster but very slow to respond profile no longer outranks a snappy one`() {
        val snappy = AicpThroughputPolicy.sortKey(20_000_000L, 10_000_000L, now, now, 80L)
        val laggy = AicpThroughputPolicy.sortKey(24_000_000L, 12_000_000L, now, now, 4_000L)
        assertTrue(snappy < laggy, "lower key sorts first")
    }

    @Test
    fun `clearly higher throughput still wins over a modest latency gap`() {
        val fast = AicpThroughputPolicy.sortKey(200_000_000L, 100_000_000L, now, now, 400L)
        val slow = AicpThroughputPolicy.sortKey(20_000_000L, 10_000_000L, now, now, 60L)
        assertTrue(fast < slow)
    }
}
