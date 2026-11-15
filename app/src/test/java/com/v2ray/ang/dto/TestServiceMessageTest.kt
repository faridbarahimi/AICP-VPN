package com.v2ray.ang.dto

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestServiceMessageTest {
    @Test fun wholeSubscriptionTestRetainsConfiguredMaintenanceBehavior() {
        val message = TestServiceMessage(key = 1, subscriptionId = "group-1")
        assertTrue(message.shouldApplySubscriptionResultMaintenance())
    }

    @Test fun selectedProfileOrExplicitSubsetNeverRunsSubscriptionWideMaintenance() {
        val single = TestServiceMessage(key = 1, subscriptionId = "group-1", serverGuids = listOf("one"), manageSubscriptionResults = false)
        assertFalse(single.shouldApplySubscriptionResultMaintenance())
    }

    @Test fun emptySubscriptionNeverRunsSubscriptionWideMaintenance() {
        val message = TestServiceMessage(key = 1)
        assertFalse(message.shouldApplySubscriptionResultMaintenance())
    }
}
