package com.byd.tripstats.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.data.entitlement.EntitlementManager
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.SocSource
import com.byd.tripstats.ui.components.BrandSwitch
import com.byd.tripstats.ui.theme.BydElectricAzure
import kotlinx.coroutines.launch

/**
 * Preferences → Battery: which state-of-charge reading the app shows, and the cell-imbalance
 * alert.
 *
 * The alert used to live on the Pro tab, where it was the only actual setting among cards that
 * just describe Pro features. It is a battery setting, so it sits here now and is locked the way
 * the Pro dashboard layout and Neon theme already are on Appearance: shown to everyone, with the
 * lock leading to the Pro tab.
 */
@Composable
internal fun BatterySection(
    preferencesManager: PreferencesManager,
    onNavigateToProTab: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val socSource by preferencesManager.socSource.collectAsState(
        initial = preferencesManager.getCachedSocSource()
    )
    val isPro by EntitlementManager.isPro.collectAsState()
    val cellImbalanceAlertEnabled by preferencesManager.cellImbalanceAlertEnabled.collectAsState(
        initial = preferencesManager.getCachedCellImbalanceAlertEnabled()
    )
    val cellImbalanceThresholdV by preferencesManager.cellImbalanceThresholdV.collectAsState(
        initial = preferencesManager.getCachedCellImbalanceThresholdV()
    )
    var showCellImbalanceThresholdDialog by remember { mutableStateOf(false) }

    SectionHeader(icon = Icons.Filled.BatteryChargingFull, title = stringResource(R.string.pref_battery_title))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.pref_soc_source),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.soc_source_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    SocSource.PANEL to stringResource(R.string.soc_panel_option),
                    SocSource.BMS   to stringResource(R.string.soc_bms_option),
                ).forEach { (source, label) ->
                    Button(
                        onClick = { scope.launch { preferencesManager.saveSocSource(source) } },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (socSource == source)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (socSource == source)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text(label, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    stringResource(R.string.soc_source_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.pref_cell_imbalance),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (!isPro) {
                            Spacer(Modifier.width(8.dp))
                            ProBadge()
                        }
                    }
                    Text(
                        stringResource(R.string.cell_imbalance_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isPro) {
                    BrandSwitch(
                        checked = cellImbalanceAlertEnabled,
                        onCheckedChange = {
                            scope.launch { preferencesManager.saveCellImbalanceAlertEnabled(it) }
                        },
                    )
                } else {
                    // Locked — tapping the lock opens the Pro tab, as on Appearance.
                    IconButton(onClick = onNavigateToProTab) {
                        Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.unlock_pro_action))
                    }
                }
            }
            Text(
                stringResource(R.string.cell_imbalance_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isPro && cellImbalanceAlertEnabled) {
                Text(
                    stringResource(R.string.current_limit_value, "%.0f".format(cellImbalanceThresholdV * 1000)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = { showCellImbalanceThresholdDialog = true }) {
                    Icon(Icons.Filled.BatteryAlert, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.change_limit_action))
                }
            } else if (!isPro) {
                Text(
                    stringResource(R.string.pro_feature_imbalance_msg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onNavigateToProTab) {
                    Icon(Icons.Filled.Lock, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.unlock_pro_action))
                }
            }
        }
    }

    if (showCellImbalanceThresholdDialog) {
        var thresholdInput by remember(cellImbalanceThresholdV) {
            mutableStateOf("%.0f".format(cellImbalanceThresholdV * 1000))
        }
        AlertDialog(
            onDismissRequest = { showCellImbalanceThresholdDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.imbalance_dialog_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.imbalance_input_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = thresholdInput,
                        onValueChange = { thresholdInput = it },
                        label = { Text(stringResource(R.string.limit_mv_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val mv = thresholdInput.replace(',', '.').toDoubleOrNull()
                        if (mv != null) {
                            scope.launch { preferencesManager.saveCellImbalanceThresholdV(mv / 1000.0) }
                        }
                        showCellImbalanceThresholdDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BydElectricAzure)
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showCellImbalanceThresholdDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
