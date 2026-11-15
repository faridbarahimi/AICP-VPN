package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AicpThroughputBudgetPolicyTest {
    @Test fun latencyFirstOrdersFastestFirstDropsUnreachableAndDeduplicates() {
        val records = listOf("c" to 300L, "a" to 40L, "x" to -1L, "z" to 0L, "b" to 40L, "a" to 40L)
        assertEquals(listOf("a", "b", "c"), AicpThroughputTester.latencyFirst(records))
    }

    @Test fun latencyFirstKeepsEveryReachableProfile() {
        val records = (1..30).map { "g$it" to (1000L - it) }
        val ordered = AicpThroughputTester.latencyFirst(records)
        assertEquals(30, ordered.size)
        assertEquals("g30", ordered.first())
    }

    @Test fun budgetZeroMeansUnlimitedAndExpiryIsInclusive() {
        assertFalse(AicpThroughputTester.budgetExpired(10_000_000L, 0L))
        assertFalse(AicpThroughputTester.budgetExpired(14_999L, AicpThroughputTester.RECOVERY_BUDGET_MS))
        assertTrue(AicpThroughputTester.budgetExpired(15_000L, AicpThroughputTester.RECOVERY_BUDGET_MS))
    }

    @Test fun recoveryBudgetIsSharedAcrossBenchmarkGroups() {
        val start = 1_000_000_000L
        assertEquals(15_000L, AicpThroughputTester.remainingRecoveryBudgetMillis(start, start))
        assertEquals(7_500L, AicpThroughputTester.remainingRecoveryBudgetMillis(start, start + 7_500_000_000L))
        assertEquals(0L, AicpThroughputTester.remainingRecoveryBudgetMillis(start, start + 15_000_000_000L))
        assertEquals(0L, AicpThroughputTester.remainingRecoveryBudgetMillis(start, start + 20_000_000_000L))
    }
}
