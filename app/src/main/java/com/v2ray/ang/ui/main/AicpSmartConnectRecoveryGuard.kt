package com.v2ray.ang.ui.main

/**
 * Anti-oscillation guard for Smart Connect recovery. It replaces a fixed multi-minute wait with a short
 * cooldown plus a per-window cap: a real second failure is acted on within seconds, but the app can never
 * switch/refresh in an endless loop. Pure and clock-injectable; the owner calls canRecover() to decide and
 * recordRecovery() only when a recovery actually starts.
 */
internal class AicpSmartConnectRecoveryGuard(private val nowMillis: () -> Long) {
    private val recoveries = ArrayDeque<Long>()

    fun canRecover(): Boolean {
        val now = nowMillis()
        prune(now)
        val last = recoveries.lastOrNull()
        if (last != null && now - last < COOLDOWN_MS) return false
        return recoveries.size < MAX_RECOVERIES_PER_WINDOW
    }

    fun recordRecovery() {
        val now = nowMillis()
        prune(now)
        recoveries.addLast(now)
    }

    fun reset() = recoveries.clear()

    private fun prune(now: Long) {
        while (recoveries.isNotEmpty() && now - recoveries.first() >= WINDOW_MS) recoveries.removeFirst()
    }

    companion object {
        const val COOLDOWN_MS = 30_000L
        const val WINDOW_MS = 10L * 60L * 1000L
        const val MAX_RECOVERIES_PER_WINDOW = 3
    }
}
