package com.v2ray.ang.discovery

/** A fixed, allowlisted endpoint for one independently observable public config provider. */
internal data class PublicConfigSourceEndpoint(
    val id: String,
    val url: String,
    val countryCode: String? = null,
)

internal interface PublicConfigSourceProvider {
    val id: String
    fun urlForCountry(countryCode: String, countryFileName: String?): String?
    fun endpointsForCountry(countryCode: String, countryFileName: String?): List<PublicConfigSourceEndpoint> =
        urlForCountry(countryCode, countryFileName)?.let { listOf(PublicConfigSourceEndpoint(id, it)) }.orEmpty()
}

/** Country-specific V2Ray subscription shards published by Au1rxx. */
internal object Au1rxxConfigSource : PublicConfigSourceProvider {
    override val id: String = "au1rxx"
    override fun urlForCountry(countryCode: String, countryFileName: String?): String =
        "https://raw.githubusercontent.com/Au1rxx/free-vpn-subscriptions/main/output/by-country/v2ray-base64-${countryCode}.txt"
}

/** Second independent country-feed repository; only emit URLs with a known static filename mapping. */
internal object RadikalConfigSource : PublicConfigSourceProvider {
    override val id: String = "0xradikal"
    override fun urlForCountry(countryCode: String, countryFileName: String?): String? =
        countryFileName?.let {
            "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/Countries/${it}.txt"
        }
}

/** Third independent provider; output is country-tagged by the provider's published GeoIP pipeline. */
internal object V2goConfigSource : PublicConfigSourceProvider {
    override val id: String = "danialsamadi-v2go"
    override fun urlForCountry(countryCode: String, countryFileName: String?): String =
        "https://raw.githubusercontent.com/Danialsamadi/v2go/main/Splitted-By-Country/${countryCode}.txt"
}

/** Country-split feed whose pipeline validates supported protocols through sing-box/Xray before publishing. */
internal object MrDevMohamedConfigSource : PublicConfigSourceProvider {
    override val id: String = "mrdevmohamed-v2ray-configs"

    // Country files are created dynamically by the upstream pipeline; unsupported countries may return 404.
    override fun urlForCountry(countryCode: String, countryFileName: String?): String? =
        countryCode.uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) }?.let {
            "https://raw.githubusercontent.com/mrdevmohamed/v2ray-configs/main/By-Country/${it}.txt"
        }
}

/** Fourth independent source with country-specific files and an automated public health-check pipeline. */
internal object IdolvpnCollectorSource : PublicConfigSourceProvider {
    override val id: String = "idolvpn-collector"
    override fun urlForCountry(countryCode: String, countryFileName: String?): String =
        "https://raw.githubusercontent.com/Idolvpn/Automate-V2ray-Config-Collector/main/configs/country_${countryCode}.txt"
}

/** Country-split public feed maintained by Mokafela; only request countries listed by its published catalog. */
internal object MokafelaConfigFinderSource : PublicConfigSourceProvider {
    override val id: String = "mokafela-config-finder"
    private val supportedCountries = setOf(
        "NL", "US", "CA", "DE", "PL", "FR", "GB", "RU", "SG", "JP", "AU",
        "FI", "SE", "CH", "IT", "ES", "TR", "HK", "BR", "IN", "ID"
    )

    override fun urlForCountry(countryCode: String, countryFileName: String?): String? =
        countryCode.uppercase().takeIf { it in supportedCountries }?.let {
            "https://raw.githubusercontent.com/Mokafela/Config-Finder/master/split/sub-${it}.txt"
        }
}

/** Country-aware TCP-screened collector. Its test report publishes per-country protocol URI arrays. */
internal object LagzianConfigSource : PublicConfigSourceProvider {
    override val id: String = "lagzian-ss-collector"
    override fun urlForCountry(countryCode: String, countryFileName: String?): String =
        "https://raw.githubusercontent.com/lagzian/SS-Collector/main/test_report.json"

    override fun endpointsForCountry(countryCode: String, countryFileName: String?): List<PublicConfigSourceEndpoint> =
        listOf(PublicConfigSourceEndpoint(id, urlForCountry(countryCode, countryFileName), countryCode.uppercase(java.util.Locale.ROOT)))
}


/** Iran-specific public feeds. These are discovery inputs, never trusted as live until per-profile probing. */
internal object IranCarrierConfigSources {
    fun endpoints(): List<PublicConfigSourceEndpoint> = listOf(
        // The Radikal repository also publishes an Iran country shard; keep it alongside the
        // carrier-specific Iranian feeds instead of losing it to the IR-specific registry branch.
        requireNotNull(RadikalConfigSource.urlForCountry("IR", "Iran")).let {
            PublicConfigSourceEndpoint(RadikalConfigSource.id, it)
        },
        PublicConfigSourceEndpoint("bllare-mci", "https://raw.githubusercontent.com/Bllare/V2ray-Configs/main/MCI"),
        PublicConfigSourceEndpoint("bllare-mobinet", "https://raw.githubusercontent.com/Bllare/V2ray-Configs/main/Mobinet"),
        PublicConfigSourceEndpoint("bllare-irancell", "https://raw.githubusercontent.com/Bllare/V2ray-Configs/main/Irancell"),
        PublicConfigSourceEndpoint("miladtahanian-iran-mixed", "https://raw.githubusercontent.com/miladtahanian/Config-Collector/main/mixed_iran.txt"),
        PublicConfigSourceEndpoint("vpnineh-iran-isp", "https://raw.githubusercontent.com/vpnineh/configs-collector-v2ray/main/sub/countries/IR.txt"),
        PublicConfigSourceEndpoint("mrdevmohamed-v2ray-configs", "https://raw.githubusercontent.com/mrdevmohamed/v2ray-configs/main/By-Country/IR.txt"),
        PublicConfigSourceEndpoint("zendanteam-ss44-iran", "https://raw.githubusercontent.com/ZendanTeam/SS44-sub/main/SS44-gold.txt"),
        LagzianConfigSource.endpointsForCountry("IR", null).single(),
    )
}

/** Ordered registry: providers are isolated so one missing feed does not suppress the others. */
internal object PublicConfigSourceRegistry {
    private val providers: List<PublicConfigSourceProvider> = listOf(
        Au1rxxConfigSource,
        RadikalConfigSource,
        V2goConfigSource,
        MrDevMohamedConfigSource,
        IdolvpnCollectorSource,
        MokafelaConfigFinderSource,
        LagzianConfigSource,
    )

    fun forCountry(countryCode: String, countryFileName: String?): List<PublicConfigSourceEndpoint> {
        if (countryCode.equals("IR", ignoreCase = true)) return IranCarrierConfigSources.endpoints()
        return providers.flatMap { provider ->
            provider.endpointsForCountry(countryCode, countryFileName)
        }
    }
}
