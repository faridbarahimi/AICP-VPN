package com.v2ray.ang.routing

/** Keeps DNS address families aligned with the effective VPN route families. */
internal object VpnDnsFamilyPolicy {
    fun filterDnsServers(servers: List<String>, ipv6Enabled: Boolean): List<String> =
        servers.filter { ipv6Enabled || !it.contains(':') }
}
