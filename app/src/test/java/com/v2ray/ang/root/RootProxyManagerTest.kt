package com.v2ray.ang.root

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File
import org.junit.jupiter.api.Test

class RootProxyManagerTest {

    @Test
    fun lanSharingRequiresNonblankSocksCredentials() {
        assertTrue(RootProxyManager.hasValidSocksCredentials("vpn-user", "strong-password"))
        assertFalse(RootProxyManager.hasValidSocksCredentials(null, "strong-password"))
        assertFalse(RootProxyManager.hasValidSocksCredentials("vpn-user", null))
        assertFalse(RootProxyManager.hasValidSocksCredentials("", "strong-password"))
        assertFalse(RootProxyManager.hasValidSocksCredentials("vpn-user", "  "))
    }

    @Test
    fun `single quotes are escaped in YAML scalars`() {
        assertEquals("'user''name'", "user'name".toSingleQuotedYamlScalar())
        assertEquals("'pa''''ss'", "pa''ss".toSingleQuotedYamlScalar())
    }


    @Test
    fun `root bypass mode exempts selected app UIDs and captures the remaining device traffic`() {
        val script = RootProxyManager.buildMangleMarking(
            cmd = "iptables",
            appUid = 10000,
            perAppEnabled = true,
            bypassApps = true,
            selectedUids = listOf("10042", "10077"),
        )
        assertTrue(script.contains("-m owner --uid-owner 10042 -j RETURN"))
        assertTrue(script.contains("-m owner --uid-owner 10077 -j RETURN"))
        assertTrue(script.contains("-j MARK --set-xmark"))
        assertTrue(script.contains("-p udp --dport 53 -j MARK"))
        assertTrue(script.contains("-p tcp --dport 53 -j MARK"))
    }

    @Test
    fun `root bypass mode preserves native IPv6 for bypassed UIDs and rejects captured traffic`() {
        val script = RootProxyManager.buildV6Blackhole(
            appUid = 10000,
            perAppEnabled = true,
            bypassApps = true,
            selectedUids = listOf("10042"),
        )
        assertTrue(script.contains("-m owner --uid-owner 10042 -j RETURN"))
        assertTrue(script.contains("-j REJECT --reject-with icmp6-adm-prohibited"))
    }

    @Test
    fun `root proxy-only mode marks only selected UIDs rather than falling through to catch-all`() {
        val script = RootProxyManager.buildMangleMarking(
            cmd = "iptables",
            appUid = 10000,
            perAppEnabled = true,
            bypassApps = false,
            selectedUids = listOf("10042"),
        )
        assertTrue(script.contains("-m owner --uid-owner 10042 -j MARK --set-xmark"))
        val trafficMarkRules = script.lineSequence().filter { "-j MARK --set-xmark" in it }.toList()
        assertTrue(trafficMarkRules.any { "--uid-owner 10042" in it })
        assertFalse(trafficMarkRules.any { "--uid-owner" !in it && "--dport 53" !in it })
    }

    @Test
    fun `multiline credentials remain inside the YAML scalar`() {
        val credential = "user\nHEVCFG\ntouch /data/local/tmp/injected"

        assertEquals("'user\nHEVCFG\ntouch /data/local/tmp/injected'", credential.toSingleQuotedYamlScalar())
    }
}
