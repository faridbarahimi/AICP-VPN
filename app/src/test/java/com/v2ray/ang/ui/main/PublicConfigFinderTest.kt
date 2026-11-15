package com.v2ray.ang.ui.main


import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

class PublicConfigFinderTest {
    @Test fun countrySourceUrlsUseFixedHttpsHostsAndSelectedCountry() {
        val urls = PublicConfigFinder.sourceUrls("DE")
        assertEquals(7, urls.size)
        assertTrue(urls.all { it.startsWith("https://raw.githubusercontent.com/") })
        assertTrue(urls.any { it.endsWith("/output/by-country/v2ray-base64-DE.txt") })
        assertTrue(urls.any { it.endsWith("/Countries/Germany.txt") })
        assertTrue(urls.any { it.endsWith("/Splitted-By-Country/DE.txt") })
        assertTrue(urls.any { it.endsWith("/By-Country/DE.txt") })
        assertTrue(urls.any { it.endsWith("/configs/country_DE.txt") })
        assertTrue(urls.any { it.endsWith("/split/sub-DE.txt") })
        assertTrue(urls.any { it.endsWith("/SS-Collector/main/test_report.json") })
        assertEquals("DE", PublicConfigCatalog.sourceEndpoints("DE").single { it.id == "lagzian-ss-collector" }.countryCode)
        assertEquals(7, PublicConfigCatalog.sourceEndpoints("DE").map { it.id }.distinct().size)
        assertEquals(6, PublicConfigFinder.sourceUrls("AM").size)
        assertTrue(PublicConfigFinder.sourceUrls("AM").any { it.endsWith("/By-Country/AM.txt") })
        assertEquals(5, PublicConfigFinder.sourceUrls("AL").size)
        assertEquals(9, PublicConfigFinder.sourceUrls("IR").size)
        assertTrue(PublicConfigFinder.sourceUrls("IR").any { it.endsWith("/Countries/Iran.txt") })
        assertTrue(PublicConfigFinder.sourceUrls("IR").none { it.startsWith("https://t.me/") })
        assertEquals("0xradikal", PublicConfigCatalog.sourceEndpoints("IR").first().id)
        assertTrue(PublicConfigFinder.sourceUrls("IR").any { it.endsWith("/MCI") })
        assertTrue(PublicConfigFinder.sourceUrls("IR").any { it.endsWith("/Mobinet") })
        assertTrue(PublicConfigFinder.sourceUrls("IR").any { it.endsWith("/Irancell") })
        assertTrue(PublicConfigFinder.sourceUrls("IR").any { it.endsWith("/mixed_iran.txt") })
        assertTrue(PublicConfigFinder.sourceUrls("IR").any { it.endsWith("/sub/countries/IR.txt") })
        assertTrue(PublicConfigFinder.sourceUrls("IR").any { it.endsWith("/SS44-gold.txt") })
        assertTrue(PublicConfigFinder.sourceUrls("IR").any { it.endsWith("/By-Country/IR.txt") })
        assertEquals("IR", PublicConfigCatalog.sourceEndpoints("IR").single { it.id == "lagzian-ss-collector" }.countryCode)
        assertTrue(PublicConfigCatalog.countries.size >= 45)
        assertTrue(PublicConfigCatalog.countries.any { it.code == "AE" })
        assertTrue(PublicConfigCatalog.countries.any { it.code == "IR" })
        assertFalse(PublicConfigCatalog.countries.any { it.code == PublicConfigCatalog.GLOBAL_FEED_CODE })
        assertEquals("telegram-aggregator-global", PublicConfigCatalog.sourceEndpoints(PublicConfigCatalog.GLOBAL_FEED_CODE).single().id)
        assertEquals("https://cdn.jsdelivr.net/gh/faridbarahimi/AICP-VPN@config-feed/feed/telegram-all.txt", PublicConfigCatalog.sourceUrls(PublicConfigCatalog.GLOBAL_FEED_CODE).single())
        assertEquals("GL", AicpSelectedRefreshTargetPolicy.resolveCountry("aicp-finder-gl", "DE"))
        assertEquals("GL", AicpSelectedRefreshTargetPolicy.resolveCountry("aicp-multilocation", "DE"))
    }


    @Test fun globalTelegramSnapshotIsImportedAsOneMixedSourceWithoutCountryMislabeling() {
        val source = PublicConfigCatalog.sourceEndpoints(PublicConfigCatalog.GLOBAL_FEED_CODE).single()
        val result = PublicConfigFinder.fetchFromSources(listOf(source)) { url ->
            assertEquals("https://cdn.jsdelivr.net/gh/faridbarahimi/AICP-VPN@config-feed/feed/telegram-all.txt", url)
            "# generated_at=2026-11-15T16:00:00Z\nvless://id@de.example:443#DE\ntrojan://pw@nl.example:443#NL\n"
        }
        assertEquals(1, result.successfulSources)
        assertEquals(0, result.failedSources)
        assertEquals(2, result.configs.size)
        assertTrue(result.configs.any { "de.example" in it })
        assertTrue(result.configs.any { "nl.example" in it })
    }

    @Test fun independentSourcesKeepSuccessfulResultsWhenOneProviderFails() {
        val sources = PublicConfigCatalog.sourceEndpoints("DE")
        val progress = mutableListOf<Triple<Int, Int, Int>>()
        val result = PublicConfigFinder.fetchFromSources(sources, onProgress = { completed, total, found ->
            progress += Triple(completed, total, found)
        }) { url ->
            when {
                url.contains("0xRadikal", ignoreCase = true) -> throw java.io.IOException("simulated provider outage")
                url.contains("Au1rxx", ignoreCase = true) -> "vless://a@de-a.example:443#A"
                url.contains("Danialsamadi", ignoreCase = true) -> "trojan://b@de-b.example:443#B"
                url.contains("MrDevMohamed", ignoreCase = true) -> "ss://c@de-c.example:443#C"
                url.contains("Idolvpn", ignoreCase = true) -> "vmess://e@de-e.example:443#E"
                url.contains("Mokafela", ignoreCase = true) -> "vless://d@de-d.example:443#D"
                url.contains("SS-Collector", ignoreCase = true) -> """{"per_country_proto":{"de":{"vless":["vless://f@de-f.example:443#F"]}}}"""
                else -> error("unexpected source URL")
            }
        }
        assertEquals(6, result.successfulSources)
        assertEquals(1, result.failedSources)
        assertEquals(7, progress.size)
        assertEquals(7, progress.last().first)
        assertEquals(7, progress.last().second)
        assertEquals(6, progress.last().third)
        assertEquals(6, result.configs.size)
        assertEquals(7, result.sourceOutcomes.size)
        assertEquals(1, result.sourceOutcomes.first { it.sourceId == "mrdevmohamed-v2ray-configs" }.candidateCount)
        assertEquals(setOf("au1rxx", "0xradikal", "danialsamadi-v2go", "mrdevmohamed-v2ray-configs", "idolvpn-collector", "mokafela-config-finder", "lagzian-ss-collector"), result.sourceOutcomes.map { it.sourceId }.toSet())
        assertEquals(1, result.sourceOutcomes.first { it.sourceId == "lagzian-ss-collector" }.candidateCount)
        assertEquals(false, result.sourceOutcomes.first { it.sourceId == "0xradikal" }.succeeded)
        assertNotNull(result.sourceOutcomes.first { it.sourceId == "0xradikal" }.failureCategory)
    }

    @Test fun emptyProviderBodyDoesNotEraseOtherProvidersOrCreateConfigs() {
        val sources = PublicConfigCatalog.sourceEndpoints("DE")
        val result = PublicConfigFinder.fetchFromSources(sources) { url ->
            if (url.contains("Au1rxx", ignoreCase = true)) "" else "vless://id@working.example:443#working"
        }
        assertEquals(7, result.successfulSources)
        assertEquals(0, result.failedSources)
        assertEquals(1, result.configs.size)
        assertEquals(0, result.sourceOutcomes.first { it.sourceId == "au1rxx" }.responseBytes)
    }

    @Test fun lagzianReportIsFilteredToTheRequestedCountryAndProtocolUris() {
        val source = PublicConfigCatalog.sourceEndpoints("DE").single { it.id == "lagzian-ss-collector" }
        val report = """{"per_country_proto":{"de":{"vless":["vless://de@de.example:443"],"ss":["ss://ss@de.example:443"]},"ir":{"vless":["vless://ir@ir.example:443"]}}}"""
        val result = PublicConfigFinder.fetchFromSources(listOf(source)) { report }
        assertEquals(2, result.configs.size)
        assertTrue(result.configs.all { "de.example" in it })
        assertEquals(2, result.sourceOutcomes.single().candidateCount)
    }

    @Test fun aicpListRefreshBenchmarksAndSelectsFastestOnlyWhenDisconnected() {
        assertTrue(AicpExistingListRefreshPolicy.isAicpGroup("aicp-finder-de"))
        assertTrue(AicpExistingListRefreshPolicy.isAicpGroup("aicp-multilocation"))
        assertFalse(AicpExistingListRefreshPolicy.isAicpGroup("user-subscription"))
        assertTrue(AicpExistingListRefreshPolicy.shouldBenchmarkExistingList("aicp-finder-de"))
        assertTrue(AicpExistingListRefreshPolicy.shouldBenchmarkExistingList("aicp-multilocation"))
        assertTrue(AicpExistingListRefreshPolicy.shouldBenchmarkExistingList("user-subscription"))
        assertFalse(AicpExistingListRefreshPolicy.shouldBenchmarkExistingList(""))
        assertEquals(listOf("user-a", "user-b"), AicpExistingListRefreshPolicy.groupsToSort("", listOf("", "user-a", "user-b", "user-a")))
        assertEquals(listOf("aicp-finder-de"), AicpExistingListRefreshPolicy.groupsToSort("aicp-finder-de", listOf("user-a", "user-b")))
        assertTrue(AicpExistingListRefreshPolicy.shouldSelectFastestAfterRefresh("aicp-finder-de", false))
        assertTrue(AicpExistingListRefreshPolicy.shouldSelectFastestAfterRefresh("aicp-multilocation", false))
        assertTrue(AicpExistingListRefreshPolicy.shouldSelectFastestAfterRefresh("user-subscription", false))
        assertFalse(AicpExistingListRefreshPolicy.shouldSelectFastestAfterRefresh("aicp-finder-de", true))
        assertFalse(AicpExistingListRefreshPolicy.shouldSelectFastestAfterRefresh("user-subscription", true))
    }

    @Test fun selectsFastestLatencyCandidateAmongMeasuredPositiveResultsWithinBound() {
        val result = AicpFinderQualification.fastestLatencyCandidate(linkedMapOf(
            "unmeasured" to 0L,
            "failed" to -1L,
            "too-slow" to 201L,
            "fast" to 28L,
            "faster" to 9L,
            "unknown" to null,
        ))
        assertEquals("faster" to 9L, result)
        assertEquals("only-bad" to 201L, AicpFinderQualification.fastestLatencyCandidate(mapOf("only-bad" to 201L, "none" to null)))
        assertEquals("fallback" to 250L, AicpFinderQualification.fastestLatencyCandidate(mapOf("fallback" to 250L), 300L))
        assertEquals(null, AicpFinderQualification.fastestLatencyCandidate(mapOf("too-slow" to 301L), 300L))
    }

    @Test fun finderKeepsEveryMeasuredPositiveCandidateAndPrunesOnlyFailures() {
        assertTrue(AicpFinderQualification.shouldPrune(-1L))
        assertFalse(AicpFinderQualification.shouldPrune(0L))
        assertFalse(AicpFinderQualification.shouldPrune(201L))
        assertFalse(AicpFinderQualification.shouldPrune(5_000L))
        assertFalse(AicpFinderQualification.shouldPrune(null))
        assertEquals(false, AicpFinderQualification.shouldPrune(1L))
        assertEquals(false, AicpFinderQualification.shouldPrune(200L))
    }

    @Test fun automaticRefreshRequiresValidatedInternetAndHonorsNineteenMinuteCooldown() {
        val now = 10_000_000L
        assertFalse(AicpRefreshPolicy.shouldRefresh(false, 0L, now))
        assertTrue(AicpRefreshPolicy.shouldRefresh(true, 0L, now))
        assertFalse(AicpRefreshPolicy.shouldRefresh(true, now - 60_000L, now))
        assertFalse(AicpRefreshPolicy.shouldRefresh(true, now - AicpRefreshPolicy.COOLDOWN_MS + 1L, now))
        assertTrue(AicpRefreshPolicy.shouldRefresh(true, now - AicpRefreshPolicy.COOLDOWN_MS, now))
        assertTrue(AicpRefreshPolicy.shouldRefresh(true, now, now, force = true))
    }

    @Test fun countryCatalogIsUniqueAndContainsGermanyAsDefaultCountry() {
        val codes = PublicConfigCatalog.countries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.contains("DE"))
        assertTrue(codes.contains("IR"))
        assertTrue(PublicConfigCatalog.countries.first { it.code == "DE" }.label.contains("Germany"))
    }

    @Test fun failoverUsesOnlyTheSelectedListAndMultiLocationCanCrossCountries() {
        val germany = listOf("de-fast", "de-slow", "active-de")
        assertEquals(listOf("de-fast", "de-slow"), AicpFailoverScopePolicy.candidates("aicp-finder-de", "active-de", germany))

        val multiLocation = listOf("de-fast", "fr-faster", "nl-fast", "active-de")
        assertEquals(listOf("de-fast", "fr-faster", "nl-fast"),
            AicpFailoverScopePolicy.candidates("aicp-multilocation", "active-de", multiLocation))
        assertEquals(emptyList<String>(), AicpFailoverScopePolicy.candidates("", "active", listOf("other")))
        assertEquals(listOf("one"), AicpFailoverScopePolicy.candidates("user-list", "active", listOf("active", "one", "one")))
    }

    @Test fun existingListRefreshKeepsEveryPositiveLatencyAndRemovesOnlyUnreachableProfiles() {
        assertFalse(AicpExistingListRefreshPolicy.shouldRemove(null))
        assertTrue(AicpExistingListRefreshPolicy.shouldRemove(-1L))
        assertFalse(AicpExistingListRefreshPolicy.shouldRemove(0L))
        assertFalse(AicpExistingListRefreshPolicy.shouldRemove(1L))
        assertFalse(AicpExistingListRefreshPolicy.shouldRemove(200L))
        assertFalse(AicpExistingListRefreshPolicy.shouldRemove(850L))
        assertFalse(AicpExistingListRefreshPolicy.shouldRemove(5_000L))
    }

    @Test fun directDiscoveryFailsClosedWhenPhysicalNetworkIsUnavailable() {
        assertFalse(PublicConfigFinder.hasReachableSourceHost(null))
        val error = assertThrows(IllegalArgumentException::class.java) {
            PublicConfigFinder.fetch("DE", null)
        }
        assertTrue(error.message.orEmpty().contains("underlying non-VPN network"))
    }

    @Test fun unexpectedServiceStopSchedulesOneGuardedThreeSecondReconnect() {
        assertEquals(3_000L, AicpAutoReconnectPolicy.DISCONNECT_RECONNECT_DELAY_MS)
        assertEquals(250L, AicpAutoReconnectPolicy.RECOVERY_IDLE_POLL_MS)
        assertEquals(120, AicpAutoReconnectPolicy.RECOVERY_START_TIMEOUT_POLLS)
        assertTrue(AicpAutoReconnectPolicy.shouldSchedule(true, false, true))
        assertFalse(AicpAutoReconnectPolicy.shouldSchedule(false, false, true))
        assertFalse(AicpAutoReconnectPolicy.shouldSchedule(true, true, true))
        assertFalse(AicpAutoReconnectPolicy.shouldSchedule(true, false, false))
    }

    @Test fun unexpectedDisconnectRecoveryWaitsForRefreshAndPingToReachSafeHandoff() {
        assertTrue(AicpAutoReconnectPolicy.shouldWaitForIdle(true, false, false))
        assertTrue(AicpAutoReconnectPolicy.shouldWaitForIdle(false, true, false))
        assertTrue(AicpAutoReconnectPolicy.shouldWaitForIdle(false, false, true))
        assertTrue(AicpAutoReconnectPolicy.shouldWaitForIdle(true, true, true))
        assertFalse(AicpAutoReconnectPolicy.shouldWaitForIdle(false, false, false))
    }

    @Test fun dataPlaneFailureUsesLatencyRankingButSpeedOnlyRecoveryUsesThroughput() {
        assertTrue(AicpSmartConnectPolicy.shouldPreferLatencyFailover(0L))
        assertTrue(AicpSmartConnectPolicy.shouldPreferLatencyFailover(450L))
        assertFalse(AicpSmartConnectPolicy.shouldPreferLatencyFailover(null))
    }

    @Test fun smartConnectUsesSixtySecondHealthyAndFiveSecondRecoveryCadence() {
        assertEquals(60_000L, AicpSmartConnectPolicy.probeIntervalMs(false))
        assertEquals(5_000L, AicpSmartConnectPolicy.probeIntervalMs(true))
        assertEquals(2_000L, AicpSmartConnectPolicy.ACTIVE_TUNNEL_PROBE_TIMEOUT_MS)
        assertEquals(10_000L, AicpSmartConnectPolicy.UNHEALTHY_PERSISTENCE_MS)
        assertFalse(AicpSmartConnectPolicy.hasSustainedUnhealthy(450L, null, 13_000L))
        assertFalse(AicpSmartConnectPolicy.hasSustainedUnhealthy(450L, 10_000L, 19_999L))
        assertTrue(AicpSmartConnectPolicy.hasSustainedUnhealthy(450L, 10_000L, 20_000L))
        assertFalse(AicpSmartConnectPolicy.hasSustainedUnhealthy(150L, 10_000L, 20_000L))
        assertFalse(AicpSmartConnectPolicy.hasSustainedUnhealthy(450L, 41_000L, 40_000L))
        assertEquals(2, AicpSmartConnectPolicy.HEALTHY_PROBES_TO_EXIT_RECOVERY)
        assertTrue(AicpSmartConnectPolicy.isUnhealthy(301L))
        assertTrue(AicpSmartConnectPolicy.isUnhealthy(0L))
        assertFalse(AicpSmartConnectPolicy.isUnhealthy(null))
        assertTrue(AicpSmartConnectPolicy.isHealthy(1L))
        assertTrue(AicpSmartConnectPolicy.isHealthy(300L))
        assertFalse(AicpSmartConnectPolicy.isHealthy(301L))
        assertFalse(AicpSmartConnectPolicy.isHealthy(null))
        assertTrue(AicpSmartConnectPolicy.isBetterFailoverCandidate(800L, 700L))
        assertFalse(AicpSmartConnectPolicy.isBetterFailoverCandidate(800L, 750L))
        assertFalse(AicpSmartConnectPolicy.isBetterFailoverCandidate(250L, 100L))
        assertTrue(AicpSmartConnectPolicy.isBetterFailoverCandidate(-1L, 250L))
        assertFalse(AicpSmartConnectPolicy.isBetterFailoverCandidate(800L, -1L))
        assertFalse(AicpSmartConnectPolicy.isBetterFailoverCandidate(800L, null))
        assertEquals(1, AicpSmartConnectPolicy.nextHealthyProbeCount(true, 0, 120L))
        assertEquals(2, AicpSmartConnectPolicy.nextHealthyProbeCount(true, 1, 150L))
        assertEquals(0, AicpSmartConnectPolicy.nextHealthyProbeCount(true, 1, 301L))
        assertEquals(0, AicpSmartConnectPolicy.nextHealthyProbeCount(false, 1, 150L))
        assertFalse(AicpSmartConnectPolicy.shouldExitRecovery(1))
        assertTrue(AicpSmartConnectPolicy.shouldExitRecovery(2))
        assertTrue(AicpSmartConnectPolicy.shouldProbe(true, true, false, false, false, true))
        assertFalse(AicpSmartConnectPolicy.shouldProbe(false, true, false, false, false, true))
        assertFalse(AicpSmartConnectPolicy.shouldProbe(true, false, false, false, false, true))
        assertFalse(AicpSmartConnectPolicy.shouldProbe(true, true, true, false, false, true))
        assertFalse(AicpSmartConnectPolicy.shouldProbe(true, true, false, true, false, true))
        assertFalse(AicpSmartConnectPolicy.shouldProbe(true, true, false, false, true, true))
        assertFalse(AicpSmartConnectPolicy.shouldProbe(true, true, false, false, false, false))
        assertTrue(AicpSmartConnectPolicy.isCurrentProfile("profile-a", "profile-a"))
        assertFalse(AicpSmartConnectPolicy.isCurrentProfile("profile-a", "profile-b"))
        assertFalse(AicpSmartConnectPolicy.isCurrentProfile(null, "profile-a"))
        assertFalse(AicpSmartConnectPolicy.isCurrentProfile("", "profile-a"))
        assertTrue(AicpSmartConnectPolicy.isCurrentRecoveryContext("profile-a", "profile-a", "aicp-finder-de", "aicp-finder-de"))
        assertFalse(AicpSmartConnectPolicy.isCurrentRecoveryContext("profile-a", "profile-b", "aicp-finder-de", "aicp-finder-de"))
        assertFalse(AicpSmartConnectPolicy.isCurrentRecoveryContext("profile-a", "profile-a", "aicp-finder-de", "aicp-finder-ir"))
        assertTrue(AicpSmartConnectPolicy.mayApplyRecoveryResult("profile-a", "profile-a", "aicp-finder-de", "aicp-finder-de", true, true, true))
        assertFalse(AicpSmartConnectPolicy.mayApplyRecoveryResult("profile-a", "profile-b", "aicp-finder-de", "aicp-finder-de", true, true, true))
        assertFalse(AicpSmartConnectPolicy.mayApplyRecoveryResult("profile-a", "profile-a", "aicp-finder-de", "aicp-finder-de", true, false, true))
        // Manual Smart Connect stop disables the feature, so a late recovery result cannot switch profiles.
        assertFalse(AicpSmartConnectPolicy.mayApplyRecoveryResult("profile-a", "profile-a", "aicp-finder-de", "aicp-finder-de", true, true, false))
        // A tab change invalidates the original recovery context even if the same profile GUID still exists.
        assertFalse(AicpSmartConnectPolicy.mayApplyRecoveryResult("profile-a", "profile-a", "aicp-finder-de", "aicp-finder-ir", true, true, true))
        assertTrue(AicpSmartConnectPolicy.isCurrentRecoveryContext("profile-a", "profile-a", "", ""))
        assertTrue(AicpSmartConnectPolicy.isCurrentRecoveryContext("profile-a", "profile-a", "user-subscription", ""))
        assertFalse(AicpSmartConnectPolicy.isCurrentRecoveryContext("profile-a", "profile-a", "aicp-finder-de", "aicp-finder-ir"))
    }

    @Test fun smartConnectComparesLikeForLikeGraphRatesAndRequiresPersistentLargeGap() {
        assertTrue(AicpSmartConnectPolicy.isTunnelMuchSlower(2_000_000.0, 300_000.0, 0.0, 0.0))
        assertTrue(AicpSmartConnectPolicy.isTunnelMuchSlower(0.0, 0.0, 1_000_000.0, 100_000.0))
        assertFalse(AicpSmartConnectPolicy.isTunnelMuchSlower(2_000_000.0, 1_000_000.0, 0.0, 0.0))
        assertFalse(AicpSmartConnectPolicy.isTunnelMuchSlower(100_000.0, 0.0, 0.0, 0.0))
        assertFalse(AicpSmartConnectPolicy.isTunnelMuchSlower(Double.NaN, 0.0, 0.0, 0.0))
        assertTrue(AicpSmartConnectPolicy.isBetterSpeedCandidate(1_000_000L, 1_600_000L))
        assertFalse(AicpSmartConnectPolicy.isBetterSpeedCandidate(1_000_000L, 1_400_000L))
        assertTrue(AicpSmartConnectPolicy.isBetterSpeedCandidate(0L, 100_000L))
        assertFalse(AicpSmartConnectPolicy.isBetterSpeedCandidate(100_000L, 0L))
    }

    @Test fun nineteenMinuteRefreshFollowsTheSelectedAicpTab() {
        assertEquals("IT", AicpSelectedRefreshTargetPolicy.resolveCountry("aicp-finder-it", "DE"))
        assertEquals("GL", AicpSelectedRefreshTargetPolicy.resolveCountry("aicp-multilocation", "DE"))
        assertEquals("FI", AicpSelectedRefreshTargetPolicy.resolveCountry("", "FI"))
        assertEquals(null, AicpSelectedRefreshTargetPolicy.resolveCountry("user-subscription", "DE"))
        assertEquals(null, AicpSelectedRefreshTargetPolicy.resolveCountry("aicp-finder-invalid", "DE"))
    }

    @Test fun rejectsUnknownCountryCode() {
        assertThrows(IllegalArgumentException::class.java) { PublicConfigFinder.sourceUrls("../../example.com") }
        assertThrows(IllegalArgumentException::class.java) { PublicConfigFinder.sourceUrls("T1") }
    }

    @Test fun extractsDeduplicatesAndCapsSupportedUris() {
        val first = "vless://uuid@de.example:443#Germany\nvmess://abc@de.example:443#Germany"
        val second = "vless://uuid@de.example:443#AnotherLabel\ntrojan://secret@de2.example:443#Germany"
        val result = PublicConfigFinder.collectConfigs(listOf(first, second))
        assertEquals(3, result.size)
        assertTrue(result.any { it.startsWith("vless://") })
        assertTrue(result.any { it.startsWith("vmess://") })
        assertTrue(result.any { it.startsWith("trojan://") })
    }

    @Test fun decodesBase64SubscriptionPayload() {
        val plain = "vless://id@de.example:443#Germany\ntrojan://pw@de2.example:443#Germany"
        val encoded = Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))
        assertEquals(2, PublicConfigFinder.collectConfigs(listOf(encoded)).size)
    }

    @Test fun decodesWholeFileBase64SubscriptionWithCommentHeaders() {
        val plain = "#profile-title: test\n#profile-update-interval: 1\nvless://id@de.example:443#Germany\ntrojan://pw@de2.example:443#Germany"
        val encoded = Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))
        val result = PublicConfigFinder.collectConfigs(listOf(encoded))
        assertEquals(2, result.size)
        assertTrue(result.any { it.startsWith("vless://") })
        assertTrue(result.any { it.startsWith("trojan://") })
    }

    @Test fun decodesFeedWhereEachUriIsBase64EncodedOnItsOwnLine() {
        val plain = listOf("ss://YWVzLTEyOC1nY206cHc@de.example:443#ss", "vless://id@de2.example:443#vless")
        val encodedLines = plain.joinToString("\n") { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
        val result = PublicConfigFinder.collectConfigs(listOf(encodedLines))
        assertEquals(2, result.size)
        assertTrue(result.any { it.startsWith("ss://") })
        assertTrue(result.any { it.startsWith("vless://") })
    }


    @Test fun balancesLargeFeedsAcrossSourceAndProtocolLanes() {
        val largeFeed = (1..20).joinToString("\n") { "vless://a$it@large.example:443#DE" } +
            "\n" + (1..5).joinToString("\n") { "ss://p$it@large.example:443#DE" }
        val smallFeed = (1..8).joinToString("\n") { "vless://b$it@small.example:443#DE" }
        val result = PublicConfigFinder.collectConfigs(listOf(largeFeed, smallFeed), limit = 12)
        assertEquals(12, result.size)
        assertTrue(result.any { "small.example" in it })
        assertTrue(result.any { it.startsWith("ss://") })
    }

    @Test fun acceptsHysteria2ShortSchemeUsedByPublicFeeds() {
        assertEquals(1, PublicConfigFinder.collectConfigs(listOf("hy2://password@de.example:443#Germany")).size)
    }

    @Test fun ignoresYamlAndGarbageLines() {
        val text = "proxies:\n- name: example\nss://secret@de.example:443#Germany\nnot-a-config"
        assertEquals(1, PublicConfigFinder.collectConfigs(listOf(text)).size)
    }

    @Test fun originNotFoundStopsMirrorRetriesButOtherFailuresFallThrough() {
        val calls = ArrayList<String>()
        val notFound = assertThrows(PublicConfigFinder.FeedHttpException::class.java) {
            PublicConfigFinder.fetchFirstAvailable(listOf("origin", "m1", "m2")) { calls += it; throw PublicConfigFinder.FeedHttpException(404) }
        }
        assertEquals(404, notFound.status)
        assertEquals(listOf("origin"), calls)

        calls.clear()
        val body = PublicConfigFinder.fetchFirstAvailable(listOf("origin", "m1", "m2")) {
            calls += it
            if (it == "m2") "ok" else throw java.io.IOException("blocked")
        }
        assertEquals("ok", body)
        assertEquals(listOf("origin", "m1", "m2"), calls)

        calls.clear()
        val viaGithack = PublicConfigFinder.fetchFirstAvailable(listOf("origin", "m1", "m2")) {
            calls += it
            when (it) { "origin" -> throw java.io.IOException("timeout"); "m1" -> throw PublicConfigFinder.FeedHttpException(404); else -> "ok2" }
        }
        assertEquals("ok2", viaGithack)
        assertEquals(3, calls.size)
    }
}
