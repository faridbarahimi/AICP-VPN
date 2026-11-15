package com.v2ray.ang.discovery

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AicpGeneratedGroupPolicyTest {
    @Test fun removesOnlyEmptyNamedAicpFinderCountryBanks() {
        assertTrue(AicpGeneratedGroupPolicy.shouldPruneEmptyGroup("aicp-finder-de", 0))
        assertTrue(AicpGeneratedGroupPolicy.shouldPruneEmptyGroup("aicp-finder-jp", 0))
        assertFalse(AicpGeneratedGroupPolicy.shouldPruneEmptyGroup("aicp-finder-de", 1))
        assertFalse(AicpGeneratedGroupPolicy.shouldPruneEmptyGroup("aicp-finder-", 0))
        assertFalse(AicpGeneratedGroupPolicy.shouldPruneEmptyGroup("my-user-subscription", 0))
        assertFalse(AicpGeneratedGroupPolicy.shouldPruneEmptyGroup("", 0))
    }
    @Test fun removalIsLimitedToAicpCountryBanksAndMultiLocation() {
        assertTrue(AicpGeneratedGroupPolicy.isRemovableGroup("aicp-finder-de"))
        assertTrue(AicpGeneratedGroupPolicy.isRemovableGroup("aicp-multilocation"))
        assertFalse(AicpGeneratedGroupPolicy.isRemovableGroup("aicp-finder-"))
        assertFalse(AicpGeneratedGroupPolicy.isRemovableGroup("user-subscription"))
        assertFalse(AicpGeneratedGroupPolicy.isRemovableGroup(""))
    }

    @Test fun autoSpeedTestRunsOnlyForNewOrChangedProfilesWhenNoOtherTestIsActive() {
        assertTrue(AicpAutoTestPolicy.shouldTestChangedConfigs(1, false))
        assertTrue(AicpAutoTestPolicy.shouldTestChangedConfigs(300, false))
        assertFalse(AicpAutoTestPolicy.shouldTestChangedConfigs(0, false))
        assertFalse(AicpAutoTestPolicy.shouldTestChangedConfigs(-1, false))
        assertFalse(AicpAutoTestPolicy.shouldTestChangedConfigs(3, true))
    }

}
