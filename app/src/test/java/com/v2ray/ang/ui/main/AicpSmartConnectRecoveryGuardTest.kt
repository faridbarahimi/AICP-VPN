package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AicpSmartConnectRecoveryGuardTest {
    private var now = 1_000L
    private val guard = AicpSmartConnectRecoveryGuard { now }

    @Test fun firstRecoveryIsAllowedImmediately() = assertTrue(guard.canRecover())

    @Test fun cooldownBlocksOnlyForSecondsNotMinutes() {
        guard.recordRecovery()
        now += AicpSmartConnectRecoveryGuard.COOLDOWN_MS - 1
        assertFalse(guard.canRecover())
        now += 1
        assertTrue(guard.canRecover())
    }

    @Test fun windowCapStopsAnEndlessRecoveryLoopThenReleases() {
        repeat(AicpSmartConnectRecoveryGuard.MAX_RECOVERIES_PER_WINDOW) {
            assertTrue(guard.canRecover())
            guard.recordRecovery()
            now += AicpSmartConnectRecoveryGuard.COOLDOWN_MS
        }
        assertFalse(guard.canRecover())
        now += AicpSmartConnectRecoveryGuard.WINDOW_MS
        assertTrue(guard.canRecover())
    }

    @Test fun checkingWithoutRecordingNeverConsumesBudget() {
        repeat(20) { assertTrue(guard.canRecover()) }
    }

    @Test fun resetClearsHistory() {
        guard.recordRecovery()
        guard.reset()
        assertTrue(guard.canRecover())
    }
}
