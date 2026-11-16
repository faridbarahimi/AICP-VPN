package com.v2ray.ang.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.v2ray.ang.handler.SettingsManager
import java.net.Inet6Address
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.TimeZone

/** Independent network observations for AICP's public exit identity check. */
internal object AicpEgressVerifier {
    private const val PREFS = "aicp_network_identity"
    private const val PREF_BASELINE_IP = "baseline_ip"
    private const val PREF_BASELINE_IPV4 = "baseline_ipv4_ip"
    private const val PREF_BASELINE_IPV6 = "baseline_ipv6_ip"
    private const val PREF_BASELINE_COUNTRY = "baseline_country"
    private const val PREF_BASELINE_CITY = "baseline_city"
    private const val PREF_BASELINE_TIME = "baseline_time"
    private const val MAX_RESPONSE_BYTES = 64 * 1024
    private const val BASELINE_MAX_AGE_MS = 2 * 60 * 60 * 1000L
    private const val FAMILY_PROBE_TIMEOUT_MS = 3_000
    private const val TUNNEL_PROBE_TIMEOUT_MS = 5_000
    private const val MIN_TUNNEL_PAYLOAD_BYTES = 16 * 1024

    data class ExitLocation(
        val provider: String,
        val ip: String,
        val countryCode: String,
        val country: String,
        val region: String,
        val city: String,
        val organization: String,
        val asn: String,
        val timezone: String,
        val latitude: Double? = null,
        val longitude: Double? = null,
    )

    data class Observation(val provider: String, val ip: String, val countryCode: String)

    data class IpFamilyProbe(
        val family: String,
        val ip: String?,
        val countryCode: String?,
        val lookupComplete: Boolean,
        val region: String = "",
        val city: String = "",
        val error: String? = null,
    )

    data class Snapshot(
        val location: ExitLocation,
        val observations: List<Observation>,
        val ipv4: IpFamilyProbe,
        val ipv6: IpFamilyProbe,
        val vpnDnsServers: List<String>,
        val underlyingDnsServers: List<String>,
        /** True when the identity requests were routed through AICP's local Xray HTTP proxy. */
        val proxyRouted: Boolean,
        val underlyingGlobalIpv6: Boolean,
        val baselineIp: String?,
        val baselineIpv4Ip: String?,
        val baselineIpv6Ip: String?,
        val baselineCountry: String?,
        val baselineCity: String?,
        val baselineAtMillis: Long?,
        val vpnTransportActive: Boolean,
        /** True only when a non-empty HTTPS response was read via Android's VPN Network, not the local proxy. */
        val vpnPayloadVerified: Boolean = false,
        val observedAtMillis: Long,
        val deviceLocale: String,
        val deviceTimezone: String,
    ) {
        // IPv4 and IPv6 legitimately use different addresses; compare only observations within each family.
        val ipsAgree: Boolean get() = observations.size >= 2 && observations
            .groupBy { it.ip.contains(':') }.values.all { familyRows -> familyRows.map { it.ip }.distinct().size == 1 }
        val countriesAgree: Boolean get() = observations.size >= 2 && observations.map { it.countryCode }.filter { it.isNotBlank() }.distinct().size == 1
        val ipv4MatchesExit: Boolean get() = ipv4.ip != null && ipv4.lookupComplete &&
            ipv4.countryCode == location.countryCode && ipv4.countryCode != "IR"
        // A failed IPv6 probe is acceptable only when the underlying network has no global IPv6 address.
        val ipv6MatchesExit: Boolean get() = if (ipv6.ip != null) {
            ipv6.lookupComplete && ipv6.countryCode == location.countryCode && ipv6.countryCode != "IR"
        } else !underlyingGlobalIpv6
        val ipv6Observed: Boolean get() = ipv6.ip != null
        val baselineFresh: Boolean get() = baselineAtMillis?.let { captured ->
            val age = observedAtMillis - captured
            age in 0..BASELINE_MAX_AGE_MS
        } == true
        val baselineChanged: Boolean get() = baselineIp != null && baselineFresh && location.ip != baselineIp
        val ipv4ChangedFromBaseline: Boolean get() = baselineFresh && baselineIpv4Ip != null &&
            ipv4.ip != null && ipv4.ip != baselineIpv4Ip
        val nonIranian: Boolean get() = location.countryCode != "IR" && observations.none { it.countryCode == "IR" }
    }

    data class Target(val countryCode: String, val region: String = "", val city: String = "")

    data class PolicyResult(
        val countryMatches: Boolean,
        val regionMatches: Boolean,
        val cityMatches: Boolean,
        val confirmed: Boolean,
        val ipv4MatchesTarget: Boolean = true,
        val ipv6MatchesTarget: Boolean = true,
    )

    /** Store only the public IP and coarse location locally after an explicit user action. */
    fun captureBaseline(context: Context): ExitLocation {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // The current app package may bypass its own VPN, so activeNetwork can be the physical network even
        // while an Android VPN is up. Refuse baseline capture if any VPN transport exists in allNetworks.
        val vpnAlreadyActive = manager.allNetworks.any { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        require(!vpnAlreadyActive) { "Disconnect VPNs before capturing a network baseline" }
        val lookup = lookup(useAppProxy = false)
        val chosen = lookup.firstOrNull { it.provider == "ipapi.co" } ?: lookup.firstOrNull()
            ?: error("No identity provider responded")
        val baselineIpv4 = probeAddressFamily("api4.ipify.org", "IPv4", preferFamily = 4, useAppProxy = false)
        val baselineIpv6 = probeAddressFamily("api6.ipify.org", "IPv6", preferFamily = 6, useAppProxy = false)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREF_BASELINE_IP, chosen.ip)
            .putString(PREF_BASELINE_IPV4, baselineIpv4.ip)
            .putString(PREF_BASELINE_IPV6, baselineIpv6.ip)
            .putString(PREF_BASELINE_COUNTRY, chosen.countryCode)
            .putString(PREF_BASELINE_CITY, chosen.city)
            .putLong(PREF_BASELINE_TIME, System.currentTimeMillis())
            .apply()
        return chosen
    }

    fun verify(context: Context): Snapshot {
        // Android intentionally excludes the app's own package from its TUN to avoid a proxy loop.
        // Route identity probes through the same loopback HTTP inbound used by the native speed test.
        val proxyPort = SettingsManager.getHttpPort()
        require(proxyPort in 1..65535) { "AICP local HTTP proxy is unavailable" }
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Don't use activeNetwork here: this app is intentionally excluded from its own TUN and can
        // therefore see the physical network as its active network. Enumerate and explicitly bind a
        // separate HTTPS payload probe to Android's VPN Network; local-proxy IP checks alone do not
        // prove that normal device traffic can traverse the tunnel.
        val allNetworks = manager.allNetworks.toList()
        val vpnNetwork = allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        val vpnActive = vpnNetwork != null
        val vpnPayloadVerified = vpnNetwork?.let(::verifyVpnNetworkPayload) ?: false
        // Probe the tunnel before local-proxy identity lookups, so proxy lookup failures do not
        // prevent observing whether a real HTTPS payload traverses Android's VPN Network.
        val rows = lookup(useAppProxy = true)
        val location = rows.firstOrNull { it.provider == "ipapi.co" } ?: rows.firstOrNull()
            ?: error("No identity provider responded")
        val observations = rows.map { Observation(it.provider, it.ip, it.countryCode) }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val vpnDns = vpnNetwork?.let(manager::getLinkProperties)?.dnsServers.orEmpty()
            .map { it.hostAddress.orEmpty() }.filter { it.isNotBlank() }.distinct()
        val underlyingProperties = allNetworks.asSequence()
            .filter { network -> manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true }
            .mapNotNull(manager::getLinkProperties).toList()
        val underlyingDns = underlyingProperties.asSequence()
            .flatMap { it.dnsServers.asSequence() }
            .map { it.hostAddress.orEmpty() }.filter { it.isNotBlank() }.distinct().toList()
        val underlyingGlobalIpv6 = underlyingProperties.asSequence().flatMap { it.linkAddresses.asSequence() }
            .map { it.address }.filterIsInstance<Inet6Address>()
            .any { address ->
                val first = address.address.first().toInt() and 0xff
                (first and 0xe0) == 0x20 && !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isMulticastAddress
            }
        val ipv4 = probeAddressFamily("api4.ipify.org", "IPv4", preferFamily = 4, useAppProxy = true)
        val ipv6 = probeAddressFamily("api6.ipify.org", "IPv6", preferFamily = 6, useAppProxy = true)
        return Snapshot(
            location = location,
            observations = observations,
            ipv4 = ipv4,
            ipv6 = ipv6,
            vpnDnsServers = vpnDns,
            underlyingDnsServers = underlyingDns,
            proxyRouted = rows.size >= 2 && proxyPort in 1..65535,
            underlyingGlobalIpv6 = underlyingGlobalIpv6,
            baselineIp = prefs.getString(PREF_BASELINE_IP, null),
            baselineIpv4Ip = prefs.getString(PREF_BASELINE_IPV4, null),
            baselineIpv6Ip = prefs.getString(PREF_BASELINE_IPV6, null),
            baselineCountry = prefs.getString(PREF_BASELINE_COUNTRY, null),
            baselineCity = prefs.getString(PREF_BASELINE_CITY, null),
            baselineAtMillis = prefs.getLong(PREF_BASELINE_TIME, 0L).takeIf { it > 0L },
            vpnTransportActive = vpnActive,
            vpnPayloadVerified = vpnPayloadVerified,
            observedAtMillis = System.currentTimeMillis(),
            deviceLocale = Locale.getDefault().toLanguageTag(),
            deviceTimezone = TimeZone.getDefault().id,
        )
    }

    /**
     * Live candidate qualification. Unlike the user-facing baseline policy, this does not require
     * a manually captured physical-network baseline; it still fails closed unless the running VPN,
     * independent IP observations, country and requested region/city all agree.
     */
    fun confirmsTargetLocation(snapshot: Snapshot, target: Target): Boolean {
        val country = target.countryCode.uppercase(Locale.ROOT)
        val locationMatches = snapshot.location.countryCode == country &&
            (target.region.isBlank() || containsNormalized(snapshot.location.region, target.region)) &&
            (target.city.isBlank() || containsNormalized(snapshot.location.city, target.city))
        return snapshot.vpnTransportActive && snapshot.vpnPayloadVerified && snapshot.proxyRouted && snapshot.ipsAgree &&
            snapshot.countriesAgree && snapshot.nonIranian && locationMatches &&
            familyLocationMatchesTarget(snapshot.ipv4, target, allowAbsent = false, snapshot = snapshot) &&
            familyLocationMatchesTarget(snapshot.ipv6, target, allowAbsent = true, snapshot = snapshot)
    }

    private fun familyLocationMatchesTarget(
        probe: IpFamilyProbe,
        target: Target,
        allowAbsent: Boolean,
        snapshot: Snapshot,
    ): Boolean {
        if (probe.ip == null) return allowAbsent && !snapshot.underlyingGlobalIpv6
        if (!probe.lookupComplete || probe.countryCode != target.countryCode.uppercase(Locale.ROOT) || probe.countryCode == "IR") return false
        if (target.region.isNotBlank() && !containsNormalized(probe.region, target.region)) return false
        if (target.city.isNotBlank() && !containsNormalized(probe.city, target.city)) return false
        return true
    }

    fun evaluate(snapshot: Snapshot, target: Target): PolicyResult {
        val country = target.countryCode.uppercase(Locale.ROOT)
        val countryMatch = country.isNotBlank() && snapshot.location.countryCode == country
        val regionMatch = target.region.isBlank() || containsNormalized(snapshot.location.region, target.region)
        val cityMatch = target.city.isBlank() || containsNormalized(snapshot.location.city, target.city)
        val ipv4TargetMatch = familyMatchesTarget(snapshot.ipv4, target, absentIpv6Allowed = false, snapshot = snapshot)
        val ipv6TargetMatch = familyMatchesTarget(snapshot.ipv6, target, absentIpv6Allowed = true, snapshot = snapshot)
        val confirmed = countryMatch && regionMatch && cityMatch && snapshot.nonIranian &&
            snapshot.ipsAgree && snapshot.countriesAgree && snapshot.baselineChanged && snapshot.vpnTransportActive &&
            ipv4TargetMatch && snapshot.ipv4ChangedFromBaseline && ipv6TargetMatch &&
            snapshot.proxyRouted && snapshot.vpnPayloadVerified && snapshot.vpnDnsServers.isNotEmpty()
        return PolicyResult(countryMatch, regionMatch, cityMatch, confirmed,
            ipv4TargetMatch, ipv6TargetMatch)
    }

    private fun familyMatchesTarget(
        probe: IpFamilyProbe,
        target: Target,
        absentIpv6Allowed: Boolean,
        snapshot: Snapshot,
    ): Boolean {
        if (probe.ip == null) {
            if (!absentIpv6Allowed) return false
            // When IPv6 exists underneath, lack of a connected IPv6 observation is inconclusive, not a pass.
            return !snapshot.underlyingGlobalIpv6 && snapshot.baselineIpv6Ip == null
        }
        if (!probe.lookupComplete || probe.countryCode != target.countryCode.uppercase(Locale.ROOT) || probe.countryCode == "IR") return false
        if (target.region.isNotBlank() && !containsNormalized(probe.region, target.region)) return false
        if (target.city.isNotBlank() && !containsNormalized(probe.city, target.city)) return false
        if (probe.family == "IPv4") {
            if (snapshot.baselineIpv4Ip == null || probe.ip == snapshot.baselineIpv4Ip) return false
        }
        if (probe.family == "IPv6") {
            if (snapshot.baselineIpv6Ip == null && snapshot.underlyingGlobalIpv6) return false
            if (snapshot.baselineIpv6Ip != null && probe.ip == snapshot.baselineIpv6Ip) return false
        }
        return true
    }

    private fun containsNormalized(actual: String, requested: String): Boolean {
        if (requested.isBlank()) return true
        val a = normalize(actual)
        val r = normalize(requested)
        if (a.isBlank()) return false
        if (a.contains(r) || r.contains(a)) return true
        val canonicalActual = canonicalRegion(a)
        val canonicalRequested = canonicalRegion(r)
        return canonicalActual != null && canonicalActual == canonicalRequested
    }

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
        .replace(Regex("[\\s._-]+"), " ")
        .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")
        .replace("é", "e").replace("è", "e").replace("á", "a").replace("ó", "o")

    private fun canonicalRegion(normalized: String): String? {
        val aliases = listOf(
            setOf("hessen", "hesse"),
            setOf("bayern", "bavaria"),
            setOf("niedersachsen", "lower saxony"),
            setOf("nordrhein westfalen", "north rhine westphalia"),
            setOf("rheinland pfalz", "rhineland palatinate"),
            setOf("baden wurttemberg", "baden wuerttemberg", "baden wurttemberg state"),
            setOf("sachsen", "saxony"),
            setOf("sachsen anhalt", "saxony anhalt"),
            setOf("thuringen", "thuringia"),
            setOf("mecklenburg vorpommern", "mecklenburg western pomerania"),
            setOf("ile de france", "ile-de-france"),
            setOf("provence alpes cote d azur", "provence alpes cote d azur region"),
            setOf("wien", "vienna"),
            setOf("nord holland", "north holland"),
            setOf("zuid holland", "south holland"),
            setOf("noord brabant", "north brabant")
        )
        return aliases.firstOrNull { normalized in it }?.minOrNull()
    }

    /**
     * Require a real non-empty HTTPS payload bound to Android's VPN Network. The app UID may be
     * excluded from its own TUN, so proxy-routed identity requests are not a substitute for this.
     * Two independent endpoints reduce (but cannot eliminate) false negatives from a single outage.
     */
    /** Publicly callable by Smart Connect: verify packets through Android's VPN Network, not local SOCKS. */
    internal fun verifyVpnNetworkPayload(context: Context, timeoutMillis: Int): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return@runCatching false
        val network = manager.allNetworks.firstOrNull { candidate ->
            manager.getNetworkCapabilities(candidate)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } ?: return@runCatching false
        verifyVpnNetworkPayload(network, timeoutMillis)
    }.getOrDefault(false)

    private fun verifyVpnNetworkPayload(network: Network): Boolean =
        verifyVpnNetworkPayload(network, TUNNEL_PROBE_TIMEOUT_MS)

    private fun verifyVpnNetworkPayload(network: Network, timeoutMillis: Int): Boolean {
        val budgetMillis = timeoutMillis.coerceIn(250, TUNNEL_PROBE_TIMEOUT_MS)
        val deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(budgetMillis.toLong())
        val nonce = System.nanoTime()
        // Each endpoint must return a body; 204, redirects and captive-portal HTML never qualify.
        // Distinct providers reduce false negatives on networks that block one test host.
        val endpoints = listOf(
            "https://speed.cloudflare.com/__down?bytes=32768&aicp_probe=$nonce",
            "https://httpbin.org/bytes/32768?seed=$nonce",
        )
        val perEndpointBudgetNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
            (budgetMillis / endpoints.size).toLong()
        )
        for (rawUrl in endpoints) {
            val endpointDeadline = minOf(deadlineNanos, System.nanoTime() + perEndpointBudgetNanos)
            val remainingMillis = java.util.concurrent.TimeUnit.NANOSECONDS
                .toMillis(endpointDeadline - System.nanoTime()).toInt()
            if (remainingMillis <= 0) break
            val connection = runCatching {
                (network.openConnection(URL(rawUrl)) as HttpURLConnection).apply {
                    connectTimeout = remainingMillis
                    readTimeout = remainingMillis
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "AICP-VPN-TunnelPayload/2.0")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Cache-Control", "no-cache, no-store")
                }
            }.getOrNull() ?: continue
            try {
                connection.connect()
                val readBudget = java.util.concurrent.TimeUnit.NANOSECONDS
                    .toMillis(endpointDeadline - System.nanoTime()).toInt()
                if (readBudget <= 0) continue
                connection.readTimeout = readBudget
                val statusCode = connection.responseCode
                val contentType = connection.contentType.orEmpty()
                if (statusCode != HttpURLConnection.HTTP_OK || contentType.startsWith("text/html", true)) continue
                val buffer = ByteArray(8 * 1024)
                var total = 0
                connection.inputStream.use { input ->
                    while (total < MIN_TUNNEL_PAYLOAD_BYTES) {
                        val count = input.read(buffer, 0, minOf(buffer.size, MIN_TUNNEL_PAYLOAD_BYTES - total))
                        if (count < 0) break
                        total += count
                    }
                }
                if (isSuccessfulVpnPayloadResponse(statusCode, contentType, total)) return true
            } catch (_: Exception) {
                // Try the next independent endpoint within the same absolute deadline.
            } finally {
                connection.disconnect()
            }
        }
        return false
    }

    internal fun isSuccessfulVpnPayloadResponse(statusCode: Int, contentType: String, bytesRead: Int): Boolean =
        statusCode == HttpURLConnection.HTTP_OK && bytesRead >= MIN_TUNNEL_PAYLOAD_BYTES &&
            !contentType.trim().startsWith("text/html", ignoreCase = true)

    private fun probeAddressFamily(host: String, family: String, preferFamily: Int, useAppProxy: Boolean): IpFamilyProbe {
        return try {
            val raw = fetchFamilyProbe(host, preferFamily, useAppProxy)
            val ip = raw.trim().removePrefix("\"").removeSuffix("\"")
                .let { value -> runCatching { JSONObject(value).optString("ip").ifBlank { value } }.getOrDefault(value) }
                .trim()
            require(ip.isNotBlank() && !ip.equals("null", ignoreCase = true)) { "No $family address returned" }
            val locationJson = JSONObject(fetch("https://ipwho.is/${java.net.URLEncoder.encode(ip, "UTF-8")}", "ipwho.is", useAppProxy))
            require(locationJson.optBoolean("success", false)) { "Could not geolocate $family address" }
            val code = locationJson.optString("country_code").uppercase(Locale.ROOT)
            IpFamilyProbe(
                family = family, ip = ip, countryCode = code.takeIf { it.length == 2 },
                lookupComplete = code.length == 2,
                region = locationJson.optString("region"), city = locationJson.optString("city")
            )
        } catch (error: Exception) {
            IpFamilyProbe(family, null, null, lookupComplete = false,
                error = error.message?.take(120) ?: "$family lookup unavailable")
        }
    }

    private fun fetchFamilyProbe(host: String, family: Int, useAppProxy: Boolean): String {
        require((family == 4 && host == "api4.ipify.org") || (family == 6 && host == "api6.ipify.org"))
        val url = URL("https://$host?format=json")
        val connection = openConnection(url, useAppProxy, FAMILY_PROBE_TIMEOUT_MS)
        try {
            require(connection.responseCode in 200..299) { "IP family probe returned HTTP ${connection.responseCode}" }
            BufferedInputStream(connection.inputStream).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= 4096) { "IP family response too large" }
                    output.write(buffer, 0, read)
                }
                return output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun lookup(useAppProxy: Boolean): List<ExitLocation> {
        val result = mutableListOf<ExitLocation>()
        runCatching { readIpApi(useAppProxy) }.getOrNull()?.let(result::add)
        runCatching { readIpWho(useAppProxy) }.getOrNull()?.let(result::add)
        runCatching { readCloudflareTrace(useAppProxy) }.getOrNull()?.let(result::add)
        return result.distinctBy { it.provider }
    }

    private fun readIpApi(useAppProxy: Boolean): ExitLocation {
        val json = JSONObject(fetch("https://ipapi.co/json/", "ipapi.co", useAppProxy))
        val ip = json.optString("ip").trim()
        val countryCode = json.optString("country_code").trim().uppercase(Locale.ROOT)
        require(ip.isNotBlank() && countryCode.length == 2 && !json.has("error")) { "Invalid ipapi response" }
        return ExitLocation(
            provider = "ipapi.co", ip = ip, countryCode = countryCode,
            country = json.optString("country_name"), region = json.optString("region"), city = json.optString("city"),
            organization = json.optString("org"), asn = json.optString("asn"), timezone = json.optString("timezone"),
            latitude = json.optDouble("latitude").takeUnless { it.isNaN() },
            longitude = json.optDouble("longitude").takeUnless { it.isNaN() }
        )
    }

    private fun readIpWho(useAppProxy: Boolean): ExitLocation {
        val json = JSONObject(fetch("https://ipwho.is/", "ipwho.is", useAppProxy))
        require(json.optBoolean("success", false)) { json.optString("message", "ipwho lookup failed") }
        val ip = json.optString("ip").trim()
        val countryCode = json.optString("country_code").trim().uppercase(Locale.ROOT)
        require(ip.isNotBlank() && countryCode.length == 2) { "Invalid ipwho response" }
        val connection = json.optJSONObject("connection") ?: JSONObject()
        val timezone = json.optJSONObject("timezone") ?: JSONObject()
        return ExitLocation(
            provider = "ipwho.is", ip = ip, countryCode = countryCode,
            country = json.optString("country"), region = json.optString("region"), city = json.optString("city"),
            organization = connection.optString("org").ifBlank { connection.optString("isp") },
            asn = connection.optString("asn"), timezone = timezone.optString("id"),
            latitude = json.optDouble("latitude").takeUnless { it.isNaN() },
            longitude = json.optDouble("longitude").takeUnless { it.isNaN() }
        )
    }

    private fun readCloudflareTrace(useAppProxy: Boolean): ExitLocation {
        val values = fetch("https://www.cloudflare.com/cdn-cgi/trace", "www.cloudflare.com", useAppProxy)
            .lineSequence().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1).trim()
            }.toMap()
        val ip = values["ip"].orEmpty()
        val country = values["loc"].orEmpty().uppercase(Locale.ROOT)
        require(ip.isNotBlank() && country.length == 2) { "Invalid Cloudflare trace response" }
        return ExitLocation(
            provider = "Cloudflare trace", ip = ip, countryCode = country, country = "", region = "", city = "",
            organization = "", asn = "", timezone = ""
        )
    }

    private fun openConnection(url: URL, useAppProxy: Boolean, connectTimeoutMs: Int): HttpURLConnection {
        val connection = if (useAppProxy) {
            val port = SettingsManager.getHttpPort()
            require(port in 1..65535) { "AICP local HTTP proxy unavailable" }
            val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", port))
            url.openConnection(proxy) as HttpURLConnection
        } else {
            url.openConnection() as HttpURLConnection
        }
        return connection.apply {
            connectTimeout = connectTimeoutMs
            readTimeout = connectTimeoutMs
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", "AICP-VPN-NetworkIdentity/1.0")
            if (useAppProxy) {
                val username = SettingsManager.getSocksUsername()
                val password = SettingsManager.getSocksPassword()
                if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
                    val token = java.util.Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
                    setRequestProperty("Proxy-Authorization", "Basic $token")
                }
            }
        }
    }

    private fun fetch(rawUrl: String, allowedHost: String, useAppProxy: Boolean): String {
        val url = URL(rawUrl)
        require(url.protocol == "https" && url.host == allowedHost) { "Unapproved identity endpoint" }
        val connection = openConnection(url, useAppProxy, 8_000).apply {
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json, text/plain;q=0.9, */*;q=0.5")
        }
        try {
            require(connection.responseCode in 200..299) { "Identity provider returned HTTP ${connection.responseCode}" }
            require(connection.contentLengthLong <= MAX_RESPONSE_BYTES || connection.contentLengthLong < 0) { "Identity response too large" }
            BufferedInputStream(connection.inputStream).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_RESPONSE_BYTES) { "Identity response too large" }
                    output.write(buffer, 0, read)
                }
                return output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }
}
