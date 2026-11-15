package com.v2ray.ang.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

@Composable
internal fun PublicConfigCountryDialog(onDismiss: () -> Unit, onSelect: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AicpDestinationDefaults.ensure(context); context.getSharedPreferences("aicp_network_identity", 0) }
    var selected by remember { mutableStateOf((prefs.getString("target_country", "DE") ?: "DE").ifBlank { "DE" }) }
    var search by remember { mutableStateOf("") }
    var multiLocationRecovery by remember { mutableStateOf(false) }
    val selectedCountry = PublicConfigCatalog.countries.firstOrNull { it.code == selected }
    val targetRegion = if (selected == "DE") "Hesse" else prefs.getString("target_region", "").orEmpty()
    val targetCity = if (selected == "DE") "Frankfurt am Main" else prefs.getString("target_city", "").orEmpty()
    val targetCountryLabel = selectedCountry?.label ?: "🇩🇪 Germany / آلمان"
    val filtered = PublicConfigCatalog.countries.filter {
        it.code.contains(search.trim(), ignoreCase = true) || it.label.contains(search.trim(), ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.finder_country_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.finder_country_note), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.finder_target_location, targetCountryLabel,
                        targetRegion.ifBlank { "—" }, targetCity.ifBlank { "—" }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = multiLocationRecovery, onCheckedChange = { multiLocationRecovery = it })
                    Text(stringResource(R.string.finder_multi_location_recovery), style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.identity_search_country)) }
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 340.dp)) {
                    items(filtered, key = { it.code }) { country ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { selected = country.code }
                                .padding(horizontal = 4.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected == country.code, onClick = { selected = country.code })
                            Text(country.label, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 2)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val previousCountry = prefs.getString("target_country", "DE") ?: "DE"
                val region = if (selected == "DE") "Hesse" else if (previousCountry != selected) "" else prefs.getString("target_region", "").orEmpty()
                val city = if (selected == "DE") "Frankfurt am Main" else if (previousCountry != selected) "" else prefs.getString("target_city", "").orEmpty()
                AicpSharedState.persistTargetLocation(context, selected, region, city)
                onSelect(selected, multiLocationRecovery)
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
