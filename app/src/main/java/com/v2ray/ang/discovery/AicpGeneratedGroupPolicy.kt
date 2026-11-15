package com.v2ray.ang.discovery

/** Only app-owned Finder country banks with no stored profiles may be removed automatically. */
internal object AicpGeneratedGroupPolicy {
    const val PREFIX = "aicp-finder-"
    const val MULTI_LOCATION_GROUP = "aicp-multilocation"

    fun isRemovableGroup(groupId: String): Boolean =
        (groupId.startsWith(PREFIX) && groupId.length > PREFIX.length) || groupId == MULTI_LOCATION_GROUP

    fun shouldPruneEmptyGroup(groupId: String, serverCount: Int): Boolean =
        groupId.startsWith(PREFIX) && groupId.length > PREFIX.length && serverCount == 0
}


/** Country-bank refresh tests only newly added or connection-changed profiles and never overlaps an active test. */
internal object AicpAutoTestPolicy {
    fun shouldTestChangedConfigs(changedCount: Int, isAlreadyTesting: Boolean): Boolean =
        changedCount > 0 && !isAlreadyTesting
}
