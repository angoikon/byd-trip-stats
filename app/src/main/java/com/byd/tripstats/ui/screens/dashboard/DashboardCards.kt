package com.byd.tripstats.ui.screens.dashboard

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.R
import com.byd.tripstats.data.config.Drivetrain
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.preferences.DashboardCardId
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.distanceUnit
import com.byd.tripstats.sdk.VehicleTelemetrySnapshot
import com.byd.tripstats.ui.components.energyModeLabel
import com.byd.tripstats.ui.theme.AccelerationOrange
import com.byd.tripstats.ui.theme.BatteryBlue
import com.byd.tripstats.ui.theme.BydEcoTealDim
import com.byd.tripstats.ui.theme.BydElectricBlue
import com.byd.tripstats.ui.theme.BydErrorRed
import com.byd.tripstats.ui.theme.RegenGreen
import com.byd.tripstats.ui.theme.isNeon

/**
 * When true, card value text is rendered smaller so it fits the narrow tiles of the
 * split (chart-visible) layout. Provided by the split grids; defaults to full-size.
 */
val LocalDashboardCardCompact = compositionLocalOf { false }

/**
 * When true, cards render a dense two-line form (icon + title, then value; no subtitle)
 * so a whole grid fits the height without scrolling. Provided by the split-screen reflow.
 */
val LocalDashboardCardDense = compositionLocalOf { false }

/** Localised title for a card id — used by the edit sheet where no telemetry is drawn. */
@Composable
fun dashboardCardTitle(id: DashboardCardId): String = when (id) {
    DashboardCardId.BATTERY     -> stringResource(R.string.stat_battery)
    DashboardCardId.ENVIRONMENT -> stringResource(R.string.stat_environment)
    DashboardCardId.HV_12V      -> stringResource(R.string.stat_hv_12v)
    DashboardCardId.MOTORS      -> stringResource(R.string.stat_front_rear_motors)
    DashboardCardId.DRIVING     -> stringResource(R.string.stat_driving_dynamics)
    DashboardCardId.TYRES       -> stringResource(R.string.stat_tyres)
    DashboardCardId.ODOMETER    -> stringResource(R.string.stat_odometer)
    DashboardCardId.DISCHARGE   -> stringResource(R.string.stat_total_discharge)
}

/**
 * Renders a customisable middle-section card for the CARDS layout, matching the
 * redesign: icon + title on the top row, the value below, and the subtitle pinned
 * to the bottom (a four-row column). The tyre card keeps its bespoke 2×2 layout.
 */
@Composable
fun DashboardStatCard(
    id: DashboardCardId,
    telemetry: VehicleTelemetry,
    vehicleSnapshot: VehicleTelemetrySnapshot?,
    modifier: Modifier = Modifier,
    tyreUnit: TyrePressureUnit = TyrePressureUnit.BAR,
    interactive: Boolean = true,
    onNavigateToBatteryDegradation: () -> Unit = {},
    onShowBattery12vHistory: () -> Unit = {},
    onShowTyreDialog: () -> Unit = {},
) {
    // In edit mode the tile is dragged/toggled, so its own tap targets are disabled.
    val batteryClick = onNavigateToBatteryDegradation.takeIf { interactive }
    val hvClick = onShowBattery12vHistory.takeIf { interactive }
    val tyreClick = if (interactive) onShowTyreDialog else {{}}
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context.applicationContext) }
    val selectedCar by prefs.selectedCarConfig.collectAsState(initial = null)
    val unitSystem by prefs.unitSystem.collectAsState(initial = prefs.getCachedUnitSystem())
    val distanceUnit = unitSystem.distanceUnit

    when (id) {
        DashboardCardId.BATTERY -> {
            val sohDisplay: String = run {
                val statSoh = vehicleSnapshot?.statisticBatterySoh ?: telemetry.statisticBatterySoh
                val pct = when {
                    statSoh != null && statSoh in 50.0..110.0 -> String.format("%.1f%%", statSoh)
                    telemetry.soh in 1..110 -> String.format("%.1f%%", telemetry.soh.toDouble())
                    else -> "—"
                }
                val src = when {
                    statSoh != null && statSoh in 50.0..110.0 -> "stat"
                    !telemetry.sohEstimated && telemetry.soh in 1..110 -> "BMS"
                    telemetry.soh in 1..110 -> "~est"
                    else -> ""
                }
                if (pct == "—") stringResource(R.string.soh_value, "—")
                else stringResource(R.string.soh_value, "$pct${if (src.isNotEmpty()) " ($src)" else ""}")
            }
            val batteryTempSubtitle: String? = run {
                val cellMin = telemetry.batteryCellTempMin.toDouble().takeIf { it > 0.0 }
                    ?: vehicleSnapshot?.statisticCellTempMin
                val cellMax = telemetry.batteryCellTempMax.toDouble().takeIf { it > 0.0 }
                    ?: vehicleSnapshot?.statisticCellTempMax
                val avg = if (cellMin != null && cellMax != null && cellMax >= cellMin && (cellMax - cellMin) <= 25) {
                    (cellMin + cellMax) / 2.0
                } else {
                    vehicleSnapshot?.statisticCellTempAvg
                }
                avg?.takeIf { it.isFinite() && it in -40.0..120.0 }
                    ?.let { stringResource(R.string.battery_temp_value, it.toInt()) }
            }
            CardTile(
                title = stringResource(R.string.stat_battery),
                value = sohDisplay,
                subtitle = batteryTempSubtitle,
                icon = Icons.Filled.BatteryChargingFull,
                color = BatteryBlue,
                modifier = modifier,
                onClick = batteryClick
            )
        }

        DashboardCardId.ENVIRONMENT -> CardTile(
            title = stringResource(R.string.stat_environment),
            value = run {
                val externalTemp = vehicleSnapshot?.instrumentOutCarTemperature
                    ?: telemetry.instrumentOutCarTemperature
                if (externalTemp != null) stringResource(R.string.ambient_temp_value, externalTemp)
                else stringResource(R.string.ambient_temp_value, "—")
            },
            subtitle = run {
                val pm25In = vehicleSnapshot?.pm25InCar
                val pm25Out = vehicleSnapshot?.pm25OutCar
                if (pm25In != null || pm25Out != null)
                    stringResource(R.string.pm25_value, pm25In ?: "—", pm25Out ?: "—")
                else null
            },
            icon = Icons.Filled.Public,
            color = RegenGreen,
            modifier = modifier
        )

        DashboardCardId.HV_12V -> CardTile(
            title = stringResource(R.string.stat_hv_12v),
            value = run {
                val cellMin = vehicleSnapshot?.statisticCellVoltageMin
                    ?: telemetry.statisticCellVoltageMin
                    ?: telemetry.batteryCellVoltageMin.takeIf { it > 0.0 }
                val cellMax = vehicleSnapshot?.statisticCellVoltageMax
                    ?: telemetry.statisticCellVoltageMax
                    ?: telemetry.batteryCellVoltageMax.takeIf { it > 0.0 }
                val cellV = cellMin ?: cellMax
                val cells = selectedCar?.cellCount ?: 0
                val packVoltage = vehicleSnapshot?.batteryTotalVoltage?.takeIf { it > 0 }
                    ?: telemetry.batteryTotalVoltage.takeIf { it > 0 }
                val hvVolts: Int? = when {
                    packVoltage != null -> packVoltage
                    cellV != null && cells > 0 -> (cellV * cells).toInt()
                    else -> null
                }
                val hvStr = if (hvVolts != null) "$hvVolts V" else "— V"
                val v12Str = if (telemetry.battery12vVoltage > 0.0) String.format("%.2f", telemetry.battery12vVoltage) + " V" else "— V"
                stringResource(R.string.hv_12v_pair, hvStr, v12Str)
            },
            subtitle = run {
                val cellMin = vehicleSnapshot?.statisticCellVoltageMin
                    ?: telemetry.statisticCellVoltageMin
                    ?: telemetry.batteryCellVoltageMin.takeIf { it > 0.0 }
                val cellMax = vehicleSnapshot?.statisticCellVoltageMax
                    ?: telemetry.statisticCellVoltageMax
                    ?: telemetry.batteryCellVoltageMax.takeIf { it > 0.0 }
                when {
                    cellMin != null && cellMax != null ->
                        stringResource(R.string.cells_range, String.format("%.3f", cellMin), String.format("%.3f", cellMax))
                    cellMin != null ->
                        stringResource(R.string.cells_min_only, String.format("%.3f", cellMin))
                    cellMax != null ->
                        stringResource(R.string.cells_min_only, String.format("%.3f", cellMax))
                    else -> stringResource(R.string.cells_awaiting)
                }
            },
            icon = Icons.Filled.Bolt,
            color = BydEcoTealDim,
            modifier = modifier,
            onClick = hvClick
        )

        DashboardCardId.MOTORS -> {
            val liveFrontRpm = vehicleSnapshot?.engineSpeedFront ?: telemetry.engineSpeedFront
            val liveRearRpm = vehicleSnapshot?.engineSpeedRear ?: telemetry.engineSpeedRear
            val liveMotorSpeedKmh = vehicleSnapshot?.directSpeedKmh?.takeIf { it > 0.1 } ?: telemetry.speed
            val liveEnginePower = vehicleSnapshot?.enginePower ?: telemetry.enginePower
            val isStopped = liveMotorSpeedKmh < 0.5
            val frontMotorRpm = if (isStopped) null else liveFrontRpm.takeIf { it >= 10 }
            val rearMotorRpm = if (isStopped) null else liveRearRpm.takeIf { it >= 10 }

            when (selectedCar?.drivetrain) {
                Drivetrain.FWD -> CardTile(
                    title = stringResource(R.string.stat_front_motor),
                    value = frontMotorRpm?.let { "$it RPM" } ?: "0 RPM",
                    subtitle = "$liveEnginePower kW",
                    iconRes = R.drawable.ic_motor_axle,
                    color = BydElectricBlue,
                    modifier = modifier
                )
                Drivetrain.RWD -> CardTile(
                    title = stringResource(R.string.stat_rear_motor),
                    value = rearMotorRpm?.let { "$it RPM" } ?: "0 RPM",
                    subtitle = "$liveEnginePower kW",
                    iconRes = R.drawable.ic_motor_axle,
                    color = BydElectricBlue,
                    modifier = modifier
                )
                else -> {
                    val drivetrainLabel = when (telemetry.drivetrainState) {
                        1, 4 -> "AWD"
                        2, 5 -> "FWD"
                        3, 6, 19 -> "RWD"
                        else -> when (selectedCar?.drivetrain) {
                            Drivetrain.AWD -> "AWD"
                            Drivetrain.FWD -> "FWD"
                            Drivetrain.RWD -> "RWD"
                            else -> null
                        }
                    }
                    val kwLine = "$liveEnginePower kW"
                    CardTile(
                        title = stringResource(R.string.stat_front_rear_motors),
                        value = "${frontMotorRpm ?: "0"} / ${rearMotorRpm ?: "0"} RPM",
                        subtitle = if (drivetrainLabel != null) "$drivetrainLabel · $kwLine" else kwLine,
                        iconRes = R.drawable.ic_motor_axle,
                        color = BydElectricBlue,
                        modifier = modifier
                    )
                }
            }
        }

        DashboardCardId.DRIVING -> CardTile(
            title = stringResource(R.string.stat_driving_dynamics),
            value = run {
                listOfNotNull(
                    // PHEV powertrain source — see the matching Classic-layout card in
                    // VehicleStats.kt. Gated on the car being a PHEV: a BEV Seal reports
                    // energyMode 1 (=EV), so energyMode != 0 alone is NOT enough.
                    energyModeLabel(telemetry.energyMode)
                        .takeIf { selectedCar?.isPhev == true && telemetry.energyMode != 0 },
                    telemetry.regenModeName.takeIf { telemetry.regenMode != 0 },
                    telemetry.driveModeName.takeIf { telemetry.driveMode != 0 },
                ).joinToString(" / ").ifEmpty { "—" }
            },
            subtitle = run {
                val slope = vehicleSnapshot?.roadSlopeDeg
                val needsInit = telemetry.isCarOn && telemetry.driveMode == 0
                when {
                    needsInit -> stringResource(R.string.change_drive_mode_hint)
                    slope != null -> stringResource(R.string.slope_value, String.format("%.1f", slope))
                    else -> null
                }
            },
            icon = Icons.Filled.DirectionsCar,
            color = BydElectricBlue,
            modifier = modifier
        )

        DashboardCardId.TYRES -> TyreCardTile(
            telemetry = telemetry,
            tyreUnit = tyreUnit,
            modifier = modifier,
            onClick = tyreClick
        )

        DashboardCardId.ODOMETER -> CardTile(
            title = stringResource(R.string.stat_odometer),
            value = "${String.format("%.1f", telemetry.odometer)} $distanceUnit",
            subtitle = stringResource(R.string.odometer_subtitle_lifetime),
            icon = Icons.Filled.Speed,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier
        )

        DashboardCardId.DISCHARGE -> CardTile(
            title = stringResource(R.string.stat_total_discharge),
            value = "${String.format("%.1f", telemetry.totalDischarge)} kWh",
            subtitle = run {
                val phm = telemetry.totalElecConPHM ?: return@run stringResource(R.string.discharge_subtitle_lifetime)
                val mileage = vehicleSnapshot?.statisticTotalMileageValue?.toDouble()
                    ?: vehicleSnapshot?.statisticTotalMileageDecimal
                    ?: telemetry.odometer
                if (mileage > 0) "Drivetrain: ${String.format("%.1f", phm * mileage / 100.0)} kWh"
                else stringResource(R.string.discharge_subtitle_lifetime)
            },
            icon = Icons.Filled.ElectricalServices,
            color = AccelerationOrange,
            modifier = modifier
        )
    }
}

/**
 * A four-row tile: (1) icon + title, (2) value, (3) flexible gap, (4) subtitle at
 * the far bottom. Fills the height it is given so the subtitle stays pinned down.
 */
@Composable
private fun CardTile(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconRes: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    // In Neon, give the card an accent-tinted edge so it reads as a surface on black.
    val neon = MaterialTheme.isNeon
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (neon) 1.5.dp else 1.dp,
                color = if (neon) color.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(14.dp)
            )
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        val dense = LocalDashboardCardDense.current
        val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        val valueColor = if (isLight) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f)
            else MaterialTheme.colorScheme.onSurface
        val valueWeight = if (isLight) FontWeight.Medium else FontWeight.Bold
        if (dense) {
            // Split-screen: one line — icon + title on the left, value pushed to the end.
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    icon != null -> Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
                    iconRes != null -> Icon(painterResource(id = iconRes), null, tint = color, modifier = Modifier.size(18.dp))
                }
                if (icon != null || iconRes != null) Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = value,
                    fontSize = 16.sp,
                    fontWeight = valueWeight,
                    color = valueColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        icon != null -> Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                        iconRes != null -> Icon(painter = painterResource(id = iconRes), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                    }
                    if (icon != null || iconRes != null) Spacer(Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontSize = if (LocalDashboardCardCompact.current) 18.sp else TextUnit.Unspecified,
                    fontWeight = valueWeight,
                    color = valueColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = subtitle ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Tyre tile shaped like [CardTile] for visual uniformity: icon + title on top, the
 * 2×2 pressure/temperature grid filling the middle, and a subtitle pinned to the bottom.
 */
@Composable
private fun TyreCardTile(
    telemetry: VehicleTelemetry,
    tyreUnit: TyrePressureUnit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context.applicationContext) }
    val selectedCar by prefs.selectedCarConfig.collectAsState(initial = null)
    val frontBar = selectedCar?.frontTyrePressureBar
    val rearBar = selectedCar?.rearTyrePressureBar
    val denseFont = LocalDashboardCardCompact.current
    val denseLayout = LocalDashboardCardDense.current
    val neon = MaterialTheme.isNeon

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (neon) 1.5.dp else 1.dp,
                color = if (neon) BydElectricBlue.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        if (denseLayout) {
            // Split-screen: one line — icon + title, then the four pressures (FL FR RL RR).
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.TireRepair, contentDescription = null, tint = BydElectricBlue, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.stat_tyres),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TyreMini(telemetry.tyrePressureLF, tyreUnit, frontBar)
                    TyreMini(telemetry.tyrePressureRF, tyreUnit, frontBar)
                    TyreMini(telemetry.tyrePressureLR, tyreUnit, rearBar)
                    TyreMini(telemetry.tyrePressureRR, tyreUnit, rearBar)
                }
            }
        } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
        ) {
            // Row 1 — icon + title
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.TireRepair,
                    contentDescription = null,
                    tint = BydElectricBlue,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.stat_tyres),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(4.dp))
            // Rows 2–3 — the 2×2 grid; weighted rows so both always fit the card height
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TyreCell("FL", telemetry.tyreTempLF.takeIf { it > 0 }, telemetry.tyrePressureLF, tyreUnit, frontBar, modifier = Modifier.weight(1f).fillMaxHeight(), large = true, compact = denseFont)
                    TyreCell("FR", telemetry.tyreTempRF.takeIf { it > 0 }, telemetry.tyrePressureRF, tyreUnit, frontBar, modifier = Modifier.weight(1f).fillMaxHeight(), large = true, compact = denseFont)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TyreCell("RL", telemetry.tyreTempLR.takeIf { it > 0 }, telemetry.tyrePressureLR, tyreUnit, rearBar, modifier = Modifier.weight(1f).fillMaxHeight(), large = true, compact = denseFont)
                    TyreCell("RR", telemetry.tyreTempRR.takeIf { it > 0 }, telemetry.tyrePressureRR, tyreUnit, rearBar, modifier = Modifier.weight(1f).fillMaxHeight(), large = true, compact = denseFont)
                }
            }
            Spacer(Modifier.height(4.dp))
            // Row 4 — subtitle
            Text(
                text = stringResource(R.string.tyre_subtitle_pressure, tyreUnit.label()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        }
    }
}

@Composable
private fun TyreMini(pressurePsi: Double, unit: TyrePressureUnit, recommendedBar: Double?) {
    val status = recommendedBar?.let { tyrePressureStatus(pressurePsi, it) }
    val col = when (status) {
        TyrePressureStatus.LOW -> AccelerationOrange
        TyrePressureStatus.HIGH -> BydErrorRed
        TyrePressureStatus.NORMAL -> RegenGreen
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(unit.formatValue(pressurePsi), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = col, maxLines = 1)
}
