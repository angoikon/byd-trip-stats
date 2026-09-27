package com.byd.tripstats.ui.screens.settings

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.data.preferences.UnitSystem
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import com.byd.tripstats.util.LocaleHelper
import kotlinx.coroutines.launch

/** Preferences → Language & units: the app's language, and metric or imperial units. */
@Composable
internal fun LanguageUnitsSection(viewModel: DashboardViewModel) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val unitSystem by viewModel.unitSystem.collectAsState()
    var showLanguageDialog by remember { mutableStateOf(false) }
    var currentLanguageTag by remember { mutableStateOf(LocaleHelper.getSelectedTag(context)) }

    SectionHeader(icon = Icons.Filled.Translate, title = stringResource(R.string.pref_language_units_title))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.pref_language),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.language_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.current_language_label, LocaleHelper.displayNameForTag(currentLanguageTag)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = { showLanguageDialog = true }) {
                Icon(Icons.Filled.Language, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.change_language_action))
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.pref_units),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.units_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { scope.launch { viewModel.saveUnitSystem(UnitSystem.METRIC) } },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (unitSystem == UnitSystem.METRIC)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (unitSystem == UnitSystem.METRIC)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(stringResource(R.string.units_metric), fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { scope.launch { viewModel.saveUnitSystem(UnitSystem.IMPERIAL) } },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (unitSystem == UnitSystem.IMPERIAL)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (unitSystem == UnitSystem.IMPERIAL)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(stringResource(R.string.units_imperial), fontWeight = FontWeight.Bold)
                }
            }
            Text(
                if (unitSystem == UnitSystem.IMPERIAL)
                    stringResource(R.string.units_imperial_display)
                else
                    stringResource(R.string.units_metric_display),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showLanguageDialog) {
        val activity = context as? Activity
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Language", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    LocaleHelper.supportedLanguages.forEach { lang ->
                        val selected = lang.tag == currentLanguageTag
                        Row(
                            modifier = Modifier.clickable {
                                if (!selected) {
                                    LocaleHelper.saveTag(context, lang.tag)
                                    currentLanguageTag = lang.tag
                                    showLanguageDialog = false
                                    activity?.recreate()
                                } else {
                                    showLanguageDialog = false
                                }
                            }
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    if (!selected) {
                                        LocaleHelper.saveTag(context, lang.tag)
                                        currentLanguageTag = lang.tag
                                        showLanguageDialog = false
                                        activity?.recreate()
                                    } else {
                                        showLanguageDialog = false
                                    }
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(lang.displayName, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLanguageDialog = false }) { Text("Cancel") }
            }
        )
    }
}
