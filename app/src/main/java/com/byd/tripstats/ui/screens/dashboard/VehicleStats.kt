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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.byd.tripstats.R
import com.byd.tripstats.data.config.Drivetrain
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.distanceUnit
import com.byd.tripstats.sdk.DiLink5Platform
import com.byd.tripstats.sdk.VehicleTelemetrySnapshot
import com.byd.tripstats.ui.components.energyModeLabel
import com.byd.tripstats.ui.theme.AccelerationOrange
import com.byd.tripstats.ui.theme.BatteryBlue
import com.byd.tripstats.ui.theme.BydEcoTealDim
import com.byd.tripstats.ui.theme.BydElectricBlue
import com.byd.tripstats.ui.theme.RegenGreen
import com.byd.tripstats.ui.theme.isNeon

/** One card of [VehicleStats], as data, so the same set can be stacked in a column or set in a grid. */
private sealed interface VehicleStatEntry {
    /** Its share of a height-filling column. The grid gives every row the same height. */
    val weight: Float
}

private class StatEntry(
    val title: String,
    val value: String,
    val color: Color,
    val subtitle: String? = null,
    val icon: ImageVector? = null,
    val iconRes: Int? = null,
    val onClick: (() -> Unit)? = null,
    override val weight: Float = 1f,
) : VehicleStatEntry

private object TyreEntry : VehicleStatEntry {
    override val weight = 1f
}

/**
 * The Classic layout's vehicle cards. Stacked in one column, or with [columns] = 2 (and
 * [fillHeight]) in a two-column grid of [StatCard]'s stacked form, which a full-width screen uses so
 * each card gets twice the height: stacked eight high, the cards had only room for their compact
 * type. The grid is Classic's own look: the icon and an accent-coloured title above a full-size value,
 * centred in the card.
 */
@Composable
fun VehicleStats(
    telemetry: VehicleTelemetry,
    vehicleSnapshot: VehicleTelemetrySnapshot? = null,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
    columns: Int = 1,
    onNavigateToBatteryDegradation: () -> Unit = {},
    onShowBattery12vHistory: () -> Unit = {},
    onShowLifetimeEnergy: () -> Unit = {},
    tyreUnit: TyrePressureUnit = TyrePressureUnit.BAR,
    onShowTyreDialog: () -> Unit = {},
    dashboardIconsEnabled: Boolean = true
) {
    val context     = LocalContext.current
    val prefs       = remember { PreferencesManager(context.applicationContext) }
    val selectedCar by prefs.selectedCarConfig.collectAsState(initial = null)
    val unitSystem  by prefs.unitSystem.collectAsState(initial = prefs.getCachedUnitSystem())
    val distanceUnit = unitSystem.distanceUnit

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
    val environmentValue = run {
        val externalTemp = vehicleSnapshot?.instrumentOutCarTemperature
            ?: telemetry.instrumentOutCarTemperature
        if (externalTemp != null) stringResource(R.string.ambient_temp_value, externalTemp) else stringResource(R.string.ambient_temp_value, "—")
    }
    val environmentSubtitle = run {
        val pm25In = vehicleSnapshot?.pm25InCar
        val pm25Out = vehicleSnapshot?.pm25OutCar
        if (pm25In != null || pm25Out != null)
            stringResource(R.string.pm25_value, pm25In ?: "—", pm25Out ?: "—")
        else null
    }

    val cellMin = vehicleSnapshot?.statisticCellVoltageMin
        ?: telemetry.statisticCellVoltageMin
        ?: telemetry.batteryCellVoltageMin.takeIf { it > 0.0 }
    val cellMax = vehicleSnapshot?.statisticCellVoltageMax
        ?: telemetry.statisticCellVoltageMax
        ?: telemetry.batteryCellVoltageMax.takeIf { it > 0.0 }
    val hvValue = run {
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
    }
    val hvSubtitle = when {
        cellMin != null && cellMax != null ->
            stringResource(R.string.cells_range, String.format("%.3f", cellMin), String.format("%.3f", cellMax))
        cellMin != null ->
            stringResource(R.string.cells_min_only, String.format("%.3f", cellMin))
        cellMax != null ->
            stringResource(R.string.cells_min_only, String.format("%.3f", cellMax))
        // DiLink-5 has no cell-voltage source, so "awaiting" would wait forever.
        DiLink5Platform.isDiLink5 -> stringResource(R.string.cells_unavailable_di5)
        else -> stringResource(R.string.cells_awaiting)
    }

    val liveFrontRpm = vehicleSnapshot?.engineSpeedFront ?: telemetry.engineSpeedFront
    val liveRearRpm  = vehicleSnapshot?.engineSpeedRear  ?: telemetry.engineSpeedRear
    val liveMotorSpeedKmh = vehicleSnapshot?.directSpeedKmh?.takeIf { it > 0.1 } ?: telemetry.speed
    val liveEnginePower = vehicleSnapshot?.enginePower ?: telemetry.enginePower
    val isStopped = liveMotorSpeedKmh < 0.5
    val frontMotorRpm = if (isStopped) null else liveFrontRpm.takeIf { it >= 10 }
    val rearMotorRpm  = if (isStopped) null else liveRearRpm.takeIf  { it >= 10 }
    val motorEntry = when (selectedCar?.drivetrain) {
        Drivetrain.FWD -> StatEntry(
            title    = stringResource(R.string.stat_front_motor),
            value    = frontMotorRpm?.let { "$it RPM" } ?: "0 RPM",
            subtitle = "$liveEnginePower kW",
            iconRes  = R.drawable.ic_motor_axle,
            color    = BydElectricBlue
        )
        Drivetrain.RWD -> StatEntry(
            title    = stringResource(R.string.stat_rear_motor),
            value    = rearMotorRpm?.let { "$it RPM" } ?: "0 RPM",
            subtitle = "$liveEnginePower kW",
            iconRes  = R.drawable.ic_motor_axle,
            color    = BydElectricBlue
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
            StatEntry(
                title    = stringResource(R.string.stat_front_rear_motors),
                value    = "${frontMotorRpm ?: "0"} / ${rearMotorRpm ?: "0"} RPM",
                subtitle = if (drivetrainLabel != null) "$drivetrainLabel · $kwLine" else kwLine,
                iconRes  = R.drawable.ic_motor_axle,
                color    = BydElectricBlue
            )
        }
    }

    val drivingValue = listOfNotNull(
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
    val drivingSubtitle = run {
        val slope = vehicleSnapshot?.roadSlopeDeg
        val needsInit = telemetry.isCarOn && telemetry.driveMode == 0
        when {
            needsInit -> stringResource(R.string.change_drive_mode_hint)
            slope != null -> stringResource(R.string.slope_value, String.format("%.1f", slope))
            else -> null
        }
    }

    val dischargeSubtitle = run {
        val phm = telemetry.totalElecConPHM ?: return@run null
        val mileage = vehicleSnapshot?.statisticTotalMileageValue?.toDouble()
            ?: vehicleSnapshot?.statisticTotalMileageDecimal
            ?: telemetry.odometer
        if (mileage > 0)
            "Drivetrain: ${String.format("%.1f", phm * mileage / 100.0)} kWh"
        else null
    }

    val cards: List<VehicleStatEntry> = listOfNotNull(
        StatEntry(
            title    = stringResource(R.string.stat_battery),
            value    = sohDisplay,
            subtitle = batteryTempSubtitle,
            icon     = Icons.Filled.BatteryChargingFull,
            color    = BatteryBlue,
            onClick  = onNavigateToBatteryDegradation
        ),
        StatEntry(
            title    = stringResource(R.string.stat_environment),
            value    = environmentValue,
            subtitle = environmentSubtitle,
            icon     = Icons.Filled.Public,
            color    = RegenGreen
        ),
        StatEntry(
            title    = stringResource(R.string.stat_hv_12v),
            value    = hvValue,
            subtitle = hvSubtitle,
            icon     = Icons.Filled.Bolt,
            color    = BydEcoTealDim,
            onClick  = onShowBattery12vHistory
        ),
        motorEntry,
        StatEntry(
            title    = stringResource(R.string.stat_driving_dynamics),
            value    = drivingValue,
            subtitle = drivingSubtitle,
            icon     = Icons.Filled.DirectionsCar,
            color    = BydElectricBlue
        ),
        // With the dashboard icons on, the energy-flow graphic already shows the tyres.
        TyreEntry.takeIf { !dashboardIconsEnabled },
        StatEntry(
            title    = stringResource(R.string.stat_odometer),
            value    = "${String.format("%.1f", telemetry.odometer)} $distanceUnit",
            icon     = Icons.Filled.Speed,
            color    = MaterialTheme.colorScheme.primary,
            weight   = 0.75f
        ),
        StatEntry(
            title    = stringResource(R.string.stat_total_discharge),
            value    = "${String.format("%.1f", telemetry.totalDischarge)} kWh",
            subtitle = dischargeSubtitle,
            icon     = Icons.Filled.ElectricalServices,
            color    = AccelerationOrange,
            onClick  = onShowLifetimeEnergy,
            weight   = 0.75f
        ),
    )

    @Composable
    fun Entry(entry: VehicleStatEntry, compact: Boolean, stacked: Boolean, modifier: Modifier) {
        when (entry) {
            is StatEntry -> StatCard(
                title    = entry.title,
                value    = entry.value,
                subtitle = entry.subtitle,
                icon     = entry.icon,
                iconRes  = entry.iconRes,
                color    = entry.color,
                compact  = compact,
                stacked  = stacked,
                modifier = modifier,
                onClick  = entry.onClick
            )
            TyreEntry -> TyreStatCard(
                telemetry = telemetry,
                tyreUnit  = tyreUnit,
                compact   = compact,
                stacked   = stacked,
                modifier  = modifier,
                onClick   = onShowTyreDialog
            )
        }
    }

    if (fillHeight && columns >= 2) {
        // Row-major pairs; an odd last card spans the width (7 cards with the dashboard icons on).
        Column(
            modifier = modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            cards.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { entry ->
                        Entry(entry, compact = false, stacked = true, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    } else {
        val colModifier = if (fillHeight) modifier.fillMaxHeight() else modifier.verticalScroll(rememberScrollState())
        Column(
            modifier = colModifier,
            verticalArrangement = Arrangement.spacedBy(if (fillHeight) 4.dp else 8.dp)
        ) {
            cards.forEach { entry ->
                Entry(
                    entry,
                    compact  = fillHeight,
                    stacked  = false,
                    modifier = if (fillHeight) Modifier.fillMaxWidth().weight(entry.weight) else Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * An accent colour for text on a card: as is on the dark themes, darkened on the light one, where
 * the accents read at 1.4:1 (electric blue) to 2:1 (orange) on its pale card and ~5:1 darkened.
 */
@Composable
internal fun accentTextColor(color: Color): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) lerp(color, Color.Black, 0.45f) else color

/** What each stretch of a card's reading is, for [readingParts]. */
internal enum class ReadingPart {
    /** The figure itself, or the "—" standing in for one: full size. */
    FIGURE,
    /** A unit, a label before the figure ("SoH:") or a source tag ("(stat)"): smaller and grey. */
    UNIT,
    /** The "/" between two figures: full size, grey. */
    SEPARATOR,
}

/**
 * Splits a card's formatted reading so the figures lead and the rest recedes: "571 V / 13.70 V" is
 * 571, " V ", "/", " ", 13.70, " V". It reads the finished, translated string, so no translation
 * has to be cut into pieces: no label in any of them holds a digit. A reading with no figure at
 * all ("Standard / Normal") keeps its words full size and only greys the slashes.
 */
internal fun readingParts(value: String): List<Pair<String, ReadingPart>> {
    val hasFigure = value.any { it.isDigit() || it == '—' }
    fun partAt(i: Int): ReadingPart {
        val c = value[i]
        val next = value.getOrNull(i + 1)
        return when {
            c == '/' -> ReadingPart.SEPARATOR
            !hasFigure -> ReadingPart.FIGURE
            c.isDigit() || c == '—' -> ReadingPart.FIGURE
            // A decimal point or comma inside a number: 13.70, or 13,70 where the locale writes it so.
            (c == '.' || c == ',') && value.getOrNull(i - 1)?.isDigit() == true && next?.isDigit() == true ->
                ReadingPart.FIGURE
            // A minus sign that starts a number: -5 °C.
            (c == '-' || c == '−') && next?.isDigit() == true && value.getOrNull(i - 1)?.isDigit() != true ->
                ReadingPart.FIGURE
            else -> ReadingPart.UNIT
        }
    }
    val kinds = List(value.length) { partAt(it) }
    val parts = mutableListOf<Pair<String, ReadingPart>>()
    var start = 0
    for (i in 1..value.length) {
        if (i == value.length || kinds[i] != kinds[start]) {
            parts += value.substring(start, i) to kinds[start]
            start = i
        }
    }
    return parts
}

/**
 * [readingParts] as styled text: figures in the reading's own style, the rest [unitScale] of its size
 * and grey. Split screen's 14 sp readings keep their units at 80 %, as 70 % of 14 sp is under 10 sp.
 */
@Composable
private fun styledReading(value: String, unitScale: Float = 0.7f): AnnotatedString {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return remember(value, muted, unitScale) {
        buildAnnotatedString {
            readingParts(value).forEach { (text, part) ->
                when (part) {
                    ReadingPart.FIGURE -> append(text)
                    ReadingPart.UNIT -> withStyle(SpanStyle(color = muted, fontSize = unitScale.em, fontWeight = FontWeight.Medium)) { append(text) }
                    ReadingPart.SEPARATOR -> withStyle(SpanStyle(color = muted, fontWeight = FontWeight.Normal)) { append(text) }
                }
            }
        }
    }
}

/**
 * The title line of a card in Classic's grid ([StatCard] and [TyreStatCard] `stacked`): the card's
 * icon, then the title in the accent colour, 16 sp. Beside the whole card, as in the single column,
 * the icon took ~44 dp from a ~190 dp card and cut off values such as "Standard / Normal"; on the
 * title line it costs the values no width.
 */
@Composable
internal fun StackedCardHeader(title: String, color: Color, icon: ImageVector? = null, iconRes: Int? = null) {
    val accent = accentTextColor(color)
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            icon != null -> Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.padding(end = 8.dp).size(24.dp))
            iconRes != null -> Icon(painterResource(id = iconRes), contentDescription = null, tint = accent, modifier = Modifier.padding(end = 8.dp).size(24.dp))
        }
        Text(
            text       = title,
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color      = accent,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis
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
    /** Classic's grid form: the icon and the title in the accent colour on top, the value full width. */
    stacked: Boolean = false,
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
        if (stacked) {
            // Sized to be read at a glance while driving: 16 sp title and subtitle around a 22 sp
            // value, ~82 dp of text in a ~120 dp card on a 1280 × 600 dp head unit. The figures are
            // semibold in the theme's main text colour, white on the dark themes and deep navy on
            // the light one, and their units smaller and grey. The single column below (split
            // screen, phones) takes the same colours.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.Center
            ) {
                StackedCardHeader(title, color, icon as? ImageVector, iconRes)
                Spacer(Modifier.height(4.dp))
                Text(
                    text       = styledReading(value),
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onSurface,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                subtitle?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text     = it,
                        style    = MaterialTheme.typography.bodyLarge,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val accent = accentTextColor(color)
            when {
                icon is ImageVector -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(iconSize)
                )
                iconRes != null -> Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(iconSize)
                )
            }
            Spacer(modifier = Modifier.width(spacerW))
            Column {
                Text(
                    text       = title,
                    style      = if (compact) MaterialTheme.typography.labelMedium
                    else MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color      = accent,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Text(
                    text       = styledReading(value, unitScale = if (compact) 0.8f else 0.7f),
                    style      = if (compact) MaterialTheme.typography.titleSmall
                    else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onSurface
                )
                subtitle?.let {
                    // One line in split screen: a second one (a long hint such as "Change drive
                    // mode to initialise") was cut through the middle by the card's height.
                    Text(
                        text     = it,
                        style    = if (compact) MaterialTheme.typography.labelSmall
                        else MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (compact) 1 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
