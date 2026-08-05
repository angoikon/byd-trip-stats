package com.byd.tripstats.ui.screens.chargingdetail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.data.local.entity.ChargingDataPointEntity
import com.byd.tripstats.data.local.entity.ChargingSessionEntity
import com.byd.tripstats.data.preferences.SocSource
import com.byd.tripstats.data.preferences.UnitSystem
import com.byd.tripstats.data.preferences.convertDistance
import com.byd.tripstats.data.preferences.distanceUnit
import com.byd.tripstats.ui.theme.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// Shared model types for the charging detail sub-package
internal data class ChargingChartPoint(
    val timestamp: Long,
    val soc: Double,
    val socPanel: Double = 0.0,
    val chargingPowerKw: Double,
    val batteryTotalVoltageV: Double,
    val batteryTempAvgC: Double,
    val batteryCellTempMinC: Double,
    val batteryCellTempMaxC: Double,
    val batteryCellVoltageMinV: Double = 0.0,
    val batteryCellVoltageMaxV: Double = 0.0,
)

internal data class ChargingPowerSummary(
    val peakKw: Double,
    val avgKw: Double
)

internal enum class ChargingXAxisMode {
    TIME,
    SOC
}

private val chargingDetailJson = Json { ignoreUnknownKeys = true }

@Composable
internal fun ChargingOverviewTab(
    session     : ChargingSessionEntity,
    dataPoints  : List<ChargingChartPoint>,
    powerSummary: ChargingPowerSummary?,
    socSource   : SocSource = SocSource.PANEL,
    distanceSinceLastCharge: Double? = null,
    defaultTariff: Double = 0.0,
    currencySymbol: String = "€",
    unitSystem   : UnitSystem = UnitSystem.METRIC,
    onSavePrice  : (Double?) -> Unit = {},
) {
    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault()) }
    val displayPeakKw = powerSummary?.peakKw?.takeIf { it > 0.0 } ?: session.peakKw
    val displayAvgKw = powerSummary?.avgKw?.takeIf { it > 0.0 } ?: session.avgKw
    val displayTempStart = dataPoints.firstNotNullOfOrNull { it.overviewTemperatureC() }
        ?: session.batteryTempStart.takeIf { it > 0.0 }
    val displayTempEnd = dataPoints.asReversed().firstNotNullOfOrNull { it.overviewTemperatureC() }
        ?: session.batteryTempEnd?.takeIf { it > 0.0 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OverviewRow(stringResource(R.string.started_label),  dateFmt.format(Date(session.startTime)))
                session.endTime?.let {
                    OverviewRow(stringResource(R.string.ended_label),  dateFmt.format(Date(it)))
                }
                session.durationSeconds?.let {
                    OverviewRow(stringResource(R.string.stat_duration), formatDurationLong(it))
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.section_energy), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val usePanelSoc = socSource == SocSource.PANEL && session.socStartPanel > 0.0
                val displaySocStart = if (usePanelSoc) session.socStartPanel else session.socStart
                val displaySocEnd   = if (usePanelSoc) session.socEndPanel else session.socEnd
                OverviewRow(stringResource(R.string.soc_start_label), "%.1f%%".format(displaySocStart))
                displaySocEnd?.let {
                    OverviewRow(stringResource(R.string.soc_end_label),  "%.1f%%".format(it))
                    OverviewRow(stringResource(R.string.soc_added_label),"%.1f%%".format(it - displaySocStart))
                }
                session.kwhAdded?.let {
                    OverviewRow(stringResource(R.string.kwh_added_label), "%.2f kWh".format(it), valueColor = RegenGreen)
                }
                OverviewRow(stringResource(R.string.battery_car_label), "%.1f kWh".format(session.batteryKwh))
                distanceSinceLastCharge?.takeIf { it >= 0.0 }?.let { km ->
                    OverviewRow(
                        stringResource(R.string.distance_since_last_charge_label),
                        "%.1f %s".format(unitSystem.convertDistance(km), unitSystem.distanceUnit),
                        valueColor = BatteryBlue
                    )
                }
            }
        }

        // ── Cost ───────────────────────────────────────────────────────────────
        // Editable per-charge price (currency/kWh). Explicit price = real money paid;
        // otherwise the global tariff is applied as an estimate. 0.00 = free charge.
        ChargingCostCard(
            session        = session,
            defaultTariff  = defaultTariff,
            currencySymbol = currencySymbol,
            onSavePrice    = onSavePrice
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tab_power), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (displayPeakKw > 0.0) {
                    OverviewRow(stringResource(R.string.peak_power_label), "%.1f kW".format(displayPeakKw), valueColor = AccelerationOrange)
                }
                if (displayAvgKw > 0.0) {
                    OverviewRow(stringResource(R.string.average_power_label), "%.1f kW".format(displayAvgKw))
                }
                session.durationSeconds?.let { secs ->
                    if (session.kwhAdded != null && secs > 0) {
                        val rate = session.kwhAdded / (secs / 3600.0)
                        OverviewRow(stringResource(R.string.charge_rate_label), "%.1f kW (avg)".format(rate))
                    }
                }
            }
        }

        if (displayTempStart != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.battery_temperature_section), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OverviewRow(stringResource(R.string.at_start_label), "%.1f °C".format(displayTempStart))
                    displayTempEnd?.let {
                        OverviewRow(stringResource(R.string.at_end_label),   "%.1f °C".format(it))
                        val delta = it - displayTempStart
                        val sign  = if (delta >= 0) "+" else ""
                        OverviewRow(stringResource(R.string.rise_label),     "$sign%.1f °C".format(delta),
                            valueColor = if (delta > 10) BydErrorRed else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }

        if (dataPoints.isNotEmpty()) {
            Text(
                stringResource(R.string.telemetry_points_label, dataPoints.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun ChargingCostCard(
    session       : ChargingSessionEntity,
    defaultTariff : Double,
    currencySymbol: String,
    onSavePrice   : (Double?) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val kwh = session.kwhAdded ?: 0.0
    val explicitRate = session.pricePerKwh                  // null = tariff, 0.0 = free
    val effectiveRate = explicitRate ?: defaultTariff.takeIf { it > 0.0 }
    val cost = effectiveRate?.let { kwh * it }

    val rateText = when {
        explicitRate != null && explicitRate <= 0.0 -> stringResource(R.string.free_label)
        explicitRate != null -> "$currencySymbol%.3f / kWh".format(explicitRate)
        defaultTariff > 0.0 -> stringResource(R.string.using_tariff_rate, "$currencySymbol%.3f".format(defaultTariff))
        else -> stringResource(R.string.set_price_action)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.section_cost), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = { showDialog = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Edit, stringResource(R.string.set_price_action), modifier = Modifier.size(18.dp))
                }
            }
            OverviewRow(stringResource(R.string.charge_price_label), rateText)
            if (cost != null) {
                OverviewRow(
                    stringResource(R.string.charge_cost_label),
                    if (cost <= 0.0) stringResource(R.string.free_label)
                    else "$currencySymbol%.2f".format(cost),
                    valueColor = if (cost <= 0.0) RegenGreen else AccelerationOrange
                )
            }
            if (explicitRate == null) {
                Text(
                    stringResource(R.string.charge_price_estimate_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showDialog) {
        ChargingPriceDialog(
            kwhAdded       = kwh,
            currentRate    = explicitRate,
            defaultTariff  = defaultTariff,
            currencySymbol = currencySymbol,
            onDismiss      = { showDialog = false },
            onSave         = { rate -> onSavePrice(rate); showDialog = false }
        )
    }
}

/**
 * Price editor for a charge. Accepts EITHER a €/kWh rate OR the total paid (converted to a
 * rate via kWh added), whichever the user has to hand at a public charger. 0 = free.
 */
@Composable
private fun ChargingPriceDialog(
    kwhAdded      : Double,
    currentRate   : Double?,
    defaultTariff : Double,
    currencySymbol: String,
    onDismiss     : () -> Unit,
    onSave        : (Double?) -> Unit,
) {
    // false = enter €/kWh rate; true = enter total paid.
    var enterTotal by remember { mutableStateOf(false) }
    // Prefill: the explicit price if set, otherwise the global tariff so the user sees the
    // default that's already being applied and can accept or adjust it (rather than a blank field).
    var input by remember {
        mutableStateOf((currentRate ?: defaultTariff.takeIf { it > 0.0 })?.let { "%.3f".format(it) } ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.charge_price_dialog_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.charge_price_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Rate vs total toggle
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = !enterTotal,
                        onClick = { enterTotal = false },
                        label = { Text(stringResource(R.string.price_mode_rate)) }
                    )
                    FilterChip(
                        selected = enterTotal,
                        onClick = { enterTotal = true },
                        label = { Text(stringResource(R.string.price_mode_total)) },
                        enabled = kwhAdded > 0.0
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = {
                        Text(
                            if (enterTotal) stringResource(R.string.price_total_label)
                            else stringResource(R.string.price_per_kwh_label)
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    prefix = { Text(currencySymbol) }
                )
                Text(
                    stringResource(R.string.charge_price_free_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = input.replace(',', '.').toDoubleOrNull()
                val rate = when {
                    parsed == null -> null
                    parsed < 0.0 -> null
                    enterTotal && kwhAdded > 0.0 -> parsed / kwhAdded  // total → €/kWh
                    enterTotal -> null
                    else -> parsed
                }
                onSave(rate)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (currentRate != null) {
                    TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.clear)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}

@Composable
private fun OverviewRow(
    label     : String,
    value     : String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium, color = valueColor)
    }
}

@Composable
internal fun ActiveChargingPowerTab(latestKw: Double?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.charging_chart_charge_power),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                latestKw?.takeIf { it > 0.1 }?.let {
                    Text(
                        stringResource(R.string.current_power_estimate, it),
                        style = MaterialTheme.typography.headlineSmall,
                        color = AccelerationOrange,
                        fontWeight = FontWeight.Bold
                    )
                }
                latestKw?.takeIf { it > 0.1 }?.let {
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.live_chart_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

internal fun buildChargingPowerSummary(
    session: ChargingSessionEntity,
    dataPoints: List<ChargingChartPoint>
): ChargingPowerSummary {
    if (dataPoints.isEmpty()) {
        return ChargingPowerSummary(peakKw = session.peakKw, avgKw = session.avgKw)
    }
    val positivePowers = dataPoints.map { it.chargingPowerKw }.filter { it > 0.1 }
    val displayPeakKw = maxOf(session.peakKw, positivePowers.maxOrNull() ?: 0.0)
    val displayAvgKw = positivePowers.average().takeIf { positivePowers.isNotEmpty() } ?: session.avgKw
    return ChargingPowerSummary(peakKw = displayPeakKw, avgKw = displayAvgKw)
}

internal fun ChargingDataPointEntity.toBaseChartPoint(): ChargingChartPoint {
    val needsRawJson = chargingPower <= 0.0
        || batteryTotalVoltage <= 0
        || (batteryCellTempMin <= 0 && batteryCellTempMax <= 0 && batteryTempAvg <= 0.0)
    val raw = if (needsRawJson) rawJson.toJsonObjectOrNull() else null
    val rawChargingPower = raw?.doubleOrNull("charging_power")
    val rawVoltage = raw?.doubleOrNull("battery_total_voltage")
    val rawCellTempAvg = raw?.doubleOrNull("statistic_cell_temp_avg")
    val rawBatteryCellTempMin = raw?.doubleOrNull("battery_cell_temp_min")
    val rawBatteryCellTempMax = raw?.doubleOrNull("battery_cell_temp_max")
    val rawStatisticCellTempMin = raw?.doubleOrNull("statistic_cell_temp_min")
    val rawStatisticCellTempMax = raw?.doubleOrNull("statistic_cell_temp_max")
    val rawPackTemp = raw?.doubleOrNull("battery_pack_temp")

    val effectiveVoltage = when {
        batteryTotalVoltage > 0 -> batteryTotalVoltage.toDouble()
        rawVoltage != null && rawVoltage > 0.0 -> rawVoltage
        else -> 0.0
    }

    val effectiveChargingPower = when {
        chargingPower > 0.0 -> chargingPower
        rawChargingPower != null && rawChargingPower > 0.0 -> rawChargingPower
        else -> 0.0
    }

    val effectiveCellTempMin = when {
        batteryCellTempMin > 0 -> batteryCellTempMin.toDouble()
        rawBatteryCellTempMin != null && rawBatteryCellTempMin > 0.0 -> rawBatteryCellTempMin
        rawStatisticCellTempMin != null && rawStatisticCellTempMin > 0.0 -> rawStatisticCellTempMin
        else -> 0.0
    }

    val effectiveCellTempMax = when {
        batteryCellTempMax > 0 -> batteryCellTempMax.toDouble()
        rawBatteryCellTempMax != null && rawBatteryCellTempMax > 0.0 -> rawBatteryCellTempMax
        rawStatisticCellTempMax != null && rawStatisticCellTempMax > 0.0 -> rawStatisticCellTempMax
        else -> 0.0
    }

    val effectiveTempAvg = when {
        effectiveCellTempMin > 0.0 && effectiveCellTempMax > 0.0 ->
            (effectiveCellTempMin + effectiveCellTempMax) / 2.0
        effectiveCellTempMin > 0.0 -> effectiveCellTempMin
        effectiveCellTempMax > 0.0 -> effectiveCellTempMax
        batteryTempAvg > 0.0 -> batteryTempAvg
        rawCellTempAvg != null && rawCellTempAvg > 0.0 -> rawCellTempAvg
        rawPackTemp != null && rawPackTemp > 0.0 -> rawPackTemp
        else -> 0.0
    }

    return ChargingChartPoint(
        timestamp            = timestamp,
        soc                  = soc,
        socPanel             = socPanel.toDouble(),
        chargingPowerKw      = effectiveChargingPower,
        batteryTotalVoltageV = effectiveVoltage,
        batteryTempAvgC      = effectiveTempAvg,
        batteryCellTempMinC  = effectiveCellTempMin,
        batteryCellTempMaxC  = effectiveCellTempMax,
        batteryCellVoltageMinV = batteryCellVoltageMin,
        batteryCellVoltageMaxV = batteryCellVoltageMax
    )
}

private fun String.toJsonObjectOrNull(): JsonObject? {
    if (isBlank() || this == "{}") return null
    return runCatching { chargingDetailJson.parseToJsonElement(this).jsonObject }.getOrNull()
}

private fun JsonObject.doubleOrNull(key: String): Double? =
    this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()

private fun ChargingChartPoint.overviewTemperatureC(): Double? = when {
    batteryCellTempMinC > 0.0 || batteryCellTempMaxC > 0.0 -> {
        val samples = listOf(batteryCellTempMinC, batteryCellTempMaxC).filter { it > 0.0 }
        if (samples.isEmpty()) null else samples.average()
    }
    batteryTempAvgC > 0.0 -> batteryTempAvgC
    else -> null
}

internal fun formatDurationLong(seconds: Long): String {
    val h = TimeUnit.SECONDS.toHours(seconds)
    val m = TimeUnit.SECONDS.toMinutes(seconds) % 60
    val s = seconds % 60
    return if (h > 0) "${h}h ${m}min ${s}s" else "${m}min ${s}s"
}

internal fun niceStep(range: Double): Double = when {
    range <= 0.1  -> 0.02
    range <= 0.25 -> 0.05
    range <= 0.5  -> 0.1
    range <= 1.0  -> 0.2
    range <= 5    -> 1.0
    range <= 20   -> 5.0
    range <= 50   -> 10.0
    range <= 200  -> 25.0
    range <= 500  -> 50.0
    else          -> 100.0
}

/**
 * Format an axis tick with just enough decimals for the step, so a narrow range (e.g. charge power
 * pinned at 6.1–6.2 kW → step 0.02) shows "6.10, 6.12, …" instead of every tick collapsing to "6".
 */
internal fun axisLabel(value: Double, step: Double): String {
    val decimals = when {
        step >= 1.0  -> 0
        step >= 0.1  -> 1
        step >= 0.01 -> 2
        else         -> 3
    }
    return "%.${decimals}f".format(value)
}
