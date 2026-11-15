package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BoundedProbeExecutorTest {
    @Test
    fun slowProbeIsCancelledAtItsDeadline() {
        BoundedProbeExecutor(1).use { executor ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val exited = CountDownLatch(1)
            val started = System.nanoTime()
            val result = executor.run(50) {
                entered.countDown()
                try {
                    while (release.count > 0L) {
                        try {
                            release.await()
                        } catch (_: InterruptedException) {
                            // Simulate a resolver/native call that does not promptly honor interruption.
                        }
                    }
                    1L
                } finally {
                    exited.countDown()
                }
            }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertNull(result)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(elapsed < 500L)
            // A stuck resolver consumes one bounded worker, but never blocks this caller or grows a queue.
            assertNull(executor.run(50) { 2L })
            release.countDown()
            assertTrue(exited.await(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun socketConnectProbeCompletesAgainstALocalListener() {
        ServerSocket(0).use { server ->
            val delay = SpeedtestManager.socketConnectTime("127.0.0.1", server.localPort, 500)
            assertTrue(delay >= 0L)
        }
    }

    @Test
    fun fastProbeReturnsItsResult() {
        BoundedProbeExecutor(1).use { executor ->
            assertEquals(42L, executor.run(500) { 42L } ?: -1L)
        }
    }
}
