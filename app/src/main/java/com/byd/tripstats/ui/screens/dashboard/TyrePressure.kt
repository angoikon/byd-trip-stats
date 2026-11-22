package com.byd.tripstats.ui.screens.dashboard

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.R
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.ui.theme.AccelerationOrange
import com.byd.tripstats.ui.theme.BydElectricBlue
import com.byd.tripstats.ui.theme.BydErrorRed
import com.byd.tripstats.ui.theme.RegenGreen
import com.byd.tripstats.ui.theme.isNeon

enum class TyrePressureUnit { BAR, PSI, KPA }

private fun Double.toDisplayPressure(unit: TyrePressureUnit): Double = when (unit) {
    TyrePressureUnit.BAR -> this / 14.5038
    TyrePressureUnit.PSI -> this
    TyrePressureUnit.KPA -> this * 6.89476
}

internal fun Double.toBarFromPsi(): Double = this / 14.5038

internal fun TyrePressureUnit.label(): String = when (this) {
    TyrePressureUnit.BAR -> "bar"
    TyrePressureUnit.PSI -> "psi"
    TyrePressureUnit.KPA -> "kPa"
}

internal fun TyrePressureUnit.formatValue(psi: Double): String {
    if (psi < 0.1) return "--"
    return when (this) {
        // 2 decimals for bar: 1 psi ≈ 0.069 bar, so "%.1f" collapses distinct wheels (42.0/43.5/43.0
        // psi = 2.90/3.00/2.96 bar) into a flat "2.9/3.0/3.0" and mis-rounds. "%.2f" preserves the
        // per-wheel spread and matches the cluster's psi granularity.
        TyrePressureUnit.BAR -> String.format("%.2f", psi.toDisplayPressure(this))
        TyrePressureUnit.PSI -> String.format("%.1f", psi.toDisplayPressure(this))
        TyrePressureUnit.KPA -> String.format("%.0f", psi.toDisplayPressure(this))
    }
}

enum class TyrePressureStatus { NO_DATA, LOW, NORMAL, HIGH }

fun tyrePressureStatus(pressurePsi: Double, recommendedBar: Double): TyrePressureStatus {
    if (pressurePsi < 0.1) return TyrePressureStatus.NO_DATA
    val pressureBar = pressurePsi.toBarFromPsi()
    return when {
        pressureBar < recommendedBar - 0.2 -> TyrePressureStatus.LOW
        pressureBar > recommendedBar + 0.2 -> TyrePressureStatus.HIGH
        else                               -> TyrePressureStatus.NORMAL
    }
}

@Composable
fun TyrePressureIndicator(
    pressure: Double,
    isFront: Boolean,
    tempC: Int? = null,
    unit: TyrePressureUnit = TyrePressureUnit.BAR,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context.applicationContext) }
    val selectedCar by prefs.selectedCarConfig.collectAsState(initial = null)

    val car = selectedCar ?: return

    val frontTyrePressureBar = car.frontTyrePressureBar
    val rearTyrePressureBar = car.rearTyrePressureBar

    val status = tyrePressureStatus(pressure, if (isFront) frontTyrePressureBar else rearTyrePressureBar)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = when (status) {
            TyrePressureStatus.NO_DATA -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
            TyrePressureStatus.LOW     -> AccelerationOrange.copy(alpha = 0.9f)
            TyrePressureStatus.HIGH    -> BydErrorRed.copy(alpha = 0.9f)
            TyrePressureStatus.NORMAL  -> RegenGreen.copy(alpha = 0.9f)
        }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = unit.formatValue(pressure),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 12.sp
            )
            if (tempC != null) {
                Text(
                    text = "${tempC}°C",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
internal fun TyreStatCard(
    telemetry: VehicleTelemetry,
    tyreUnit: TyrePressureUnit,
    compact: Boolean = false,
    /** Classic's grid form, as [StatCard]'s: the icon and the title in the accent colour on top. */
    stacked: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val pad = 8.dp
    val iconSize = if (compact) 22.dp else 26.dp
    val spacerW = 8.dp
    val cellSpacing = 4.dp

    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context.applicationContext) }
    val selectedCar by prefs.selectedCarConfig.collectAsState(initial = null)
    val frontBar = selectedCar?.frontTyrePressureBar
    val rearBar = selectedCar?.rearTyrePressureBar
    val neon = MaterialTheme.isNeon

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (neon) 1.5.dp else 1.dp,
                color = if (neon) BydElectricBlue.copy(alpha = 0.45f)
                else androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            ),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        // Centred like the other grid cards: the title, then two lines of two tyres.
        if (stacked) Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center
        ) {
            StackedCardHeader("${stringResource(R.string.stat_tyres)} · ${tyreUnit.label()}", BydElectricBlue, Icons.Filled.TireRepair)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StackedTyreCell("FL", telemetry.tyreTempLF.takeIf { it > 0 }, telemetry.tyrePressureLF, tyreUnit, frontBar, Modifier.weight(1f))
                StackedTyreCell("FR", telemetry.tyreTempRF.takeIf { it > 0 }, telemetry.tyrePressureRF, tyreUnit, frontBar, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StackedTyreCell("RL", telemetry.tyreTempLR.takeIf { it > 0 }, telemetry.tyrePressureLR, tyreUnit, rearBar, Modifier.weight(1f))
                StackedTyreCell("RR", telemetry.tyreTempRR.takeIf { it > 0 }, telemetry.tyrePressureRR, tyreUnit, rearBar, Modifier.weight(1f))
            }
        } else Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.TireRepair,
                contentDescription = null,
                tint = BydElectricBlue,
                modifier = Modifier.size(iconSize)
            )
            Spacer(modifier = Modifier.width(spacerW))
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(cellSpacing)
                ) {
                    TyreCell("FL", telemetry.tyreTempLF.takeIf { it > 0 }, telemetry.tyrePressureLF, tyreUnit, frontBar, modifier = Modifier.weight(1f))
                    TyreCell("FR", telemetry.tyreTempRF.takeIf { it > 0 }, telemetry.tyrePressureRF, tyreUnit, frontBar, modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(cellSpacing))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(cellSpacing)
                ) {
                    TyreCell("RL", telemetry.tyreTempLR.takeIf { it > 0 }, telemetry.tyrePressureLR, tyreUnit, rearBar, modifier = Modifier.weight(1f))
                    TyreCell("RR", telemetry.tyreTempRR.takeIf { it > 0 }, telemetry.tyrePressureRR, tyreUnit, rearBar, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * One tyre in Classic's grid card: wheel, pressure, temperature on one line, sized for a glance
 * while driving. The pressure comes first and largest, coloured by its status (darkened on the light
 * theme, where the plain colours are too faint to read), and it is measured before the temperature,
 * so a narrow cell cuts the temperature, never the pressure. No box: at ~90 dp a cell has no room
 * for one. "FL 2.47 16°" at 13/18/15 sp is ~72 dp.
 */
@Composable
private fun StackedTyreCell(
    label: String,
    tempC: Int?,
    pressurePsi: Double,
    unit: TyrePressureUnit,
    recommendedBar: Double?,
    modifier: Modifier = Modifier,
) {
    val pressureColor = when (recommendedBar?.let { tyrePressureStatus(pressurePsi, it) }) {
        TyrePressureStatus.LOW    -> accentTextColor(AccelerationOrange)
        TyrePressureStatus.HIGH   -> accentTextColor(BydErrorRed)
        TyrePressureStatus.NORMAL -> accentTextColor(RegenGreen)
        else                      -> MaterialTheme.colorScheme.onSurface
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = unit.formatValue(pressurePsi),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = pressureColor,
            maxLines = 1,
            softWrap = false
        )
        Spacer(modifier = Modifier.weight(1f))
        if (tempC != null) {
            Text(
                text = "${tempC}°",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
internal fun TyreCell(
    label: String,
    tempC: Int?,
    pressurePsi: Double,
    unit: TyrePressureUnit,
    recommendedBar: Double?,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    compact: Boolean = false
) {
    val status = recommendedBar?.let { tyrePressureStatus(pressurePsi, it) }
    val pressureColor = when (status) {
        TyrePressureStatus.LOW    -> AccelerationOrange
        TyrePressureStatus.HIGH   -> BydErrorRed
        TyrePressureStatus.NORMAL -> RegenGreen
        else                      -> MaterialTheme.colorScheme.onSurface
    }
    val isAlarm = status == TyrePressureStatus.LOW || status == TyrePressureStatus.HIGH

    val labelSize = if (large) (if (compact) 12.sp else 14.sp) else 10.sp
    val tempSize = if (large) (if (compact) 14.sp else 16.sp) else 11.sp
    val pressureSize = if (large) (if (compact) 13.sp else 16.sp) else 10.sp

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (large) (if (compact) 6.dp else 10.dp) else 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                fontSize = labelSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            if (tempC != null) {
                Text(
                    text = "${tempC}°",
                    fontSize = tempSize,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
            Text(
                text = unit.formatValue(pressurePsi),
                fontSize = pressureSize,
                fontWeight = if (isAlarm) FontWeight.Bold else FontWeight.Normal,
                color = pressureColor,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
