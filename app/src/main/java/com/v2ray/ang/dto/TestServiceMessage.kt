package com.v2ray.ang.dto

import java.io.Serializable

data class TestServiceMessage(
    val key: Int,
    val subscriptionId: String = "",
    val serverGuids: List<String> = emptyList(),
    val onlyTcp: Boolean = false,
    /** False when the caller owns pruning/sorting or tests only an explicit subset. */
    val manageSubscriptionResults: Boolean = true
) : Serializable



/** Subscription-wide cleanup is safe only when the worker tested the whole subscription. */
internal fun TestServiceMessage.shouldApplySubscriptionResultMaintenance(): Boolean =
    manageSubscriptionResults && subscriptionId.isNotEmpty()
