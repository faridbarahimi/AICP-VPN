package com.v2ray.ang.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import android.widget.Toast
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.core.AicpEgressVerifier
import com.v2ray.ang.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class CountryOption(val code: String, val name: String) {
    val display: String get() = "$name ($code)"
}

@Composable
internal fun AicpIdentityDialog(isVpnRunning: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val prefs = remember { AicpDestinationDefaults.ensure(context); context.getSharedPreferences("aicp_network_identity", 0) }
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val allCountries = remember {
        Locale.getISOCountries().filter { it != "IR" }.map { code ->
            CountryOption(code, Locale("", code).getDisplayCountry(Locale.ENGLISH).ifBlank { code })
        }.distinctBy { it.code }.sortedBy { it.name }
    }
    var countryCode by remember { mutableStateOf(prefs.getString("target_country", "DE") ?: "DE") }
    var region by remember { mutableStateOf((prefs.getString("target_region", "Hesse") ?: "Hesse").ifBlank { if ((prefs.getString("target_country", "DE") ?: "DE") == "DE") "Hesse" else "" }) }
    var city by remember { mutableStateOf((prefs.getString("target_city", "Frankfurt am Main") ?: "Frankfurt am Main").ifBlank { if ((prefs.getString("target_country", "DE") ?: "DE") == "DE") "Frankfurt am Main" else "" }) }
    var countryPickerExpanded by remember { mutableStateOf(false) }
    var countrySearch by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var baselineLocation by remember { mutableStateOf<AicpEgressVerifier.ExitLocation?>(null) }
    var report by remember { mutableStateOf<AicpEgressVerifier.Snapshot?>(null) }
    val scope = rememberCoroutineScope()
    val selectedCountry = allCountries.firstOrNull { it.code == countryCode } ?: allCountries.first()

    fun persistTarget() {
        AicpSharedState.persistTargetLocation(context, countryCode, region, city)
    }
    fun runBaseline() {
        if (isVpnRunning || loading) return
        persistTarget()
        loading = true
        message = ""
        scope.launch {
            try {
                baselineLocation = withContext(Dispatchers.IO) { AicpEgressVerifier.captureBaseline(context) }
                val baseline = baselineLocation!!
                message = resources.getString(R.string.identity_baseline_saved, baseline.ip, baseline.countryCode)
                report = null
            } catch (error: Exception) {
                message = if (error.message?.contains("Disconnect VPNs", ignoreCase = true) == true) {
                    resources.getString(R.string.identity_baseline_disconnect_vpn)
                } else resources.getString(R.string.identity_lookup_failed)
            } finally { loading = false }
        }
    }
    fun runVerification() {
        if (!isVpnRunning || loading) return
        persistTarget()
        loading = true
        message = ""
        scope.launch {
            try {
                report = withContext(Dispatchers.IO) { AicpEgressVerifier.verify(context) }
                message = ""
            } catch (_: Exception) {
                message = resources.getString(R.string.identity_lookup_failed)
            } finally { loading = false }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(stringResource(R.string.identity_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.identity_explainer), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.identity_country_label), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = selectedCountry.display,
                    onValueChange = {}, readOnly = true, singleLine = true,
                    modifier = Modifier.fillMaxWidth().clickable { countryPickerExpanded = true },
                    label = { Text(stringResource(R.string.identity_country_label)) }
                )
                if (countryPickerExpanded) {
                    AlertDialog(
                        onDismissRequest = { countryPickerExpanded = false },
                        title = { Text(stringResource(R.string.identity_country_label)) },
                        text = {
                            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = countrySearch,
                                    onValueChange = { countrySearch = it },
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.identity_search_country)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp)) {
                                    items(allCountries.filter { it.display.contains(countrySearch.trim(), ignoreCase = true) }, key = { it.code }) { item ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                val previousCountry = countryCode
                                                countryCode = item.code
                                                countryPickerExpanded = false
                                                countrySearch = ""
                                                if (item.code == "DE") {
                                                    region = "Hesse"
                                                    city = "Frankfurt am Main"
                                                } else if (previousCountry != item.code) {
                                                    region = ""
                                                    city = ""
                                                }
                                                report = null
                                            }.padding(vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(item.display, modifier = Modifier.weight(1f), maxLines = 2)
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { countryPickerExpanded = false }) { Text(stringResource(R.string.action_cancel)) } }
                    )
                }
                OutlinedTextField(
                    value = region, onValueChange = { region = it; report = null },
                    label = { Text(stringResource(R.string.identity_region_label)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (countryCode == "DE") {
                    Text(stringResource(R.string.identity_common_german_regions), style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("Hesse", "Bavaria", "Berlin", "Hamburg", "North Rhine-Westphalia", "Baden-Württemberg").forEach { option ->
                            AssistChip(
                                onClick = {
                                    region = option
                                    city = when (option) {
                                        "Hesse" -> "Frankfurt am Main"
                                        "Berlin" -> "Berlin"
                                        "Hamburg" -> "Hamburg"
                                        else -> ""
                                    }
                                    report = null
                                },
                                label = { Text(option, maxLines = 1) }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = city, onValueChange = { city = it; report = null },
                    label = { Text(stringResource(R.string.identity_city_label)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (countryCode == "DE" && region.trim().equals("Hesse", ignoreCase = true) ||
                    countryCode == "DE" && region.trim().equals("Hessen", ignoreCase = true)) {
                    Text(stringResource(R.string.identity_frankfurt_area), style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("Frankfurt am Main", "Wiesbaden", "Darmstadt", "Kassel").forEach { option ->
                            AssistChip(onClick = { city = option; report = null }, label = { Text(option, maxLines = 1) })
                        }
                    }
                }
                TextButton(onClick = {
                    countryCode = "DE"
                    region = "Hesse"
                    city = "Frankfurt am Main"
                    report = null
                    persistTarget()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.identity_use_frankfurt_default))
                }
                if (loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.identity_working))
                }
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                report?.let { snapshot ->
                    if (snapshot.baselineIp == null) {
                        Text(stringResource(R.string.identity_baseline_missing), color = MaterialTheme.colorScheme.error)
                    }
                    val target = AicpEgressVerifier.Target(countryCode, region, city)
                    val checks = AicpEgressVerifier.evaluate(snapshot, target)
                    val selectedDestination = listOf(selectedCountry.name, region.trim(), city.trim())
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    Text(stringResource(R.string.identity_target, selectedDestination), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(if (checks.confirmed) R.string.identity_confirmed else R.string.identity_attention),
                        color = if (checks.confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(stringResource(R.string.identity_observed_ip, snapshot.location.ip))
                    Text(stringResource(R.string.identity_ipv4_status,
                        snapshot.ipv4.ip ?: resources.getString(R.string.identity_not_checked),
                        snapshot.ipv4.countryCode ?: "—", snapshot.ipv4.region.ifBlank { "—" }, snapshot.ipv4.city.ifBlank { "—" }))
                    Text(stringResource(R.string.identity_ipv4_matches_exit, yesNo(checks.ipv4MatchesTarget,
                        resources.getString(R.string.identity_yes), resources.getString(R.string.identity_no))))
                    if (snapshot.ipv6.ip != null) {
                        Text(stringResource(R.string.identity_ipv6_status, snapshot.ipv6.ip,
                            snapshot.ipv6.countryCode ?: "—", snapshot.ipv6.region.ifBlank { "—" }, snapshot.ipv6.city.ifBlank { "—" }))
                        Text(stringResource(R.string.identity_ipv6_matches_exit, yesNo(checks.ipv6MatchesTarget,
                            resources.getString(R.string.identity_yes), resources.getString(R.string.identity_no))))
                    } else {
                        Text(stringResource(R.string.identity_ipv6_not_observed))
                        if (snapshot.underlyingGlobalIpv6) Text(
                            stringResource(R.string.identity_ipv6_underlay_risk), color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(stringResource(R.string.identity_observed_location,
                        snapshot.location.country.ifBlank { snapshot.location.countryCode },
                        snapshot.location.region.ifBlank { "—" }, snapshot.location.city.ifBlank { "—" }))
                    Text(stringResource(R.string.identity_network_org,
                        snapshot.location.organization.ifBlank { "—" }, snapshot.location.asn.ifBlank { "—" }))
                    Text(stringResource(R.string.identity_timezone, snapshot.location.timezone.ifBlank { "—" }))
                    val coordinates = if (snapshot.location.latitude != null && snapshot.location.longitude != null) {
                        "${"%.4f".format(Locale.US, snapshot.location.latitude)}, ${"%.4f".format(Locale.US, snapshot.location.longitude)}"
                    } else "—"
                    Text(stringResource(R.string.identity_coordinates, coordinates))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = {
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AICP exit IP", snapshot.location.ip))
                            Toast.makeText(context, R.string.identity_ip_copied, Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.identity_copy_ip)) }
                        if (snapshot.location.latitude != null && snapshot.location.longitude != null) {
                            TextButton(onClick = {
                                val mapUrl = Uri.Builder().scheme("https").authority("www.google.com")
                                    .path("/maps/search/").appendQueryParameter("api", "1")
                                    .appendQueryParameter("query", "${snapshot.location.latitude},${snapshot.location.longitude}").build()
                                context.startActivity(Intent(Intent.ACTION_VIEW, mapUrl))
                            }) { Text(stringResource(R.string.identity_open_map)) }
                        }
                    }
                    TextButton(onClick = {
                            val summary = buildString {
                                appendLine("AICP VPN Network Identity")
                                appendLine("Target: $selectedDestination ($countryCode)")
                                appendLine("IP: ${snapshot.location.ip}")
                                appendLine("Country: ${snapshot.location.country} (${snapshot.location.countryCode})")
                                appendLine("Region/City: ${snapshot.location.region} / ${snapshot.location.city}")
                                appendLine("IPv4: ${snapshot.ipv4.ip ?: "not observed"} (${snapshot.ipv4.countryCode ?: "unknown"})")
                                appendLine("IPv6: ${snapshot.ipv6.ip ?: "not observed"} (${snapshot.ipv6.countryCode ?: "unknown"})")
                                appendLine("ASN/Organization: ${snapshot.location.asn} / ${snapshot.location.organization}")
                                appendLine("Timezone: ${snapshot.location.timezone}")
                                appendLine("Coordinates (approx): $coordinates")
                                appendLine("Target country/region/city: ${checks.countryMatches}/${checks.regionMatches}/${checks.cityMatches}")
                                appendLine("Provider IP/country consensus: ${snapshot.ipsAgree}/${snapshot.countriesAgree}")
                                appendLine("Android VPN transport: ${snapshot.vpnTransportActive}; AICP proxy route: ${snapshot.proxyRouted}")
                                appendLine("IPv4/IPv6 target checks: ${checks.ipv4MatchesTarget}/${checks.ipv6MatchesTarget}")
                                appendLine("Baseline fresh/changed: ${snapshot.baselineFresh}/${snapshot.baselineChanged}")
                                appendLine("Result confirmed: ${checks.confirmed}")
                                appendLine("Device locale/timezone: ${snapshot.deviceLocale} / ${snapshot.deviceTimezone}")
                                appendLine("Observed at epoch ms: ${snapshot.observedAtMillis}")
                            }
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AICP identity report", summary))
                            Toast.makeText(context, R.string.identity_report_copied, Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.identity_copy_report)) }
                    Text(stringResource(R.string.identity_device_fingerprint, snapshot.deviceLocale, snapshot.deviceTimezone))
                    Text(stringResource(R.string.identity_timezone_matches, yesNo(
                        snapshot.location.timezone.isNotBlank() && snapshot.location.timezone == snapshot.deviceTimezone,
                        resources.getString(R.string.identity_yes), resources.getString(R.string.identity_no))))
                    val localeRegion = Locale.forLanguageTag(snapshot.deviceLocale).country
                    Text(stringResource(R.string.identity_locale_matches, yesNo(
                        localeRegion.isNotBlank() && localeRegion.equals(countryCode, ignoreCase = true),
                        resources.getString(R.string.identity_yes), resources.getString(R.string.identity_no))))
                    Text(stringResource(R.string.identity_vpn_dns,
                        snapshot.vpnDnsServers.joinToString().ifBlank { resources.getString(R.string.identity_not_checked) }))
                    Text(stringResource(R.string.identity_underlying_dns,
                        snapshot.underlyingDnsServers.joinToString().ifBlank { resources.getString(R.string.identity_not_checked) }))
                    Text(stringResource(R.string.identity_sources, snapshot.observations.size,
                        yesNo(snapshot.ipsAgree, resources.getString(R.string.identity_yes), resources.getString(R.string.identity_no)),
                        yesNo(snapshot.countriesAgree, resources.getString(R.string.identity_yes), resources.getString(R.string.identity_no))))
                    if (snapshot.baselineIp != null) Text(stringResource(R.string.identity_baseline_details,
                        snapshot.baselineIp, snapshot.baselineCountry.orEmpty(), snapshot.baselineCity.orEmpty()))
                    Text(stringResource(R.string.identity_baseline_fresh, yesNo(snapshot.baselineFresh,
                        resources.getString(R.string.identity_yes), resources.getString(R.string.identity_no))))
                    Text(stringResource(R.string.identity_baseline_comparison, yesNo(snapshot.baselineChanged,
                        resources.getString(R.string.identity_yes), resources.getString(R.string.identity_no))))
                    Text(stringResource(R.string.identity_vpn_transport, yesNo(snapshot.vpnTransportActive,
                        resources.getString(R.string.identity_yes), resources.getString(R.string.identity_no))))
                    Text(stringResource(R.string.identity_proxy_routed, yesNo(snapshot.proxyRouted,
                        resources.getString(R.string.identity_yes), resources.getString(R.string.identity_no))))
                    Text(stringResource(R.string.identity_non_iranian, yesNo(snapshot.nonIranian,
                        resources.getString(R.string.identity_yes), resources.getString(R.string.identity_no))))
                    Text(stringResource(R.string.identity_country_match, yesNo(checks.countryMatches,
                        resources.getString(R.string.identity_yes), resources.getString(R.string.identity_no))))
                    if (region.isNotBlank()) Text(stringResource(R.string.identity_region_match, yesNo(checks.regionMatches,
                        resources.getString(R.string.identity_yes), resources.getString(R.string.identity_no))))
                    if (city.isNotBlank()) Text(stringResource(R.string.identity_city_match, yesNo(checks.cityMatches,
                        resources.getString(R.string.identity_yes), resources.getString(R.string.identity_no))))
                    if (!snapshot.ipsAgree || !snapshot.countriesAgree) Text(
                        stringResource(R.string.identity_provider_disagreement), color = MaterialTheme.colorScheme.error
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_DATE_SETTINGS)) }
                            .onFailure { Toast.makeText(context, R.string.identity_system_settings_unavailable, Toast.LENGTH_SHORT).show() }
                    }) { Text(stringResource(R.string.identity_open_date_settings)) }
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS)) }
                            .onFailure { Toast.makeText(context, R.string.identity_system_settings_unavailable, Toast.LENGTH_SHORT).show() }
                    }) { Text(stringResource(R.string.identity_open_language_settings)) }
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                            .onFailure { Toast.makeText(context, R.string.identity_system_settings_unavailable, Toast.LENGTH_SHORT).show() }
                    }) { Text(stringResource(R.string.identity_open_location_settings)) }
                }
                Text(stringResource(R.string.identity_external_tests_note), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.dnsleaktest.com/"))) }) {
                        Text(stringResource(R.string.identity_external_dns_test))
                    }
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://browserleaks.com/webrtc"))) }) {
                        Text(stringResource(R.string.identity_external_webrtc_test))
                    }
                }
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.antigravity.google/docs/faq/"))) }) {
                    Text(stringResource(R.string.identity_antigravity_regions))
                }
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                        .onFailure { Toast.makeText(context, R.string.identity_system_settings_unavailable, Toast.LENGTH_SHORT).show() }
                }) { Text(stringResource(R.string.identity_open_vpn_lockdown)) }
                Text(stringResource(R.string.identity_location_estimate_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = { if (isVpnRunning) runVerification() else runBaseline() }, enabled = !loading) {
                Text(stringResource(if (isVpnRunning) R.string.identity_verify_exit else R.string.identity_capture_baseline))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !loading) { Text(stringResource(R.string.action_cancel)) } }
    )
}

private fun yesNo(value: Boolean, yes: String, no: String): String = if (value) yes else no
