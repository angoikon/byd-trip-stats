package com.byd.tripstats.ui.screens.dashboard

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.data.config.Drivetrain
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.distanceUnit
import com.byd.tripstats.sdk.VehicleTelemetrySnapshot
import com.byd.tripstats.ui.components.energyModeLabel
import com.byd.tripstats.ui.theme.AccelerationOrange
import com.byd.tripstats.ui.theme.BatteryBlue
import com.byd.tripstats.ui.theme.BydEcoTealDim
import com.byd.tripstats.ui.theme.BydElectricBlue
import com.byd.tripstats.ui.theme.RegenGreen
import com.byd.tripstats.ui.theme.isNeon

@Composable
fun VehicleStats(
    telemetry: VehicleTelemetry,
    vehicleSnapshot: VehicleTelemetrySnapshot? = null,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
    onNavigateToBatteryDegradation: () -> Unit = {},
    onShowBattery12vHistory: () -> Unit = {},
    tyreUnit: TyrePressureUnit = TyrePressureUnit.BAR,
    onShowTyreDialog: () -> Unit = {},
    dashboardIconsEnabled: Boolean = true
) {
    val context     = LocalContext.current
    val prefs       = remember { PreferencesManager(context.applicationContext) }
    val selectedCar by prefs.selectedCarConfig.collectAsState(initial = null)
    val unitSystem  by prefs.unitSystem.collectAsState(initial = prefs.getCachedUnitSystem())
    val distanceUnit = unitSystem.distanceUnit

    val colModifier = if (fillHeight) modifier.fillMaxHeight() else modifier.verticalScroll(rememberScrollState())
    val spacing     = if (fillHeight) 4.dp else 8.dp

    Column(
        modifier = colModifier,
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        val cardMod = if (fillHeight) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth()
        val smallCardMod = if (fillHeight) Modifier.fillMaxWidth().weight(0.75f) else Modifier.fillMaxWidth()
        val tyreCardMod = if (fillHeight) Modifier.fillMaxWidth().weight(1.0f) else Modifier.fillMaxWidth()

        val sohDisplay: String = run {
            val statSoh = vehicleSnapshot?.statisticBatterySoh
                ?: telemetry.statisticBatterySoh
            val pct = when {
                statSoh != null && statSoh in 50.0..110.0 ->
                    String.format("%.1f%%", statSoh)
                telemetry.soh in 1..110 ->
                    String.format("%.1f%%", telemetry.soh.toDouble())
                else -> "—"
            }
            val src = when {
                statSoh != null && statSoh in 50.0..110.0 -> "stat"
                !telemetry.sohEstimated && telemetry.soh in 1..110 -> "BMS"
                telemetry.soh in 1..110 -> "~est"
                else -> ""
            }
            if (pct == "—") stringResource(R.string.soh_value, "—") else stringResource(R.string.soh_value, "$pct${if (src.isNotEmpty()) " ($src)" else ""}")
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
            avg?.takeIf { it.isFinite() && it in -40.0..120.0 }?.let { stringResource(R.string.battery_temp_value, it.toInt()) }
        }
        StatCard(
            title    = stringResource(R.string.stat_battery),
            value    = sohDisplay,
            subtitle = batteryTempSubtitle,
            icon     = Icons.Filled.BatteryChargingFull,
            color    = BatteryBlue,
            compact  = fillHeight,
            modifier = cardMod,
            onClick  = onNavigateToBatteryDegradation
        )
        StatCard(
            title    = stringResource(R.string.stat_environment),
            value    = run {
                val externalTemp = vehicleSnapshot?.instrumentOutCarTemperature
                    ?: telemetry.instrumentOutCarTemperature
                if (externalTemp != null) stringResource(R.string.ambient_temp_value, externalTemp) else stringResource(R.string.ambient_temp_value, "—")
            },
            subtitle = run {
                val pm25In = vehicleSnapshot?.pm25InCar
                val pm25Out = vehicleSnapshot?.pm25OutCar
                if (pm25In != null || pm25Out != null)
                    stringResource(R.string.pm25_value, pm25In ?: "—", pm25Out ?: "—")
                else null
            },
            icon     = Icons.Filled.Public,
            color    = RegenGreen,
            compact  = fillHeight,
            modifier = cardMod
        )
        StatCard(
            title    = stringResource(R.string.stat_hv_12v),
            value    = run {
                val cellMin  = vehicleSnapshot?.statisticCellVoltageMin
                    ?: telemetry.statisticCellVoltageMin
                    ?: telemetry.batteryCellVoltageMin.takeIf { it > 0.0 }
                val cellMax  = vehicleSnapshot?.statisticCellVoltageMax
                    ?: telemetry.statisticCellVoltageMax
                    ?: telemetry.batteryCellVoltageMax.takeIf { it > 0.0 }
                val cellV    = cellMin ?: cellMax
                val cells    = selectedCar?.cellCount ?: 0
                val packVoltage = vehicleSnapshot?.batteryTotalVoltage?.takeIf { it > 0 }
                    ?: telemetry.batteryTotalVoltage.takeIf { it > 0 }
                val hvVolts: Int? = when {
                    packVoltage != null               -> packVoltage
                    cellV != null && cells > 0        -> (cellV * cells).toInt()
                    else                              -> null
                }
                val hvStr    = if (hvVolts != null) "$hvVolts V" else "— V"
                val v12Str   = if (telemetry.battery12vVoltage > 0.0) String.format("%.2f", telemetry.battery12vVoltage) + " V" else "— V"
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
            icon     = Icons.Filled.Bolt,
            color    = BydEcoTealDim,
            compact  = fillHeight,
            modifier = cardMod,
            onClick  = onShowBattery12vHistory
        )

        val liveFrontRpm = vehicleSnapshot?.engineSpeedFront ?: telemetry.engineSpeedFront
        val liveRearRpm  = vehicleSnapshot?.engineSpeedRear  ?: telemetry.engineSpeedRear
        val liveMotorSpeedKmh = vehicleSnapshot?.directSpeedKmh?.takeIf { it > 0.1 } ?: telemetry.speed
        val liveEnginePower = vehicleSnapshot?.enginePower ?: telemetry.enginePower
        val isStopped = liveMotorSpeedKmh < 0.5
        val frontMotorRpm = if (isStopped) null else liveFrontRpm.takeIf { it >= 10 }
        val rearMotorRpm  = if (isStopped) null else liveRearRpm.takeIf  { it >= 10 }

        when (selectedCar?.drivetrain) {
            Drivetrain.FWD -> StatCard(
                title    = stringResource(R.string.stat_front_motor),
                value    = frontMotorRpm?.let { "$it RPM" } ?: "0 RPM",
                subtitle = "$liveEnginePower kW",
                iconRes  = R.drawable.ic_motor_axle,
                color    = BydElectricBlue,
                compact  = fillHeight,
                modifier = cardMod
            )
            Drivetrain.RWD -> StatCard(
                title    = stringResource(R.string.stat_rear_motor),
                value    = rearMotorRpm?.let { "$it RPM" } ?: "0 RPM",
                subtitle = "$liveEnginePower kW",
                iconRes  = R.drawable.ic_motor_axle,
                color    = BydElectricBlue,
                compact  = fillHeight,
                modifier = cardMod
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
                StatCard(
                    title    = stringResource(R.string.stat_front_rear_motors),
                    value    = "${frontMotorRpm ?: "0"} / ${rearMotorRpm ?: "0"} RPM",
                    subtitle = if (drivetrainLabel != null) "$drivetrainLabel · $kwLine" else kwLine,
                    iconRes  = R.drawable.ic_motor_axle,
                    color    = BydElectricBlue,
                    compact  = fillHeight,
                    modifier = cardMod
                )
            }
        }

        StatCard(
            title    = stringResource(R.string.stat_driving_dynamics),
            value    = run {
                listOfNotNull(
                    // PHEV powertrain source (EV / Force EV / HEV / Fuel / Keep) — the most
                    // important of the three on a plug-in, so it leads. Uses the same
                    // energyModeLabel() as the trip Mode Timeline's energy lane.
                    //
                    // Gated on the CAR being a PHEV, not on energyMode != 0: the DiLink-3 poll
                    // stores getEnergyMode for every car with no PHEV check, and a BEV Seal
                    // reports 1 (=EV) — true but meaningless, and it leaked onto BEV dashboards.
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
            icon     = Icons.Filled.DirectionsCar,
            color    = BydElectricBlue,
            compact  = fillHeight,
            modifier = cardMod
        )

        if (!dashboardIconsEnabled) {
            TyreStatCard(
                telemetry = telemetry,
                tyreUnit  = tyreUnit,
                compact   = fillHeight,
                modifier  = tyreCardMod,
                onClick   = onShowTyreDialog
            )
        }

        StatCard(
            title    = stringResource(R.string.stat_odometer),
            value    = "${String.format("%.1f", telemetry.odometer)} $distanceUnit",
            icon     = Icons.Filled.Speed,
            color    = MaterialTheme.colorScheme.primary,
            compact  = fillHeight,
            modifier = smallCardMod
        )
        StatCard(
            title    = stringResource(R.string.stat_total_discharge),
            value    = "${String.format("%.1f", telemetry.totalDischarge)} kWh",
            subtitle = run {
                val phm = telemetry.totalElecConPHM ?: return@run null
                val mileage = vehicleSnapshot?.statisticTotalMileageValue?.toDouble()
                    ?: vehicleSnapshot?.statisticTotalMileageDecimal
                    ?: telemetry.odometer
                if (mileage > 0)
                    "Drivetrain: ${String.format("%.1f", phm * mileage / 100.0)} kWh"
                else null
            },
            icon     = Icons.Filled.ElectricalServices,
            color    = AccelerationOrange,
            compact  = fillHeight,
            modifier = smallCardMod
        )
    }
}

@Composable
fun StatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: Any? = null,
    iconRes: Int? = null,
    color: Color,
    subtitle: String? = null,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val pad      = if (compact) 8.dp  else 12.dp
    val iconSize = if (compact) 22.dp else 32.dp
    val spacerW  = if (compact) 8.dp  else 12.dp
    val neon     = MaterialTheme.isNeon

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (neon) 1.5.dp else 1.dp,
                color = if (neon) color.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            ),
        onClick  = onClick ?: {},
        enabled  = onClick != null,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        // disabledElevation matters: cards without an onClick use the clickable-Card's
        // `enabled = false` path, whose default disabled elevation is ~0 (they'd look flat).
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp, disabledElevation = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                icon is ImageVector -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(iconSize)
                )
                iconRes != null -> Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(iconSize)
                )
            }
            Spacer(modifier = Modifier.width(spacerW))
            Column {
                Text(
                    text     = title,
                    style    = if (compact) MaterialTheme.typography.labelMedium
                    else MaterialTheme.typography.bodyMedium,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text       = value,
                    style      = if (compact) MaterialTheme.typography.titleSmall
                    else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                subtitle?.let {
                    Text(
                        text  = it,
                        style = if (compact) MaterialTheme.typography.labelSmall
                        else MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
