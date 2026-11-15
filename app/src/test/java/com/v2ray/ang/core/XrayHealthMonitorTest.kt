package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket

class XrayHealthMonitorTest {
    @Test fun recoveryRequiresThreeConsecutiveFailures() {
        assertFalse(XrayHealthMonitor.shouldRecover(0))
        assertFalse(XrayHealthMonitor.shouldRecover(1))
        assertFalse(XrayHealthMonitor.shouldRecover(2))
        assertTrue(XrayHealthMonitor.shouldRecover(3))
        assertTrue(XrayHealthMonitor.shouldRecover(4))
    }

    @Test fun recoveryIsRateLimitedAndBoundedToThreeAttemptsPerFiveMinutes() {
        assertTrue(XrayHealthMonitor.mayAttemptRecovery(emptyList(), 10_000L))
        assertFalse(XrayHealthMonitor.mayAttemptRecovery(listOf(10_000L), 39_999L))
        assertTrue(XrayHealthMonitor.mayAttemptRecovery(listOf(10_000L), 40_000L))
        assertFalse(XrayHealthMonitor.mayAttemptRecovery(listOf(10_000L, 40_000L, 70_000L), 100_000L))
        assertTrue(XrayHealthMonitor.mayAttemptRecovery(listOf(10_000L, 40_000L, 70_000L), 400_000L))
        assertFalse(XrayHealthMonitor.mayAttemptRecovery(emptyList(), -1L))
    }

    @Test fun socksProbeRequiresAValidNoAuthGreeting() {
        ServerSocket(0).use { server ->
            val responder = Thread {
                server.accept().use { socket ->
                    val greeting = ByteArray(3)
                    socket.getInputStream().read(greeting)
                    socket.getOutputStream().write(byteArrayOf(0x05, 0x00))
                    socket.getOutputStream().flush()
                }
            }.apply { start() }
            assertTrue(XrayHealthMonitor.isSocksResponsive("127.0.0.1", server.localPort))
            responder.join(1_000L)
            assertFalse(responder.isAlive)
        }
    }

    @Test fun invalidSocksProbeArgumentsFailClosed() {
        assertFalse(XrayHealthMonitor.isSocksResponsive("127.0.0.1", 0))
        assertFalse(XrayHealthMonitor.isSocksResponsive("", 10808))
        assertFalse(XrayHealthMonitor.isSocksResponsive("127.0.0.1", 10808, 10))
    }

    @Test fun recoveryCooldownBacksOffExponentiallyAndIsCapped() {
        assertEquals(0L, XrayHealthMonitor.cooldownMillis(0))
        assertEquals(30_000L, XrayHealthMonitor.cooldownMillis(1))
        assertEquals(60_000L, XrayHealthMonitor.cooldownMillis(2))
        assertEquals(240_000L, XrayHealthMonitor.cooldownMillis(9))
        assertFalse(XrayHealthMonitor.mayAttemptRecovery(listOf(10_000L, 40_000L), 99_999L))
        assertTrue(XrayHealthMonitor.mayAttemptRecovery(listOf(10_000L, 40_000L), 100_000L))
    }
}
