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
import com.byd.tripstats.data.preferences.DEFAULT_CAR_OFF_TIMEOUT_MINUTES
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.convertDistance
import com.byd.tripstats.data.preferences.distanceUnit
import com.byd.tripstats.data.preferences.toKilometers
import com.byd.tripstats.ui.theme.BydElectricAzure
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.launch

/**
 * Preferences → Trip recording: when a trip ends after the car is switched off (and whether to
 * ask first), and the minimum distance a trip needs to be kept.
 */
@Composable
internal fun TripRecordingSection(
    viewModel: DashboardViewModel,
    preferencesManager: PreferencesManager
) {
    val scope = rememberCoroutineScope()
    val carOffTimeoutMinutes by preferencesManager.carOffTimeoutMinutes.collectAsState(
        initial = preferencesManager.getCachedCarOffTimeoutMinutes()
    )
    val confirmBeforeAutoStop by preferencesManager.confirmBeforeAutoStop.collectAsState(
        initial = preferencesManager.getCachedConfirmBeforeAutoStop()
    )
    val minTripDistanceKm by preferencesManager.minTripDistanceKm.collectAsState(
        initial = preferencesManager.getCachedMinTripDistanceKm()
    )
    val unitSystem by viewModel.unitSystem.collectAsState()
    var showCarOffTimeoutDialog by remember { mutableStateOf(false) }
    var showMinTripDistanceDialog by remember { mutableStateOf(false) }

    SectionHeader(icon = Icons.Filled.Timer, title = stringResource(R.string.pref_trip_recording_title))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.pref_engine_off_timeout),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.engine_off_timeout_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.current_timeout_value, carOffTimeoutMinutes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = { showCarOffTimeoutDialog = true }) {
                Icon(Icons.Filled.Timer, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.change_timeout_action))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        stringResource(R.string.pref_auto_stop_prompt),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.auto_stop_prompt_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = confirmBeforeAutoStop,
                    onCheckedChange = { enabled ->
                        scope.launch { preferencesManager.saveConfirmBeforeAutoStop(enabled) }
                    }
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
            Text(
                stringResource(R.string.pref_min_trip_distance),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.min_trip_distance_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.min_trip_distance_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (minTripDistanceKm > 0.0) {
                    stringResource(
                        R.string.current_minimum_value,
                        "%.2f".format(unitSystem.convertDistance(minTripDistanceKm)),
                        unitSystem.distanceUnit
                    )
                } else {
                    stringResource(R.string.current_minimum_disabled)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = { showMinTripDistanceDialog = true }) {
                Icon(Icons.Filled.Straighten, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (minTripDistanceKm > 0.0) stringResource(R.string.change_minimum_action) else stringResource(R.string.set_minimum_action))
            }
        }
    }

    if (showCarOffTimeoutDialog) {
        var minutesInput by remember(carOffTimeoutMinutes) {
            mutableStateOf(carOffTimeoutMinutes.toString())
        }
        AlertDialog(
            onDismissRequest = { showCarOffTimeoutDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.timeout_dialog_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.timeout_input_desc, DEFAULT_CAR_OFF_TIMEOUT_MINUTES),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = minutesInput,
                        onValueChange = { minutesInput = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.minutes_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val mins = minutesInput.toIntOrNull()?.coerceAtLeast(1)
                            ?: DEFAULT_CAR_OFF_TIMEOUT_MINUTES
                        scope.launch { preferencesManager.saveCarOffTimeoutMinutes(mins) }
                        showCarOffTimeoutDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BydElectricAzure)
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showCarOffTimeoutDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showMinTripDistanceDialog) {
        // Edit value in the user's display unit so the number they type matches
        // the number shown on the card. Convert back to km for storage.
        val initialDisplay = if (minTripDistanceKm > 0.0) {
            "%.2f".format(unitSystem.convertDistance(minTripDistanceKm))
        } else ""
        var distanceInput by remember(minTripDistanceKm, unitSystem) {
            mutableStateOf(initialDisplay)
        }
        AlertDialog(
            onDismissRequest = { showMinTripDistanceDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.min_distance_dialog_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.min_distance_input_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = distanceInput,
                        onValueChange = { distanceInput = it },
                        label = { Text(stringResource(R.string.distance_unit_label, unitSystem.distanceUnit)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val displayValue = distanceInput.replace(',', '.').toDoubleOrNull() ?: 0.0
                        val km = if (displayValue <= 0.0) 0.0 else unitSystem.toKilometers(displayValue)
                        scope.launch { preferencesManager.saveMinTripDistanceKm(km) }
                        showMinTripDistanceDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BydElectricAzure)
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showMinTripDistanceDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
