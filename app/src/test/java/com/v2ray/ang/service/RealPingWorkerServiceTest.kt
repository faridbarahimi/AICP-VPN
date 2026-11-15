package com.v2ray.ang.service

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RealPingWorkerServiceTest {
    @Test
    fun candidateProbeDeadlinesAreSlightlyMoreTolerant() {
        assertEquals(7_000L, RealPingTimeoutPolicy.NATIVE_PING_TIMEOUT_MS)
        assertEquals(8_000L, RealPingTimeoutPolicy.AETHER_PING_TIMEOUT_MS)
        assertEquals(1_800, RealPingTimeoutPolicy.TCP_REACHABILITY_TIMEOUT_MS)
        assertEquals(14_000L, com.v2ray.ang.core.AetherDelayTester.TEST_BUDGET_MS)
    }

    @Test
    fun slowNativeProbeIsCancelledAtItsDeadline() = runBlocking {
        val cancelled = CountDownLatch(1)
        val startedAt = System.nanoTime()
        val result = RealPingProbeDeadline.run(
            timeoutMillis = 60,
            onTimeout = { cancelled.countDown() },
        ) {
            check(cancelled.await(1, TimeUnit.SECONDS))
            -1L
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertEquals(-1L, result ?: -1L)
        assertEquals(true, elapsedMillis < 500L)
    }

    @Test
    fun timedOutConfigurationIsSkippedAndTheNextConfigurationCanBeTested() = runBlocking {
        var timedOut = false
        val slowResult = RealPingProbeDeadline.run(
            timeoutMillis = 40,
            onTimeout = { timedOut = true },
        ) {
            kotlinx.coroutines.awaitCancellation()
        }
        assertNull(slowResult)
        assertEquals(true, timedOut)

        // The batch advances instead of retrying the same failed configuration.
        val nextConfigResult = RealPingProbeDeadline.run(
            timeoutMillis = 250,
            onTimeout = { error("A fast next configuration must not time out") },
        ) { 41L }
        assertEquals(41L, nextConfigResult)
    }

    @Test
    fun successfulFastProbeDoesNotTriggerCancellation() = runBlocking {
        var cancelled = false
        val result = RealPingProbeDeadline.run(
            timeoutMillis = 250,
            onTimeout = { cancelled = true },
        ) { 23L }
        Thread.sleep(300)
        assertEquals(23L, result)
        assertEquals(false, cancelled)
    }

    @Test
    fun successfulAndExplicitFailureResultsArePreserved() {
        assertEquals(37L, runRealPingProbe(probe = { 37L }))
        assertEquals(-1L, runRealPingProbe(probe = { -1L }))
    }

    @Test
    fun thrownProbeIsRecordedAsUnreachableInsteadOfBeingSilentlyLeftUnmeasured() {
        var reported: Throwable? = null
        val result = runRealPingProbe(
            probe = { throw IllegalStateException("native probe failed") },
            onFailure = { reported = it },
        )
        assertEquals(-1L, result)
        assertEquals("native probe failed", reported?.message)
    }

    @Test
    fun nonFatalNativeErrorsAreAlsoRecordedAsUnreachable() {
        assertEquals(-1L, runRealPingProbe(probe = { throw AssertionError("native assertion") }))
    }

    @Test
    fun cancellationIsNotConvertedIntoAnUnreachableProfile() {
        assertThrows(CancellationException::class.java) {
            runRealPingProbe(probe = { throw CancellationException("cancelled") })
        }
    }
}
