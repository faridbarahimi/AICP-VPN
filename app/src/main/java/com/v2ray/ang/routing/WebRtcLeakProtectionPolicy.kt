package com.v2ray.ang.routing

/**
 * Network-layer WebRTC leak protection for Android VpnService mode.
 *
 * WebRTC/STUN uses ordinary UDP and can select arbitrary ports, so port-based STUN blocking is
 * not a reliable control. The safe network-layer policy is to capture every app and both IP
 * families, without LAN/split-tunnel exclusions. Browser host-candidate obfuscation remains a
 * browser responsibility and is deliberately not claimed here.
 */
internal object WebRtcLeakProtectionPolicy {
    fun bypassLan(configuredBypassLan: Boolean, enabled: Boolean): Boolean =
        configuredBypassLan && !enabled

    fun ipv6Enabled(configuredIpv6Enabled: Boolean, enabled: Boolean): Boolean =
        configuredIpv6Enabled || enabled

    /** Full IPv6 capture is mandatory whenever leak protection is enabled. */
    fun routeAllIpv6(configuredBypassLan: Boolean, enabled: Boolean): Boolean =
        !bypassLan(configuredBypassLan, enabled)
}
