package com.v2ray.ang.ui.main

import com.v2ray.ang.core.AicpEgressVerifier

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AicpEgressVerifierTest {
    @Test fun vpnNetworkPayloadRequiresHttp200AndAtLeastOneBodyByte() {
        assertTrue(AicpEgressVerifier.isSuccessfulVpnPayloadResponse(200, true))
        assertFalse(AicpEgressVerifier.isSuccessfulVpnPayloadResponse(200, false))
        assertFalse(AicpEgressVerifier.isSuccessfulVpnPayloadResponse(204, false))
        assertFalse(AicpEgressVerifier.isSuccessfulVpnPayloadResponse(302, true))
        assertFalse(AicpEgressVerifier.isSuccessfulVpnPayloadResponse(503, true))
    }
    private fun snapshot(
        ip: String = "203.0.113.20",
        code: String = "DE",
        observations: List<AicpEgressVerifier.Observation> = listOf(
            AicpEgressVerifier.Observation("ipapi.co", ip, code),
            AicpEgressVerifier.Observation("ipwho.is", ip, code),
            AicpEgressVerifier.Observation("Cloudflare trace", ip, code),
        ),
        baselineIp: String? = "198.51.100.10",
        baselineIpv4Ip: String? = "198.51.100.11",
        baselineIpv6Ip: String? = null,
        baselineAtMillis: Long? = 1_000L,
        observedAtMillis: Long = 2_000L,
        vpnActive: Boolean = true,
        vpnPayloadVerified: Boolean = true,
        ipv6: AicpEgressVerifier.IpFamilyProbe = AicpEgressVerifier.IpFamilyProbe("IPv6", null, null, false),
        ipv4: AicpEgressVerifier.IpFamilyProbe? = null,
        underlyingGlobalIpv6: Boolean = false,
        vpnDnsServers: List<String> = listOf("10.0.0.1"),
        country: String = "Germany",
        region: String = "Berlin",
        city: String = "Berlin",
    ) = AicpEgressVerifier.Snapshot(
        location = AicpEgressVerifier.ExitLocation("ipapi.co", ip, code, country, region, city,
            "Example Network", "AS64500", "Europe/Berlin"),
        observations = observations,
        ipv4 = ipv4 ?: AicpEgressVerifier.IpFamilyProbe("IPv4", ip, code, true, region = region, city = city),
        ipv6 = ipv6,
        vpnDnsServers = vpnDnsServers,
        underlyingDnsServers = listOf("192.0.2.1"),
        proxyRouted = true,
        underlyingGlobalIpv6 = underlyingGlobalIpv6,
        baselineIp = baselineIp,
        baselineIpv4Ip = baselineIpv4Ip,
        baselineIpv6Ip = baselineIpv6Ip,
        baselineCountry = "IR",
        baselineCity = "Tehran",
        baselineAtMillis = baselineAtMillis,
        vpnTransportActive = vpnActive,
        vpnPayloadVerified = vpnPayloadVerified,
        observedAtMillis = observedAtMillis,
        deviceLocale = "en-US",
        deviceTimezone = "Europe/Berlin",
    )

    @Test fun confirmsNonIranianExitWhenIpCountryAndChosenCityAllMatch() {
        val result = AicpEgressVerifier.evaluate(snapshot(), AicpEgressVerifier.Target("DE", "ber", "Berlin"))
        assertTrue(result.countryMatches)
        assertTrue(result.regionMatches)
        assertTrue(result.cityMatches)
        assertTrue(result.confirmed)
    }

    @Test fun rejectsIranianExitEvenWhenTargetAndSourcesOtherwiseMatch() {
        val iran = snapshot(code = "IR", country = "Iran", region = "Tehran", city = "Tehran",
            observations = listOf(
                AicpEgressVerifier.Observation("ipapi.co", "203.0.113.20", "IR"),
                AicpEgressVerifier.Observation("ipwho.is", "203.0.113.20", "IR"),
                AicpEgressVerifier.Observation("Cloudflare trace", "203.0.113.20", "IR"),
            ))
        val result = AicpEgressVerifier.evaluate(iran, AicpEgressVerifier.Target("IR"))
        assertFalse(iran.nonIranian)
        assertFalse(result.confirmed)
    }

    @Test fun rejectsDifferentObservedIps() {
        val mismatch = snapshot(observations = listOf(
            AicpEgressVerifier.Observation("ipapi.co", "203.0.113.20", "DE"),
            AicpEgressVerifier.Observation("ipwho.is", "203.0.113.21", "DE"),
            AicpEgressVerifier.Observation("Cloudflare trace", "203.0.113.20", "DE"),
        ))
        assertFalse(mismatch.ipsAgree)
        assertFalse(AicpEgressVerifier.evaluate(mismatch, AicpEgressVerifier.Target("DE")).confirmed)
    }

    @Test fun regionAndCityMatchingIsCaseInsensitiveAndFailsForWrongPlace() {
        assertTrue(AicpEgressVerifier.evaluate(snapshot(), AicpEgressVerifier.Target("DE", "BERLIN", "ber" )).regionMatches)
        assertFalse(AicpEgressVerifier.evaluate(snapshot(), AicpEgressVerifier.Target("DE", "Bavaria", "Munich")).confirmed)
    }

    @Test fun requiresBaselineChangeAndAndroidVpnTransportAndVpnDnsForFullConfirmation() {
        assertFalse(AicpEgressVerifier.evaluate(snapshot(baselineIp = "203.0.113.20"), AicpEgressVerifier.Target("DE")).confirmed)
        assertFalse(AicpEgressVerifier.evaluate(snapshot(baselineIpv4Ip = "203.0.113.20"), AicpEgressVerifier.Target("DE")).confirmed)
        assertFalse(AicpEgressVerifier.evaluate(snapshot(baselineAtMillis = 1L, observedAtMillis = 3 * 60 * 60 * 1000L), AicpEgressVerifier.Target("DE")).confirmed)
        assertFalse(AicpEgressVerifier.evaluate(snapshot(vpnActive = false), AicpEgressVerifier.Target("DE")).confirmed)
        assertFalse(AicpEgressVerifier.evaluate(snapshot(vpnPayloadVerified = false), AicpEgressVerifier.Target("DE")).confirmed)
        assertFalse(AicpEgressVerifier.evaluate(snapshot(vpnDnsServers = emptyList()), AicpEgressVerifier.Target("DE")).confirmed)
        assertFalse(AicpEgressVerifier.evaluate(snapshot().copy(proxyRouted = false), AicpEgressVerifier.Target("DE")).confirmed)
    }

    @Test fun rejectsIpv6ExitCountryMismatchWhenUnderlyingNetworkHasIpv6() {
        val leaking = snapshot(
            ipv6 = AicpEgressVerifier.IpFamilyProbe("IPv6", "2001:db8::1", "IR", true),
            underlyingGlobalIpv6 = true,
        )
        assertFalse(leaking.ipv6MatchesExit)
        val policy = AicpEgressVerifier.evaluate(leaking, AicpEgressVerifier.Target("DE"))
        assertFalse(policy.ipv6MatchesTarget)
        assertFalse(policy.confirmed)
    }


    @Test fun liveTargetConfirmationDoesNotNeedManualBaselineButRequiresActualVpnAndMatchingEgress() {
        val live = snapshot(baselineIp = null, baselineIpv4Ip = null, baselineAtMillis = null)
        val target = AicpEgressVerifier.Target("DE", "Berlin", "Berlin")
        assertTrue(AicpEgressVerifier.confirmsTargetLocation(live, target))
        assertFalse(AicpEgressVerifier.confirmsTargetLocation(live, AicpEgressVerifier.Target("FR", "Paris", "Paris")))
        assertFalse(AicpEgressVerifier.confirmsTargetLocation(live.copy(vpnTransportActive = false), target))
        assertFalse(AicpEgressVerifier.confirmsTargetLocation(live.copy(vpnPayloadVerified = false), target))
        assertFalse(AicpEgressVerifier.confirmsTargetLocation(live.copy(proxyRouted = false), target))
        assertFalse(AicpEgressVerifier.confirmsTargetLocation(live.copy(underlyingGlobalIpv6 = true), target))
    }

    @Test fun neverTreatsMissingGeoIpRegionOrCityAsAMatchForAChosenPlace() {
        val missingFields = snapshot(region = "", city = "")
        val result = AicpEgressVerifier.evaluate(missingFields, AicpEgressVerifier.Target("DE", "Berlin", "Berlin"))
        assertFalse(result.regionMatches)
        assertFalse(result.cityMatches)
        assertFalse(result.confirmed)
    }

    @Test fun acceptsCommonEnglishAndLocalRegionNamesForTheSameGeoIpRegion() {
        assertTrue(AicpEgressVerifier.evaluate(snapshot(region = "Hessen"),
            AicpEgressVerifier.Target("DE", "Hesse", "Berlin")).regionMatches)
        assertTrue(AicpEgressVerifier.evaluate(snapshot(region = "Bayern", city = "Munich"),
            AicpEgressVerifier.Target("DE", "Bavaria", "Munich")).regionMatches)
    }

    @Test fun requiresIpv4RegionAndCityToMatchWhenUserSelectedThem() {
        val wrongCity = snapshot(ipv4 = AicpEgressVerifier.IpFamilyProbe(
            "IPv4", "203.0.113.21", "DE", true, region = "Bavaria", city = "Munich"
        ))
        val result = AicpEgressVerifier.evaluate(wrongCity, AicpEgressVerifier.Target("DE", "Berlin", "Berlin"))
        assertFalse(result.ipv4MatchesTarget)
        assertFalse(result.confirmed)
    }

    @Test fun refusesToClaimIpv6SafetyWhenUnderlyingIpv6ExistsButProbeCannotEstablishExit() {
        val inconclusive = snapshot(underlyingGlobalIpv6 = true)
        assertFalse(inconclusive.ipv6MatchesExit)
        assertFalse(AicpEgressVerifier.evaluate(inconclusive, AicpEgressVerifier.Target("DE")).ipv6MatchesTarget)
        assertFalse(AicpEgressVerifier.evaluate(inconclusive, AicpEgressVerifier.Target("DE")).confirmed)
    }
}
