package com.byd.tripstats.ui.screens.tripdetail

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.byd.tripstats.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.ui.components.AltitudeChart
import com.byd.tripstats.ui.components.BatteryVoltageChart
import com.byd.tripstats.ui.components.CondensedAltitudeChart
import com.byd.tripstats.ui.components.CondensedBatteryVoltageChart
import com.byd.tripstats.ui.components.CondensedEnergyChart
import com.byd.tripstats.ui.components.CondensedInstantConsumptionChart
import com.byd.tripstats.ui.components.CondensedMotorRpmChart
import com.byd.tripstats.ui.components.CondensedPowerChart
import com.byd.tripstats.ui.components.CondensedSocChart
import com.byd.tripstats.ui.components.CondensedSpeedChart
import com.byd.tripstats.ui.components.CondensedTyrePressureChart
import com.byd.tripstats.ui.components.EnergyConsumptionChart
import com.byd.tripstats.ui.components.InstantConsumptionChart
import com.byd.tripstats.ui.components.ModeTimelineChart
import com.byd.tripstats.ui.components.ModeTimelineControls
import com.byd.tripstats.ui.components.MotorRpmChart
import com.byd.tripstats.ui.components.PowerChart
import com.byd.tripstats.ui.components.SocChart
import com.byd.tripstats.ui.components.SpeedChart
import com.byd.tripstats.ui.components.TyrePressureChart
import com.byd.tripstats.ui.components.condenseData
import com.byd.tripstats.ui.components.condenseForPower
import com.byd.tripstats.ui.components.condenseForRpm
import com.byd.tripstats.ui.components.condenseForSpeed
import com.byd.tripstats.ui.components.extractTripModes

@Composable
fun TripChartsTab(
    dataPoints: List<TripDataPointEntity>,
    useImperial: Boolean = false,
    /**
     * Whether the selected car is a plug-in hybrid — gates the Mode Timeline's energy lane.
     * Required because a BEV's stored energy_mode is NOT 0: the DiLink-3 poll records
     * getEnergyMode for every car and a BEV Seal reports 1 (=EV), so the lane's own
     * "any point with energyMode != 0" check would light up on BEV trips too.
     */
    isPhev: Boolean = false
) {
    var expandedChart by remember { mutableStateOf<ChartType?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ClickableChartCard(
            title = stringResource(R.string.chart_speed_profile),
            subtitle = stringResource(R.string.axis_speed_over_time, if (useImperial) "mph" else "km/h"),
            onClick = { expandedChart = ChartType.SPEED }
        ) {
            CondensedSpeedChart(dataPoints = dataPoints, useImperial = useImperial, modifier = Modifier.fillMaxSize())
        }

        ClickableChartCard(
            title = stringResource(R.string.chart_motor_rpm),
            subtitle = stringResource(R.string.axis_rpm_over_time),
            onClick = { expandedChart = ChartType.MOTOR_RPM }
        ) {
            CondensedMotorRpmChart(dataPoints = dataPoints, modifier = Modifier.fillMaxSize())
        }

        ClickableChartCard(
            title = stringResource(R.string.chart_power_profile),
            subtitle = stringResource(R.string.axis_power_over_time),
            onClick = { expandedChart = ChartType.POWER }
        ) {
            CondensedPowerChart(dataPoints = dataPoints, modifier = Modifier.fillMaxSize())
        }

        ClickableChartCard(
            title = stringResource(R.string.chart_energy_consumption),
            subtitle = stringResource(R.string.axis_kwh_over_time),
            onClick = { expandedChart = ChartType.ENERGY }
        ) {
            CondensedEnergyChart(dataPoints = dataPoints, modifier = Modifier.fillMaxSize())
        }

        ClickableChartCard(
            title = stringResource(R.string.chart_state_of_charge),
            subtitle = stringResource(R.string.axis_soc_over_time),
            onClick = { expandedChart = ChartType.SOC }
        ) {
            CondensedSocChart(dataPoints = dataPoints, modifier = Modifier.fillMaxSize())
        }

        ClickableChartCard(
            title = stringResource(R.string.chart_elevation_profile),
            subtitle = stringResource(R.string.axis_altitude_over_time),
            onClick = { expandedChart = ChartType.ALTITUDE }
        ) {
            CondensedAltitudeChart(dataPoints = dataPoints, modifier = Modifier.fillMaxSize())
        }

        ClickableChartCard(
            title = stringResource(R.string.chart_battery_voltage),
            subtitle = stringResource(R.string.axis_hv_cells),
            onClick = { expandedChart = ChartType.BATTERY_VOLTAGE }
        ) {
            CondensedBatteryVoltageChart(dataPoints = dataPoints, modifier = Modifier.fillMaxSize())
        }

        ClickableChartCard(
            title = stringResource(R.string.chart_tyre_pressures),
            subtitle = stringResource(R.string.axis_tyre_wheels),
            onClick = { expandedChart = ChartType.TYRE_PRESSURE }
        ) {
            CondensedTyrePressureChart(dataPoints = dataPoints, modifier = Modifier.fillMaxSize())
        }

        ClickableChartCard(
            title = stringResource(R.string.chart_instant_consumption),
            subtitle = stringResource(R.string.axis_consumption_distance, if (useImperial) "kWh/100mi" else "kWh/100km"),
            onClick = { expandedChart = ChartType.INSTANT_CONSUMPTION }
        ) {
            CondensedInstantConsumptionChart(
                dataPoints = dataPoints,
                useImperial = useImperial,
                modifier = Modifier.fillMaxSize()
            )
        }

        ClickableChartCard(
            title = stringResource(R.string.chart_drive_regen_modes),
            subtitle = stringResource(R.string.chart_mode_timeline),
            onClick = { expandedChart = ChartType.MODE_TIMELINE },
            cardHeight = 360.dp
        ) {
            var showDriveModes by remember { mutableStateOf(true) }
            var showRegenModes by remember { mutableStateOf(true) }
            val missingDriveMode = remember(dataPoints) { dataPoints.none { it.extractTripModes().driveMode != 0 } }
            val missingRegenMode = remember(dataPoints) { dataPoints.none { it.extractTripModes().regenMode != 0 } }
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (missingDriveMode || missingRegenMode) {
                    ModeRecordingHint(missingDriveMode, missingRegenMode)
                }
                ModeTimelineControls(
                    showDriveModes = showDriveModes,
                    showRegenModes = showRegenModes,
                    onToggleDriveModes = { showDriveModes = !showDriveModes },
                    onToggleRegenModes = { showRegenModes = !showRegenModes }
                )
                ModeTimelineChart(
                    dataPoints = dataPoints,
                    showDriveModes = showDriveModes,
                    showRegenModes = showRegenModes,
                    showEnergyModes = isPhev,
                    compact = true,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    expandedChart?.let { chartType ->
        FullscreenChartDialog(
            chartType = chartType,
            dataPoints = dataPoints,
            useImperial = useImperial,
            isPhev = isPhev,
            onDismiss = { expandedChart = null }
        )
    }
}

@Composable
internal fun ClickableChartCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    cardHeight: Dp = 300.dp,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(cardHeight)
            .clickable(onClick = onClick)
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
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Icon(
                    imageVector = Icons.Filled.Fullscreen,
                    contentDescription = stringResource(R.string.expand),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

enum class ChartType {
    ENERGY, SPEED, MOTOR_RPM, ALTITUDE, SOC, POWER,
    BATTERY_VOLTAGE, TYRE_PRESSURE, INSTANT_CONSUMPTION, MODE_TIMELINE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullscreenChartDialog(
    chartType: ChartType,
    dataPoints: List<TripDataPointEntity>,
    useImperial: Boolean = false,
    /** See [TripChartsTab] — gates the Mode Timeline's energy lane to plug-in hybrids. */
    isPhev: Boolean = false,
    onDismiss: () -> Unit
) {
    val chartData = remember(chartType, dataPoints) {
        when (chartType) {
            ChartType.MOTOR_RPM -> condenseForRpm(dataPoints, maxPoints = 480)
            ChartType.SPEED     -> condenseForSpeed(dataPoints, maxPoints = 144)
            ChartType.POWER     -> condenseForPower(dataPoints, maxPoints = 144)
            ChartType.ENERGY    -> dataPoints
            else                -> condenseData(dataPoints, maxPoints = 144)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = when (chartType) {
                                    ChartType.ENERGY             -> stringResource(R.string.chart_energy_detailed)
                                    ChartType.SOC                -> stringResource(R.string.chart_soc_detailed)
                                    ChartType.SPEED              -> stringResource(R.string.chart_speed_detailed)
                                    ChartType.MOTOR_RPM          -> stringResource(R.string.chart_rpm_detailed)
                                    ChartType.ALTITUDE           -> stringResource(R.string.chart_elevation_detailed)
                                    ChartType.POWER              -> stringResource(R.string.chart_power_detailed)
                                    ChartType.BATTERY_VOLTAGE    -> stringResource(R.string.chart_voltage_detailed)
                                    ChartType.TYRE_PRESSURE      -> stringResource(R.string.chart_tyre_detailed)
                                    ChartType.INSTANT_CONSUMPTION -> stringResource(R.string.chart_instant_detailed)
                                    ChartType.MODE_TIMELINE      -> stringResource(R.string.chart_modes_detailed)
                                },
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = when (chartType) {
                                    ChartType.ENERGY             -> stringResource(R.string.axis_kwh_over_time)
                                    ChartType.SOC                -> stringResource(R.string.axis_soc_over_time)
                                    ChartType.SPEED              -> stringResource(R.string.axis_speed_over_time, if (useImperial) "mph" else "km/h")
                                    ChartType.MOTOR_RPM          -> stringResource(R.string.axis_rpm_over_time)
                                    ChartType.ALTITUDE           -> stringResource(R.string.axis_altitude_over_time)
                                    ChartType.POWER              -> stringResource(R.string.axis_power_over_time)
                                    ChartType.BATTERY_VOLTAGE    -> stringResource(R.string.axis_hv_cells)
                                    ChartType.TYRE_PRESSURE      -> stringResource(R.string.axis_tyre_wheels)
                                    ChartType.INSTANT_CONSUMPTION -> stringResource(R.string.axis_consumption_distance, if (useImperial) "kWh/100mi" else "kWh/100km")
                                    ChartType.MODE_TIMELINE      -> stringResource(R.string.axis_mode_timeline)
                                },
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, stringResource(R.string.chart_close))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    when (chartType) {
                        ChartType.ENERGY -> EnergyConsumptionChart(
                            dataPoints = chartData, modifier = Modifier.fillMaxSize(), maxPoints = 144
                        )
                        ChartType.SOC -> SocChart(dataPoints = chartData, modifier = Modifier.fillMaxSize())
                        ChartType.SPEED -> SpeedChart(
                            dataPoints = chartData, useImperial = useImperial, modifier = Modifier.fillMaxSize()
                        )
                        ChartType.MOTOR_RPM -> MotorRpmChart(dataPoints = chartData, modifier = Modifier.fillMaxSize())
                        ChartType.ALTITUDE  -> AltitudeChart(dataPoints = chartData, modifier = Modifier.fillMaxSize())
                        ChartType.POWER     -> PowerChart(dataPoints = chartData, modifier = Modifier.fillMaxSize())
                        ChartType.BATTERY_VOLTAGE -> BatteryVoltageChart(dataPoints = chartData, modifier = Modifier.fillMaxSize())
                        ChartType.TYRE_PRESSURE   -> TyrePressureChart(dataPoints = chartData, modifier = Modifier.fillMaxSize())
                        ChartType.INSTANT_CONSUMPTION -> InstantConsumptionChart(
                            dataPoints = dataPoints, useImperial = useImperial, modifier = Modifier.fillMaxSize()
                        )
                        ChartType.MODE_TIMELINE -> {
                            var showDriveModes by remember { mutableStateOf(true) }
                            var showRegenModes by remember { mutableStateOf(true) }
                            val missingDriveMode = remember(dataPoints) { dataPoints.none { it.extractTripModes().driveMode != 0 } }
                            val missingRegenMode = remember(dataPoints) { dataPoints.none { it.extractTripModes().regenMode != 0 } }
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                if (missingDriveMode || missingRegenMode) {
                                    ModeRecordingHint(missingDriveMode, missingRegenMode)
                                }
                                ModeTimelineControls(
                                    showDriveModes = showDriveModes,
                                    showRegenModes = showRegenModes,
                                    onToggleDriveModes = { showDriveModes = !showDriveModes },
                                    onToggleRegenModes = { showRegenModes = !showRegenModes }
                                )
                                ModeTimelineChart(
                                    dataPoints = dataPoints,
                                    showDriveModes = showDriveModes,
                                    showRegenModes = showRegenModes,
                                    showEnergyModes = isPhev,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModeRecordingHint(missingDriveMode: Boolean, missingRegenMode: Boolean) {
    val missing = buildList {
        if (missingDriveMode) add("drive")
        if (missingRegenMode) add("regen")
    }.joinToString(" and ")
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = stringResource(R.string.heatmap_no_mode, missing),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
