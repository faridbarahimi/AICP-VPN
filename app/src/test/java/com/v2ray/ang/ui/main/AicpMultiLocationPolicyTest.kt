package com.v2ray.ang.ui.main

import com.v2ray.ang.discovery.AicpMultiLocationPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AicpMultiLocationPolicyTest {
    @Test fun preferredCountryAlwaysComesFirstAndRecoveryOrderIsDeterministic() {
        assertEquals(listOf("US", "DE", "NL", "FR", "GB", "CH", "SE", "SG"),
            AicpMultiLocationPolicy.candidateCountries("us", listOf("DE", "NL", "FR", "GB", "US", "CH", "SE", "SG")))
    }

    @Test fun scanIncludesEverySupportedUniqueCountryWithoutTruncation() {
        val supported = listOf("DE", "NL", "NL", "FR", "GB", "US", "CH", "SE", "SG", "CA", "JP", "AU")
        val result = AicpMultiLocationPolicy.candidateCountries("DE", supported)
        assertEquals(11, result.size)
        assertEquals(result.size, result.toSet().size)
        assertEquals("DE", result.first())
        assertTrue(result.containsAll(supported.distinct()))
    }

    @Test fun onlyPositiveRealPingAtOrBelow200Qualifies() {
        assertTrue(AicpMultiLocationPolicy.qualifiesRealPing(1L))
        assertTrue(AicpMultiLocationPolicy.qualifiesRealPing(199L))
        assertTrue(AicpMultiLocationPolicy.qualifiesRealPing(200L))
        assertEquals(false, AicpMultiLocationPolicy.qualifiesRealPing(null))
        assertEquals(false, AicpMultiLocationPolicy.qualifiesRealPing(0L))
        assertEquals(false, AicpMultiLocationPolicy.qualifiesRealPing(201L))
    }

    @Test fun countryDiscoveryPrunesEveryCandidateOutsidePositiveSub200RealPing() {
        assertTrue(AicpMultiLocationPolicy.shouldPruneMultiLocationCandidate(null))
        assertTrue(AicpMultiLocationPolicy.shouldPruneMultiLocationCandidate(0L))
        assertTrue(AicpMultiLocationPolicy.shouldPruneMultiLocationCandidate(-1L))
        assertFalse(AicpMultiLocationPolicy.shouldPruneMultiLocationCandidate(1L))
        assertFalse(AicpMultiLocationPolicy.shouldPruneMultiLocationCandidate(199L))
        assertFalse(AicpMultiLocationPolicy.shouldPruneMultiLocationCandidate(200L))
        assertTrue(AicpMultiLocationPolicy.shouldPruneMultiLocationCandidate(201L))
    }

    @Test fun sourceBankDeletionRemovesItsMultiLocationCopyEvenIfDuplicateExistsElsewhere() {
        assertTrue(AicpMultiLocationPolicy.shouldRemoveMultiLocationCopy(
            hasKnownOrigin = true, removedFromOriginBank = true,
            identityStillAvailable = true, removedFromAnyBank = true,
        ))
        assertEquals(false, AicpMultiLocationPolicy.shouldRemoveMultiLocationCopy(
            hasKnownOrigin = true, removedFromOriginBank = false,
            identityStillAvailable = false, removedFromAnyBank = true,
        ))
    }

    @Test fun legacyMultiLocationEntriesUseRemovalEvidenceWhenOriginIsUnknown() {
        assertTrue(AicpMultiLocationPolicy.shouldRemoveMultiLocationCopy(
            hasKnownOrigin = false, removedFromOriginBank = false,
            identityStillAvailable = true, removedFromAnyBank = true,
        ))
        assertEquals(false, AicpMultiLocationPolicy.shouldRemoveMultiLocationCopy(
            hasKnownOrigin = false, removedFromOriginBank = false,
            identityStillAvailable = true, removedFromAnyBank = false,
        ))
        assertTrue(AicpMultiLocationPolicy.shouldRemoveMultiLocationCopy(
            hasKnownOrigin = false, removedFromOriginBank = false,
            identityStillAvailable = false, removedFromAnyBank = false,
        ))
    }

    @Test fun failoverTriggersOnlyAbove300Milliseconds() {
        assertEquals(false, AicpMultiLocationPolicy.shouldFailOver(null))
        assertEquals(false, AicpMultiLocationPolicy.shouldFailOver(0L))
        assertEquals(false, AicpMultiLocationPolicy.shouldFailOver(300L))
        assertTrue(AicpMultiLocationPolicy.shouldFailOver(301L))
        assertTrue(AicpMultiLocationPolicy.shouldFailOver(850L))
    }

    @Test fun failedActiveProfileFailsOverOnlyWhenUnderlyingInternetIsAvailable() {
        assertFalse(AicpMultiLocationPolicy.shouldFailOver(-1L, false))
        assertFalse(AicpMultiLocationPolicy.shouldFailOver(0L, false))
        assertTrue(AicpMultiLocationPolicy.shouldFailOver(-1L, true))
        assertTrue(AicpMultiLocationPolicy.shouldFailOver(0L, true))
        assertTrue(AicpMultiLocationPolicy.shouldFailOver(301L, false))
        assertFalse(AicpMultiLocationPolicy.shouldFailOver(300L, true))
    }

    @Test fun countryScanUsesBoundedBatchesWithoutAConfigCountStop() {
        assertEquals(40, AicpMultiLocationPolicy.TEST_BATCH_SIZE)
        val candidates = (1..137).map { "config-$it" }
        val batches = AicpMultiLocationPolicy.candidateBatches(candidates)
        assertEquals(listOf(40, 40, 40, 17), batches.map { it.size })
        assertEquals(candidates, batches.flatten())
    }

    @Test fun smartConnectPreemptsOnlyAnActiveMultiLocationBatchAndCanResumeIt() {
        assertTrue(AicpMultiLocationPolicy.shouldPreemptBatchForSmartConnectFailover(true, false, true))
        assertFalse(AicpMultiLocationPolicy.shouldPreemptBatchForSmartConnectFailover(true, true, true))
        assertFalse(AicpMultiLocationPolicy.shouldPreemptBatchForSmartConnectFailover(false, false, true))
        assertFalse(AicpMultiLocationPolicy.shouldPreemptBatchForSmartConnectFailover(true, false, false))
    }

    @Test fun candidateBatchesKeepEveryCandidateAndBoundEachServiceBatch() {
        val candidates = (1..97).map { "config-$it" }
        val batches = AicpMultiLocationPolicy.candidateBatches(candidates)
        assertEquals(listOf(40, 40, 17), batches.map { it.size })
        assertEquals(candidates, batches.flatten())
    }

    @Test fun candidateBatchesRejectNonPositiveBatchSize() {
        assertThrows(IllegalArgumentException::class.java) {
            AicpMultiLocationPolicy.candidateBatches(listOf("config"), 0)
        }
    }

    @Test fun unsupportedPreferredCountryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AicpMultiLocationPolicy.candidateCountries("IR", listOf("DE", "NL", "FR"))
        }
    }


}
