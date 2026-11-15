package com.v2ray.ang.core

import java.net.InetSocketAddress
import java.net.Socket

/** Bounded, local SOCKS5 responsiveness probe; it does not depend on the UI process or public DNS. */
internal object XrayHealthMonitor {
    const val SAMPLE_INTERVAL_MS = 5_000L
    const val STARTUP_GRACE_MS = 8_000L
    const val SOCKET_TIMEOUT_MS = 1_000
    const val FAILURE_THRESHOLD = 3
    const val RECOVERY_COOLDOWN_MS = 30_000L
    const val MAX_RECOVERY_COOLDOWN_MS = 4 * 60_000L
    const val MAX_RECOVERIES_PER_WINDOW = 3
    const val RECOVERY_WINDOW_MS = 5 * 60_000L

    fun shouldRecover(consecutiveFailures: Int): Boolean = consecutiveFailures >= FAILURE_THRESHOLD

    /** Exponential backoff: 30 s after the first recovery in the window, 60 s after the second, ... capped. */
    fun cooldownMillis(recentRecoveries: Int): Long =
        if (recentRecoveries <= 0) 0L
        else (RECOVERY_COOLDOWN_MS shl (recentRecoveries - 1).coerceAtMost(3)).coerceAtMost(MAX_RECOVERY_COOLDOWN_MS)

    fun mayAttemptRecovery(recoveryTimes: List<Long>, nowMillis: Long): Boolean {
        if (nowMillis < 0L) return false
        val recent = recoveryTimes.filter { it <= nowMillis && nowMillis - it < RECOVERY_WINDOW_MS }
        return recent.size < MAX_RECOVERIES_PER_WINDOW &&
            (recent.isEmpty() || nowMillis - recent.last() >= cooldownMillis(recent.size))
    }

    /** Complete a SOCKS5 no-auth greeting, not merely a TCP connect to an open port. */
    fun isSocksResponsive(host: String, port: Int, timeoutMillis: Int = SOCKET_TIMEOUT_MS): Boolean {
        if (host.isBlank() || port !in 1..65535 || timeoutMillis !in 100..5_000) return false
        return runCatching {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.soTimeout = timeoutMillis
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
                socket.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
                socket.getOutputStream().flush()
                val version = socket.getInputStream().read()
                val method = socket.getInputStream().read()
                version == 0x05 && method == 0x00
            }
        }.getOrDefault(false)
    }
}
