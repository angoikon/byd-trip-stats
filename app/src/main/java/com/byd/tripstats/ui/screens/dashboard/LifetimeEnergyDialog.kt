package com.byd.tripstats.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.consumptionUnit
import com.byd.tripstats.data.preferences.convertEfficiency
import com.byd.tripstats.data.preferences.distanceUnit
import com.byd.tripstats.ui.theme.AccelerationOrange
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What the Total Discharge card counts, opened by a tap on it. The car's lifetime counter divided
 * by its odometer reads above a driving-only consumption (the BYD app's, the trips'), because the
 * counter also takes in the energy used between drives — that difference confused the owner of
 * the dev Seal (20.4 vs the BYD app's 19.0), so the dialog shows both figures and says why.
 * Laid out like the dashboard's other AlertDialogs (surfaceVariant, card icon, Close).
 */
@Composable
internal fun LifetimeEnergyDialog(
    telemetry: VehicleTelemetry,
    recordedTrips: DashboardViewModel.RecordedTripsAverage?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context.applicationContext) }
    val unitSystem by prefs.unitSystem.collectAsState(initial = prefs.getCachedUnitSystem())
    fun consumption(kwhPer100km: Double) =
        "%.1f %s".format(unitSystem.convertEfficiency(kwhPer100km), unitSystem.consumptionUnit)

    val lifetimeAverage = if (telemetry.totalDischarge > 0.0 && telemetry.odometer > 0.0)
        telemetry.totalDischarge / telemetry.odometer * 100.0 else null
    val since = recordedTrips?.let {
        Instant.ofEpochMilli(it.sinceMs).atZone(ZoneId.systemDefault()).toLocalDate()
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        icon = {
            Icon(
                Icons.Filled.ElectricalServices,
                contentDescription = null,
                tint = AccelerationOrange,
                modifier = Modifier.size(32.dp)
            )
        },
        title = { Text(stringResource(R.string.discharge_subtitle_lifetime), fontWeight = FontWeight.Bold) },
        text = {
            // Scrolls in split-screen, where the explanation may not fit.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FigureRow(stringResource(R.string.stat_total_discharge), "%.1f kWh".format(telemetry.totalDischarge))
                // As the Odometer card shows it.
                FigureRow(stringResource(R.string.stat_odometer), "%.1f %s".format(telemetry.odometer, unitSystem.distanceUnit))
                lifetimeAverage?.let { FigureRow(stringResource(R.string.lifetime_avg_label), consumption(it), strong = true) }
                if (recordedTrips != null && since != null) {
                    FigureRow(stringResource(R.string.trips_avg_since_label, since), consumption(recordedTrips.kwhPer100km), strong = true)
                }
                telemetry.totalElecConPHM?.let { FigureRow(stringResource(R.string.car_avg_label), consumption(it)) }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                )
                Text(
                    stringResource(R.string.discharge_info_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

@Composable
private fun FigureRow(label: String, value: String, strong: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
