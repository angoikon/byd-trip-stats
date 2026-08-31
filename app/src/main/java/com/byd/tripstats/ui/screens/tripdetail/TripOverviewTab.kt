package com.byd.tripstats.ui.screens.tripdetail

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.data.analysis.PhevTripAnalysis
import com.byd.tripstats.data.analysis.TripReport
import com.byd.tripstats.data.analysis.calculateTripEnergyBreakdown
import com.byd.tripstats.data.config.CarConfig
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.local.entity.TripStatsEntity
import com.byd.tripstats.data.preferences.SocSource
import com.byd.tripstats.data.preferences.UnitSystem
import com.byd.tripstats.data.preferences.consumptionUnit
import com.byd.tripstats.data.preferences.convertDistance
import com.byd.tripstats.data.preferences.convertEfficiency
import com.byd.tripstats.data.preferences.distanceUnit
import com.byd.tripstats.data.preferences.speedUnit
import com.byd.tripstats.ui.screens.DetailRow
import com.byd.tripstats.ui.theme.*
import kotlin.math.abs

@Composable
fun TripOverviewTab(
    trip: TripEntity,
    stats: TripStatsEntity?,
    dataPoints: List<TripDataPointEntity>,
    selectedCarConfig: CarConfig?,
    regenEfficiencyPct: Double?,
    currencySymbol: String = "€",
    unitSystem: UnitSystem = UnitSystem.METRIC,
    socSource: SocSource = SocSource.PANEL,
    /** FIFO "battery cost-basis" rate for this trip (currency/kWh), from
     *  DashboardViewModel.tripBlendedRates. null when no rate is resolvable. */
    blendedRate: Double? = null
) {
    val energyBreakdown = remember(dataPoints, selectedCarConfig, trip.energyConsumed) {
        calculateTripEnergyBreakdown(
            dataPoints = dataPoints,
            carConfig = selectedCarConfig,
            totalEnergyConsumedKwh = trip.energyConsumed
        )
    }
    val phevBreakdown = remember(dataPoints, selectedCarConfig, trip.energyConsumed) {
        if (selectedCarConfig?.isPhev == true) {
            PhevTripAnalysis.analyze(dataPoints, trip.energyConsumed)
        } else null
    }
    // Electricity rate for this trip: the FIFO "battery cost-basis" rate for the energy this
    // trip drew (computed once in the ViewModel and passed in), so the detail matches the
    // history list exactly. It already folds in any charge that happened during the trip.
    // Read-only by design — prices are edited on charging sessions and the global tariff,
    // and flow through to trips (single source of truth).
    val effectiveRate = blendedRate

    val tripCost = remember(trip, effectiveRate) {
        effectiveRate?.let { rate -> trip.energyConsumed?.let { it * rate } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Key metrics cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MetricCard(
                title = stringResource(R.string.stat_distance),
                value = String.format("%.1f", unitSystem.convertDistance(trip.distance ?: 0.0)),
                unit = unitSystem.distanceUnit,
                icon = Icons.Filled.Route,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .weight(1f)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                        shape = MaterialTheme.shapes.medium
                    )
            )

            MetricCard(
                title = stringResource(R.string.stat_duration),
                value = formatDuration(trip.duration ?: 0),
                unit = "",
                icon = Icons.Filled.Timer,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                        shape = MaterialTheme.shapes.medium
                    )
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MetricCard(
                title = stringResource(R.string.stat_energy_consumed),
                value = String.format("%.2f", trip.energyConsumed ?: 0.0),
                unit = "kWh",
                icon = Icons.Filled.BatteryChargingFull,
                color = AccelerationOrange,
                modifier = Modifier
                    .weight(1f)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                        shape = MaterialTheme.shapes.medium
                    )
            )

            MetricCard(
                title = stringResource(R.string.stat_avg_consumption),
                value = String.format("%.2f", unitSystem.convertEfficiency(trip.efficiency ?: 0.0)),
                unit = "kWh / 100${unitSystem.distanceUnit}",
                icon = Icons.Filled.Eco,
                color = RegenGreen,
                modifier = Modifier
                    .weight(1f)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                        shape = MaterialTheme.shapes.medium
                    )
            )
        }

        // Detailed stats
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                    shape = MaterialTheme.shapes.medium
                ),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.trip_statistics_section),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                DetailRow(stringResource(R.string.start_time_label), formatTimestamp(trip.startTime))
                DetailRow(stringResource(R.string.end_time_label), trip.endTime?.let { formatTimestamp(it) } ?: stringResource(R.string.in_progress_label))
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = (MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)))

                DetailRow(stringResource(R.string.initial_mileage_label), "${String.format("%.1f", trip.startOdometer)} ${unitSystem.distanceUnit}")
                DetailRow(stringResource(R.string.final_mileage_label), trip.endOdometer?.let { "${String.format("%.1f", it)} ${unitSystem.distanceUnit}" } ?: "-")
                DetailRow(stringResource(R.string.trip_distance_label), trip.distance?.let { "${String.format("%.1f", unitSystem.convertDistance(it))} ${unitSystem.distanceUnit}" } ?: "-")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = (MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)))

                if (socSource == SocSource.PANEL) {
                    DetailRow(stringResource(R.string.start_soc_panel), "${trip.startSocPanel.toInt()}%")
                    DetailRow(stringResource(R.string.end_soc_panel), trip.endSocPanel?.let { "${it.toInt()}%" } ?: "-")
                    DetailRow(stringResource(R.string.soc_change_panel), trip.socPanelDelta?.let { "${String.format("%.1f", it)}%" } ?: "-")
                } else {
                    DetailRow(stringResource(R.string.start_soc_bms), "${String.format("%.1f", trip.startSoc)}%")
                    DetailRow(stringResource(R.string.end_soc_bms), trip.endSoc?.let { "${String.format("%.1f", it)}%" } ?: "-")
                    DetailRow(stringResource(R.string.soc_change_bms), trip.socDelta?.let { "${String.format("%.1f", it)}%" } ?: "-")
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = (MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)))

                DetailRow(stringResource(R.string.stat_max_speed), "${trip.maxSpeed.toInt()} ${unitSystem.speedUnit}")
                DetailRow(stringResource(R.string.avg_speed_detail), stats?.avgSpeed?.toInt()?.toString()?.plus(" ${unitSystem.speedUnit}") ?: "-")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = (MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)))

                DetailRow(stringResource(R.string.max_power_label), "${trip.maxPower.toInt()} kW")
                DetailRow(stringResource(R.string.stat_max_regen), "${abs(trip.maxRegenPower).toInt()} kW")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = (MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)))

                DetailRow(stringResource(R.string.stat_energy_consumed), trip.energyConsumed?.let { String.format("%.2f kWh", it) } ?: "-")

                // Read-only per-trip electricity rate: the FIFO cost-basis rate of the energy
                // this trip drew (already folds in any mid-trip charge). Edit prices on the
                // charging sessions / global tariff — they flow through to trips.
                if (effectiveRate != null) {
                    DetailRow(
                        stringResource(R.string.energy_rate_label),
                        if (effectiveRate <= 0.0) stringResource(R.string.free_label)
                        else "${currencySymbol}${String.format("%.3f", effectiveRate)}/kWh"
                    )
                    DetailRow(
                        stringResource(R.string.trip_cost_label),
                        tripCost?.let {
                            if (it <= 0.0) stringResource(R.string.free_label)
                            else "${currencySymbol}${String.format("%.2f", it)}"
                        } ?: "-"
                    )
                }
                DetailRow(stringResource(R.string.energy_regenerated_label), stats?.totalRegenEnergy?.let { String.format("%.2f kWh", it) } ?: "-")
                DetailRow(stringResource(R.string.gross_energy_consumed_label), String.format("%.2f kWh", (trip.energyConsumed ?: 0.0) + (stats?.totalRegenEnergy ?: 0.0)))
                DetailRow(stringResource(R.string.regeneration_efficiency_label), regenEfficiencyPct?.let { String.format("%.2f%%", it) } ?: "-")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = (MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)))

                DetailRow(stringResource(R.string.battery_temp_range_label), TripReport.batteryTempRangeLabel(trip))
                DetailRow(stringResource(R.string.avg_battery_temp_label), TripReport.avgBatteryTempLabel(trip))
            }
        }

        // Hybrid breakdown — PHEV cars only, and only when the trip's points carry
        // the fuel/ICE counters (older PHEV history predates their recording).
        phevBreakdown?.let { phev ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                        shape = MaterialTheme.shapes.medium
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.phev_breakdown_section),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.phev_breakdown_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                    )

                    DetailRow(
                        stringResource(R.string.phev_ev_share_label),
                        String.format("%.0f%%", phev.evSharePct)
                    )
                    LinearProgressIndicator(
                        progress = { (phev.evSharePct / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        color = RegenGreen,
                        trackColor = AccelerationOrange.copy(alpha = 0.45f),
                    )
                    DetailRow(
                        stringResource(R.string.phev_ev_distance_label),
                        "${String.format("%.1f", unitSystem.convertDistance(phev.evKm))} ${unitSystem.distanceUnit}"
                    )
                    DetailRow(
                        stringResource(R.string.phev_ice_distance_label),
                        "${String.format("%.1f", unitSystem.convertDistance(phev.iceKm))} ${unitSystem.distanceUnit}"
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                    )

                    DetailRow(
                        stringResource(R.string.phev_fuel_used_label),
                        String.format("%.2f L", phev.fuelLiters)
                    )
                    DetailRow(
                        stringResource(R.string.phev_fuel_consumption_ice_label),
                        phev.fuelLPer100IceKm?.let {
                            // convertEfficiency is pure per-100-distance-unit scaling, so it
                            // applies to a fuel rate exactly as it does to a kWh rate.
                            "${String.format("%.1f", unitSystem.convertEfficiency(it))} L/100${unitSystem.distanceUnit}"
                        } ?: "-"
                    )
                    DetailRow(
                        stringResource(R.string.phev_fuel_consumption_combined_label),
                        phev.fuelLPer100TotalKm?.let {
                            "${String.format("%.1f", unitSystem.convertEfficiency(it))} L/100${unitSystem.distanceUnit}"
                        } ?: "-"
                    )
                    DetailRow(
                        stringResource(R.string.phev_ev_consumption_label),
                        phev.evKwhPer100EvKm?.let {
                            "${String.format("%.2f", unitSystem.convertEfficiency(it))} ${unitSystem.consumptionUnit}"
                        } ?: "-"
                    )
                }
            }
        }

        energyBreakdown?.let { breakdown ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                        shape = MaterialTheme.shapes.medium
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.energy_breakdown_section),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    val caveats = buildList {
                        if (!breakdown.hasPhysicsBreakdown) add("Rolling, aero, and gradient estimates require vehicle mass")
                        if (!breakdown.hasAeroEstimate) add("Aerodynamic estimate requires CdA for this model")
                    }
                    if (caveats.isNotEmpty()) {
                        Text(
                            text = caveats.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                    )

                    DetailRow(stringResource(R.string.stat_total_consumed), formatKwh(breakdown.totalConsumedKwh))

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                    )

                    val modelledRaw = breakdown.rollingResistanceKwh +
                        breakdown.aeroDragKwh +
                        breakdown.netGradientKwh.coerceAtLeast(0.0)
                    val scale = if (modelledRaw > breakdown.totalConsumedKwh && modelledRaw > 0.0)
                        breakdown.totalConsumedKwh / modelledRaw else 1.0
                    val rollingDisplay  = breakdown.rollingResistanceKwh * scale
                    val aeroDisplay     = breakdown.aeroDragKwh * scale
                    val climbDisplay    = breakdown.climbKwh * scale
                    val descentDisplay  = breakdown.descentKwh * scale
                    val netGradDisplay  = breakdown.netGradientKwh * scale
                    val auxDisplay      = (breakdown.totalConsumedKwh - modelledRaw * scale).coerceAtLeast(0.0)
                    fun pct(kwh: Double) = if (breakdown.totalConsumedKwh > 0.0)
                        String.format("%.1f", kwh / breakdown.totalConsumedKwh * 100.0) else "0.0"

                    DetailRow(
                        stringResource(R.string.breakdown_rolling),
                        if (breakdown.hasPhysicsBreakdown) {
                            "${formatKwh(rollingDisplay)} (${pct(rollingDisplay)}%)"
                        } else "n/a"
                    )

                    DetailRow(
                        stringResource(R.string.breakdown_aero),
                        if (breakdown.hasAeroEstimate) {
                            "${formatKwh(aeroDisplay)} (${pct(aeroDisplay)}%)"
                        } else "n/a"
                    )

                    DetailRow(
                        stringResource(R.string.breakdown_climb),
                        if (breakdown.hasGradientEstimate) formatKwh(climbDisplay) else "n/a"
                    )
                    DetailRow(
                        stringResource(R.string.breakdown_descent),
                        if (breakdown.hasGradientEstimate) "−${formatKwh(descentDisplay)}" else "n/a"
                    )
                    DetailRow(
                        stringResource(R.string.breakdown_net_gradient),
                        if (breakdown.hasGradientEstimate) {
                            val signed = if (netGradDisplay >= 0.0) "+${formatKwh(netGradDisplay)}"
                                         else "−${formatKwh(-netGradDisplay)}"
                            "$signed (${pct(netGradDisplay.coerceAtLeast(0.0))}%)"
                        } else "n/a"
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                    )

                    DetailRow(
                        stringResource(R.string.breakdown_auxiliary),
                        if (breakdown.hasPhysicsBreakdown) {
                            "${formatKwh(auxDisplay)} (${pct(auxDisplay)}%)"
                        } else "n/a"
                    )
                    Text(
                        text = stringResource(R.string.breakdown_aux_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                    )

                    DetailRow(
                        stringResource(R.string.vehicle_mass_label),
                        breakdown.estimatedKerbMassKg?.let { "${it.toInt()} kg" } ?: "n/a"
                    )
                    DetailRow(
                        stringResource(R.string.cda_drag_area_label),
                        breakdown.cdA?.let { String.format("%.3f m²", it) } ?: "n/a"
                    )
                }
            }
        }
    }

}

private fun formatKwh(value: Double): String =
    String.format("%.2f kWh", value)

private fun formatSignedKwh(value: Double): String =
    if (value >= 0.0) "+${formatKwh(value)}" else "-${formatKwh(abs(value))}"

private fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) String.format("%dh %dmin", hours, minutes)
    else String.format("%dmin", minutes)
}

private fun formatTimestamp(timestamp: Long): String {
    val instant = java.time.Instant.ofEpochMilli(timestamp)
    val formatter = java.time.format.DateTimeFormatter
        .ofPattern("MMM dd, yyyy HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
    return formatter.format(instant)
}
