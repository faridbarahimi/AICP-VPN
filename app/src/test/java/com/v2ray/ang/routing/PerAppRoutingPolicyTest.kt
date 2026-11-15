package com.v2ray.ang.routing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PerAppRoutingPolicyTest {
    private val installed = setOf("browser", "play", "gemini", "maps")

    @Test
    fun `checked means VPN and unchecked means direct`() {
        assertTrue(PerAppRoutingPolicy.isVpnSelected("browser", true, setOf("browser")))
        assertFalse(PerAppRoutingPolicy.isVpnSelected("play", true, setOf("browser")))
    }

    @Test
    fun `bypass selection is preserved and only installed packages remain`() {
        val normalized = PerAppRoutingPolicy.normalize(installed, setOf("play", "maps", "removed.app"), true, true)
        assertEquals(setOf("play", "maps"), normalized.selectedVpnPackages)
        assertTrue(normalized.perAppEnabled)
        assertTrue(normalized.bypassApps)
    }

    @Test
    fun `bypass mode reverses checkbox routing semantics without changing stored selection`() {
        val bypassed = setOf("play", "maps")
        assertTrue(PerAppRoutingPolicy.isVpnSelected("browser", true, bypassed, bypassApps = true))
        assertFalse(PerAppRoutingPolicy.isVpnSelected("play", true, bypassed, bypassApps = true))
    }

    @Test
    fun `disabled per-app routing keeps saved selection dormant and routes every app through VPN`() {
        val saved = setOf("play")
        val normalized = PerAppRoutingPolicy.normalize(installed, saved, false, true)
        assertEquals(saved, normalized.selectedVpnPackages)
        assertTrue(normalized.bypassApps)
        assertTrue(installed.all { PerAppRoutingPolicy.isVpnSelected(it, normalized.perAppEnabled, normalized.selectedVpnPackages, normalized.bypassApps) })
    }

    @Test
    fun `disabled per-app routing means all apps use VPN`() {
        val normalized = PerAppRoutingPolicy.normalize(installed, setOf("play"), false, false)
        assertEquals(setOf("play"), normalized.selectedVpnPackages)
        assertTrue(installed.all { PerAppRoutingPolicy.isVpnSelected(it, normalized.perAppEnabled, normalized.selectedVpnPackages) })
    }

    @Test
    fun `first unchecked action materializes all apps except the unchecked app`() {
        assertEquals(installed - "play", PerAppRoutingPolicy.selectionAfterToggle("play", installed, emptySet(), false))
    }

    @Test
    fun `empty allow list represents no apps selected for VPN`() {
        assertFalse(PerAppRoutingPolicy.isVpnSelected("browser", true, emptySet()))
    }
    @Test
    fun `normal mode denies only the VPN app so all other apps use the tunnel`() {
        assertEquals(
            PerAppRoutingPolicy.BuilderPlan.Disallow(setOf("aicp.vpn")),
            PerAppRoutingPolicy.builderPlan(installed, "aicp.vpn", false, false, setOf("browser")),
        )
    }

    @Test
    fun `unified allow list sends only checked installed apps into VPN`() {
        assertEquals(
            PerAppRoutingPolicy.BuilderPlan.AllowOnly(setOf("browser", "gemini")),
            PerAppRoutingPolicy.builderPlan(installed, "aicp.vpn", true, false, setOf("browser", "gemini", "removed.app")),
        )
    }

    @Test
    fun `WebRTC protection overrides allow-list and routes every app except the VPN itself`() {
        assertEquals(
            PerAppRoutingPolicy.BuilderPlan.Disallow(setOf("aicp.vpn")),
            PerAppRoutingPolicy.builderPlan(
                installed, "aicp.vpn", true, false, setOf("browser"), forceAllApps = true,
            ),
        )
    }

    @Test
    fun `empty unified selection explicitly denies every installed app`() {
        assertEquals(
            PerAppRoutingPolicy.BuilderPlan.Disallow(installed + "aicp.vpn"),
            PerAppRoutingPolicy.builderPlan(installed, "aicp.vpn", true, false, emptySet()),
        )
    }

    @Test
    fun `empty bypass selection routes every app through VPN`() {
        assertEquals(
            PerAppRoutingPolicy.BuilderPlan.Disallow(setOf("aicp.vpn")),
            PerAppRoutingPolicy.builderPlan(installed, "aicp.vpn", true, true, emptySet()),
        )
    }

    @Test
    fun `WebRTC full tunnel overrides per-app bypass selection`() {
        assertEquals(
            PerAppRoutingPolicy.BuilderPlan.Disallow(setOf("aicp.vpn")),
            PerAppRoutingPolicy.builderPlan(
                installed, "aicp.vpn", true, true, setOf("play"), forceAllApps = true,
            ),
        )
    }

    @Test
    fun `bypass mode plan excludes selected direct apps and VPN app`() {
        assertEquals(
            PerAppRoutingPolicy.BuilderPlan.Disallow(setOf("play", "aicp.vpn")),
            PerAppRoutingPolicy.builderPlan(installed, "aicp.vpn", true, true, setOf("play", "removed.app")),
        )
    }

}
