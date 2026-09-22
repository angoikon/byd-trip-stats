package com.byd.tripstats.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.preferences.UnitSystem
import com.byd.tripstats.data.preferences.consumptionUnit
import com.byd.tripstats.data.preferences.convertDistance
import com.byd.tripstats.data.preferences.convertEfficiency
import com.byd.tripstats.data.preferences.speedUnit
import androidx.compose.ui.res.stringResource
import com.byd.tripstats.R
import com.byd.tripstats.ui.components.BrandSwitch
import com.byd.tripstats.ui.theme.BydElectricAzure
import com.byd.tripstats.ui.theme.BydErrorRed
import kotlinx.coroutines.delay

@Composable
fun TripControls(
    telemetry: VehicleTelemetry,
    liveGear: String? = null,
    isInTrip: Boolean,
    autoTripDetection: Boolean,
    onStartTrip: () -> Unit,
    onEndTrip: () -> Unit,
    onToggleAutoDetection: () -> Unit,
    liveSessionStartMs: Long? = null,
    liveOffStateMs: Long = 0L,
    liveDistanceKm: Double = 0.0,
    liveOdometerDistanceKm: Double = 0.0,
    liveAccumulatedKwh: Double = 0.0,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    modifier: Modifier = Modifier
) {
    var showStopConfirmDialog by remember { mutableStateOf(false) }

    if (showStopConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showStopConfirmDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            icon = {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = null,
                    tint = BydErrorRed,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text(stringResource(R.string.stop_recording_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    buildString {
                        append(stringResource(R.string.stop_recording_msg))
                        if (autoTripDetection) {
                            append("\n\nAutomatic trip recording will be turned off and future trips will be manual until you switch it back on.")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStopConfirmDialog = false
                        if (autoTripDetection) {
                            onToggleAutoDetection()
                        }
                        onEndTrip()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BydErrorRed)
                ) { Text(stringResource(R.string.stop_trip_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmDialog = false }) { Text(stringResource(R.string.keep_recording_action)) }
            }
        )
    }

    Card(
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(14.dp)
            ),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        var elapsedMs by remember(liveSessionStartMs, liveOffStateMs) {
            mutableStateOf(
                liveSessionStartMs?.let {
                    (System.currentTimeMillis() - it - liveOffStateMs).coerceAtLeast(0L)
                } ?: 0L
            )
        }
        LaunchedEffect(liveSessionStartMs, liveOffStateMs) {
            if (liveSessionStartMs == null) return@LaunchedEffect
            while (true) {
                delay(1000L)
                elapsedMs = (System.currentTimeMillis() - liveSessionStartMs - liveOffStateMs)
                    .coerceAtLeast(0L)
            }
        }

        var showManualWarning by remember { mutableStateOf(false) }
        if (showManualWarning) {
            AlertDialog(
                onDismissRequest = { showManualWarning = false },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                icon = {
                    Icon(Icons.Filled.WarningAmber, null,
                        tint = MaterialTheme.colorScheme.error)
                },
                title = {
                    Text(stringResource(R.string.stop_recording_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        stringResource(R.string.manual_tracking_warning) + "\n\n" +
                                stringResource(R.string.manual_tracking_note),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showManualWarning = false
                        onToggleAutoDetection()
                    }) {
                        Text(stringResource(R.string.switch_to_manual),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showManualWarning = false }) {
                        Text(stringResource(R.string.keep_auto))
                    }
                }
            )
        }

        val gear = liveGear ?: telemetry.gear
        val autoParkedInTrip = isInTrip && gear == "P"

        val gearColor = when {
            gear == "R"            -> com.byd.tripstats.ui.theme.AccelerationOrange
            autoParkedInTrip       -> MaterialTheme.colorScheme.onSurfaceVariant
            isInTrip               -> MaterialTheme.colorScheme.primary
            else                   -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        val statusText = when {
            isInTrip && telemetry.speed > 0.5    -> stringResource(R.string.state_driving)
            isInTrip && gear in listOf("D", "R") -> stringResource(R.string.state_ready)
            autoParkedInTrip                     -> stringResource(R.string.state_stopped)
            isInTrip                             -> stringResource(R.string.state_trip_in_progress)
            gear == "D"                          -> stringResource(R.string.state_ready_to_drive)
            gear == "R"                          -> stringResource(R.string.state_reverse)
            gear == "P"                          -> stringResource(R.string.state_waiting)
            gear == "N"                          -> stringResource(R.string.state_neutral)
            else                                 -> stringResource(R.string.state_waiting)
        }

        val autoLabel: @Composable () -> Unit = {
            Text(text = stringResource(R.string.auto_short), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        val autoSwitch: @Composable () -> Unit = {
            BrandSwitch(
                checked = autoTripDetection,
                onCheckedChange = { enabled ->
                    if (!enabled) showManualWarning = true
                    else onToggleAutoDetection()
                },
            )
        }
        val autoToggle: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                autoLabel()
                Spacer(modifier = Modifier.width(6.dp))
                autoSwitch()
            }
        }

        val elapsedH   = elapsedMs / 3_600_000L
        val elapsedM   = (elapsedMs % 3_600_000L) / 60_000L
        val elapsedS   = (elapsedMs % 60_000L) / 1000L
        val elapsedStr = "%02d:%02d:%02d".format(elapsedH, elapsedM, elapsedS)

        val distKm = liveDistanceKm
        val speedDistKm = liveOdometerDistanceKm.takeIf { it > 0.0 } ?: distKm
        val avgSpeedDisplay = if (elapsedMs > 10_000L) {
            unitSystem.convertDistance(speedDistKm) / (elapsedMs / 3_600_000.0)
        } else 0.0
        val kwhPer100km = if (distKm > 0.5) (liveAccumulatedKwh / distKm) * 100.0 else 0.0
        val effDisplay  = unitSystem.convertEfficiency(kwhPer100km)

        val recordDisabled = !isInTrip && autoTripDetection

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isWideCard   = maxWidth >= 840.dp
            val isNarrowCard = maxWidth < 720.dp
            val cardHeight = if (isWideCard) 80.dp else 64.dp

            val recordStopButton: @Composable (compact: Boolean) -> Unit = { compact ->
                Button(
                    onClick = {
                        if (isInTrip) showStopConfirmDialog = true else onStartTrip()
                    },
                    enabled = !recordDisabled,
                    shape = RoundedCornerShape(50),
                    contentPadding = if (compact) {
                        PaddingValues(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
                    } else {
                        PaddingValues(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isInTrip) BydErrorRed else BydElectricAzure,
                        contentColor = Color.White,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Icon(
                        imageVector = if (isInTrip) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(if (compact) 11.dp else 14.dp)
                    )
                    Spacer(modifier = Modifier.width(if (compact) 4.dp else 6.dp))
                    Text(
                        text = if (isInTrip) stringResource(R.string.btn_stop) else stringResource(R.string.btn_record),
                        fontSize = if (compact) 11.sp else 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            val titles: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = stringResource(R.string.trip_tracking_label),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(gearColor, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        val subtitle = if (isInTrip) statusText
                                       else "$gear · $statusText"
                        Text(
                            text = subtitle,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (isNarrowCard && isInTrip) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) { titles() }
                        Box(modifier = Modifier.weight(1f)) {
                            DenseStat(stringResource(R.string.stat_time), elapsedStr, "")
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Box(modifier = Modifier.weight(1f)) {
                            DenseStat(stringResource(R.string.stat_avg_abbr), "%.0f".format(avgSpeedDisplay), unitSystem.speedUnit)
                        }
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterEnd
                        ) { autoLabel() }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) { recordStopButton(true) }
                        Box(modifier = Modifier.weight(1f)) {
                            DenseStat(stringResource(R.string.stat_energy), "%.1f".format(liveAccumulatedKwh), "kWh")
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Box(modifier = Modifier.weight(1f)) {
                            DenseStat(
                                stringResource(R.string.stat_consumption_abbr),
                                if (effDisplay > 0) "%.1f".format(effDisplay) else "—",
                                unitSystem.consumptionUnit
                            )
                        }
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterEnd
                        ) { autoSwitch() }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cardHeight)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    recordStopButton(false)
                    titles()
                    if (isInTrip) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DenseStat(stringResource(R.string.stat_time), elapsedStr, "")
                            DenseStat(stringResource(R.string.stat_avg_abbr), "%.0f".format(avgSpeedDisplay), unitSystem.speedUnit)
                            DenseStat(stringResource(R.string.stat_energy), "%.1f".format(liveAccumulatedKwh), "kWh")
                            DenseStat(
                                stringResource(R.string.stat_consumption_abbr),
                                if (effDisplay > 0) "%.1f".format(effDisplay) else "—",
                                unitSystem.consumptionUnit
                            )
                        }
                    } else {
                        Text(
                            text = if (autoTripDetection)
                                stringResource(R.string.auto_detection_desc)
                            else
                                stringResource(R.string.press_record_hint),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    autoToggle()
                }
            }
        }
    }
}

@Composable
private fun DenseStat(label: String, value: String, unit: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            if (unit.isNotEmpty()) {
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = unit,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 1.dp),
                    maxLines = 1
                )
            }
        }
    }
}
