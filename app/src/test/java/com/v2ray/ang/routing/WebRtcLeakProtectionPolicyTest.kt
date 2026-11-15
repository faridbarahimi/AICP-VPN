package com.v2ray.ang.routing

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebRtcLeakProtectionPolicyTest {
    @Test
    fun `protection overrides LAN bypass without mutating stored preference`() {
        assertFalse(WebRtcLeakProtectionPolicy.bypassLan(configuredBypassLan = true, enabled = true))
        assertTrue(WebRtcLeakProtectionPolicy.bypassLan(configuredBypassLan = true, enabled = false))
        assertFalse(WebRtcLeakProtectionPolicy.bypassLan(configuredBypassLan = false, enabled = true))
    }

    @Test
    fun `protection installs a full IPv6 default route`() {
        assertTrue(WebRtcLeakProtectionPolicy.routeAllIpv6(configuredBypassLan = true, enabled = true))
        assertFalse(WebRtcLeakProtectionPolicy.routeAllIpv6(configuredBypassLan = true, enabled = false))
        assertTrue(WebRtcLeakProtectionPolicy.routeAllIpv6(configuredBypassLan = false, enabled = false))
    }

    @Test
    fun `protection forces IPv6 capture even when the user IPv6 toggle is off`() {
        assertTrue(WebRtcLeakProtectionPolicy.ipv6Enabled(configuredIpv6Enabled = false, enabled = true))
        assertTrue(WebRtcLeakProtectionPolicy.ipv6Enabled(configuredIpv6Enabled = true, enabled = false))
        assertFalse(WebRtcLeakProtectionPolicy.ipv6Enabled(configuredIpv6Enabled = false, enabled = false))
    }
}
