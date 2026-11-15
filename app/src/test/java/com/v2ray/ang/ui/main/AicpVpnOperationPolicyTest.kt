package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AicpVpnOperationPolicyTest {
    @Test fun onlyOneForegroundOperationCanOwnTheControlStrip() {
        assertTrue(AicpVpnOperationPolicy.canStart(null))
        assertFalse(AicpVpnOperationPolicy.canStart(VpnOperation.FIND_PUBLIC_CONFIGS))
        assertTrue(AicpVpnOperationPolicy.canStop(VpnOperation.FIND_PUBLIC_CONFIGS, VpnOperation.FIND_PUBLIC_CONFIGS))
        assertFalse(AicpVpnOperationPolicy.canStop(VpnOperation.TEST_REAL_DELAYS, VpnOperation.FIND_PUBLIC_CONFIGS))
    }

    @Test fun smartConnectHealthyPingCadenceIsSixtySeconds() {
        assertEquals(60_000L, AicpVpnOperationPolicy.SMART_CONNECT_PING_INTERVAL_MS)
    }

    @Test fun smartConnectBenchmarksSelectedBankBeforeFirstConnection() {
        assertTrue(AicpVpnOperationPolicy.shouldBenchmarkBeforeConnect(isRunning = false))
        assertFalse(AicpVpnOperationPolicy.shouldBenchmarkBeforeConnect(isRunning = true))
    }

    @Test fun interruptingFinderBatchCancelsAllInFlightSourceFetches() {
        val sources = PublicConfigCatalog.sourceEndpoints("DE").take(3)
        assertEquals(3, sources.size)
        val started = CountDownLatch(sources.size)
        val interrupted = CountDownLatch(sources.size)
        val runner = Thread {
            try {
                PublicConfigFinder.fetchFromSources(sources) {
                    started.countDown()
                    try {
                        Thread.sleep(30_000L)
                        ""
                    } catch (cancelled: InterruptedException) {
                        interrupted.countDown()
                        throw cancelled
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        runner.start()
        assertTrue(started.await(2, TimeUnit.SECONDS), "all bounded source workers should start")
        runner.interrupt()
        runner.join(2_000L)
        assertFalse(runner.isAlive, "interrupt must release the caller instead of waiting for all feeds")
        assertTrue(interrupted.await(2, TimeUnit.SECONDS), "all active feed workers should receive cancellation")
    }
}
