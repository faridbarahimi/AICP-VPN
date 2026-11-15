package com.v2ray.ang.ui.main

import android.content.Context
import com.v2ray.ang.discovery.PublicConfigSourceEndpoint
import com.v2ray.ang.discovery.PublicConfigSourceRegistry
import com.v2ray.ang.handler.MmkvManager
import java.util.Locale

/** Country/provider metadata stays independent from Compose UI so providers can be extended safely. */
internal object PublicConfigCatalog {
    data class Country(val code: String, val label: String)

    // Verified against the current public repository manifests on 2026-10-10: only show countries with at least one shard.
    private val au1rxxCountryCodes = setOf(
        "AE", "AL", "AT", "AU", "CA", "CH", "DE", "EE", "ES", "FI", "FR", "GB", "HK", "ID", "IN",
        "IT", "JP", "KR", "LT", "LV", "NL", "PL", "RO", "SE", "SG", "TH", "TR", "TW", "US", "VN"
    )

    internal val countryFileNames = mapOf(
        "DZ" to "Algeria", "AM" to "Armenia", "AU" to "Australia", "AT" to "Austria", "BY" to "Belarus",
        "BG" to "Bulgaria", "CA" to "Canada", "CN" to "China", "CZ" to "Czechia", "EE" to "Estonia",
        "FI" to "Finland", "FR" to "France", "DE" to "Germany", "GR" to "Greece", "HK" to "Hong_Kong",
        "IS" to "Iceland", "IN" to "India", "ID" to "Indonesia", "IE" to "Ireland", "IT" to "Italy",
        "JP" to "Japan", "KZ" to "Kazakhstan", "LV" to "Latvia", "LT" to "Lithuania", "MD" to "Moldova",
        "NO" to "Norway", "PL" to "Poland", "PT" to "Portugal", "RO" to "Romania", "RU" to "Russia",
        "RS" to "Serbia", "SC" to "Seychelles", "SG" to "Singapore", "ZA" to "South_Africa",
        "KR" to "South_Korea", "ES" to "Spain", "SE" to "Sweden", "CH" to "Switzerland", "TW" to "Taiwan",
        "TH" to "Thailand", "NL" to "The_Netherlands", "TR" to "Turkey", "UA" to "Ukraine",
        "AE" to "United_Arab_Emirates", "GB" to "United_Kingdom", "US" to "United_States"
    )
    private val feedCountryCodes = au1rxxCountryCodes + countryFileNames.keys + "IR"

    val countries: List<Country> = feedCountryCodes.map { code ->
        Country(code, "${flag(code)} ${Locale("", code).getDisplayCountry(Locale.ENGLISH).ifBlank { code }} / ${Locale("", code).getDisplayCountry(Locale("fa" )).ifBlank { code }}")
    }.sortedBy { it.label }

    fun sourceEndpoints(countryCode: String): List<PublicConfigSourceEndpoint> {
        val code = countryCode.uppercase(Locale.ROOT)
        require(code in feedCountryCodes) { "Unsupported country code" }
        // URLs come only from the fixed provider registry; arbitrary/user-supplied hosts are never fetched.
        return PublicConfigSourceRegistry.forCountry(code, countryFileNames[code]).distinctBy { it.url }
    }

    fun sourceUrls(countryCode: String): List<String> = sourceEndpoints(countryCode).map { it.url }

    private fun flag(code: String): String = code.uppercase(Locale.ROOT).map { char ->
        String(Character.toChars(0x1F1E6 + (char.code - 'A'.code)))
    }.joinToString(separator = "")
}


/** Cross-process AICP state. WorkManager runs in :bg, so MMKV is the canonical store; SharedPreferences
 * are dual-written only to preserve compatibility with existing UI and verifier readers. */
internal object AicpSharedState {
    private const val IDENTITY_PREFS = "aicp_network_identity"
    private const val REFRESH_PREFS = "aicp_config_refresh"
    private const val TARGET_COUNTRY_KEY = "aicp_target_country_v1"
    private const val TARGET_REGION_KEY = "aicp_target_region_v1"
    private const val TARGET_CITY_KEY = "aicp_target_city_v1"

    private fun refreshKey(countryCode: String) = "aicp_refresh_last_success_${countryCode.uppercase(Locale.ROOT)}_v1"

    fun targetCountry(context: Context): String {
        val legacy = context.getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE)
            .getString("target_country", "DE").orEmpty()
        val stored = MmkvManager.decodeSettingsString(TARGET_COUNTRY_KEY, legacy)
            ?.trim()?.uppercase(Locale.ROOT).orEmpty()
        val resolved = stored.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "DE"
        if (MmkvManager.decodeSettingsString(TARGET_COUNTRY_KEY, null).isNullOrBlank()) {
            MmkvManager.encodeSettings(TARGET_COUNTRY_KEY, resolved)
        }
        return resolved
    }

    fun persistTargetLocation(context: Context, countryCode: String, region: String, city: String) {
        val country = countryCode.trim().uppercase(Locale.ROOT).takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "DE"
        val normalizedRegion = region.trim()
        val normalizedCity = city.trim()
        MmkvManager.encodeSettings(TARGET_COUNTRY_KEY, country)
        MmkvManager.encodeSettings(TARGET_REGION_KEY, normalizedRegion)
        MmkvManager.encodeSettings(TARGET_CITY_KEY, normalizedCity)
        context.getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE).edit()
            .putString("target_country", country)
            .putString("target_region", normalizedRegion)
            .putString("target_city", normalizedCity)
            .apply()
    }

    fun lastRefreshSuccess(context: Context, countryCode: String): Long {
        val country = countryCode.uppercase(Locale.ROOT)
        val legacy = context.getSharedPreferences(REFRESH_PREFS, Context.MODE_PRIVATE)
            .getLong("last_success_$country", 0L)
        val key = refreshKey(country)
        val stored = MmkvManager.decodeSettingsLong(key, legacy)
        if (stored <= 0L && legacy > 0L) MmkvManager.encodeSettings(key, legacy)
        return stored
    }

    fun recordRefreshSuccess(context: Context, countryCode: String, timestampMillis: Long = System.currentTimeMillis()) {
        val country = countryCode.uppercase(Locale.ROOT)
        MmkvManager.encodeSettings(refreshKey(country), timestampMillis)
        context.getSharedPreferences(REFRESH_PREFS, Context.MODE_PRIVATE).edit()
            .putLong("last_success_$country", timestampMillis)
            .apply()
    }
}

/** Resolve the 19-minute worker's source country from the persisted selected tab, never a stale target tab. */
internal object AicpSelectedRefreshTargetPolicy {
    fun resolveCountry(selectedGroupId: String, fallbackCountry: String): String? {
        val selected = selectedGroupId.trim()
        val country = when {
            selected.startsWith("aicp-finder-") -> selected.removePrefix("aicp-finder-")
            selected == "aicp-multilocation" || selected.isBlank() -> fallbackCountry
            else -> return null
        }.trim().uppercase(Locale.ROOT)
        return country.takeIf { it.matches(Regex("[A-Z]{2}")) }
    }
}

/** Shared, versioned defaults used before any AICP region UI reads persisted state. */
internal object AicpDestinationDefaults {
    private const val PREFS = "aicp_network_identity"
    private const val MIGRATION_KEY = "aicp_frankfurt_defaults_v2"

    fun ensure(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val legacyCountry = prefs.getString("target_country", null)?.trim()?.uppercase(Locale.ROOT).orEmpty()
        var resolvedCountry = AicpSharedState.targetCountry(context)
        var resolvedRegion = prefs.getString("target_region", null).orEmpty()
        var resolvedCity = prefs.getString("target_city", null).orEmpty()
        val edit = prefs.edit()
        var changed = false
        if (legacyCountry != resolvedCountry) {
            // MMKV is canonical across processes; stale legacy region/city must not follow a newer country.
            resolvedRegion = ""
            resolvedCity = ""
        }
        if (!prefs.getBoolean(MIGRATION_KEY, false)) {
            // Preserve an existing country selection; only seed Frankfurt when the resolved country is DE.
            if (resolvedCountry == "DE") {
                if (resolvedRegion.isBlank()) resolvedRegion = "Hesse"
                if (resolvedCity.isBlank()) resolvedCity = "Frankfurt am Main"
            }
            edit.putString("target_country", resolvedCountry)
                .putString("target_region", resolvedRegion)
                .putString("target_city", resolvedCity)
                .putBoolean(MIGRATION_KEY, true)
            changed = true
        } else {
            if (resolvedCountry == "DE") {
                if (resolvedRegion.isBlank()) { resolvedRegion = "Hesse"; edit.putString("target_region", resolvedRegion); changed = true }
                if (resolvedCity.isBlank()) { resolvedCity = "Frankfurt am Main"; edit.putString("target_city", resolvedCity); changed = true }
            }
        }
        if (changed) edit.apply()
        AicpSharedState.persistTargetLocation(context, resolvedCountry.ifBlank { "DE" }, resolvedRegion, resolvedCity)
    }
}
