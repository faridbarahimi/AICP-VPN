package com.v2ray.ang.routing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VpnDnsFamilyPolicyTest {
    @Test
    fun ipv6DnsIsExcludedWhenEffectiveIpv6IsDisabled() {
        val configured = listOf("8.8.8.8", "1.1.1.1", "2001:4860:4860::8888")
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), VpnDnsFamilyPolicy.filterDnsServers(configured, false))
    }

    @Test
    fun dualStackDnsIsPreservedWhenEffectiveIpv6IsEnabled() {
        val configured = listOf("8.8.8.8", "2001:4860:4860::8888")
        assertEquals(configured, VpnDnsFamilyPolicy.filterDnsServers(configured, true))
    }
}
