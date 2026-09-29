package com.byd.tripstats.ui.screens.triphistory

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.byd.tripstats.data.local.entity.TagEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.preferences.SocSource
import com.byd.tripstats.data.preferences.UnitSystem
import com.byd.tripstats.data.preferences.convertDistance
import com.byd.tripstats.data.preferences.convertEfficiency
import com.byd.tripstats.data.preferences.distanceUnit
import com.byd.tripstats.data.preferences.speedUnit
import com.byd.tripstats.ui.components.RecordIdLabel
import com.byd.tripstats.ui.components.TagChip
import androidx.compose.ui.res.stringResource
import com.byd.tripstats.R
import com.byd.tripstats.ui.theme.*
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TripItem(
    trip: TripEntity,
    tags: List<TagEntity> = emptyList(),
    avgSpeedKmh: Int?,
    tripScore: Int?,
    regenEfficiencyPct: Double?,
    tripCost: Double? = null,
    currencySymbol: String = "€",
    unitSystem: UnitSystem = UnitSystem.METRIC,
    socSource: SocSource = SocSource.PANEL,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    isActive: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onToggleFavourite: () -> Unit = {},
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                enabled = !isActive || !selectionMode
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isActive && selectionMode -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.primaryContainer
            },
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header row: checkbox + id (left) + date + "In Progress" badge + delete (right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    if (selectionMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onClick() },
                            enabled = !isActive,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                RecordIdLabel(id = trip.id)
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (trip.endTime != null)
                            "${formatTimestamp(trip.startTime)}  ->  ${formatTimestamp(trip.endTime)}   | "
                        else
                            formatTimestamp(trip.startTime),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    if (trip.endTime != null) {
                        Spacer(modifier = Modifier.width(12.dp))
                        ScoreChip(score = tripScore)
                    }
                }
                if (trip.endTime == null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = stringResource(R.string.in_progress_label),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    if (!selectionMode && trip.endTime != null) {
                        IconButton(
                            onClick = onToggleFavourite,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = if (trip.isFavourite)
                                    Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = if (trip.isFavourite)
                                    stringResource(R.string.remove_favourite_action) else stringResource(R.string.mark_favourite_action),
                                modifier = Modifier.size(20.dp),
                                tint = if (trip.isFavourite)
                                    ChargingYellow
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    if (!selectionMode) {
                        IconButton(
                            onClick = { showDeleteDialog = true },
                            enabled = !isActive,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                stringResource(R.string.delete),
                                modifier = Modifier.size(18.dp),
                                tint = if (isActive)
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                else
                                    MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            if (tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tags.forEach { tag -> TagChip(tag = tag) }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── Row 1: Distance | Duration | Avg Consumption
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TripMetricChip(
                    icon = Icons.Filled.Route,
                    label = stringResource(R.string.stat_distance),
                    iconTint = MaterialTheme.colorScheme.secondary,
                    value = "${String.format("%.1f", unitSystem.convertDistance(trip.distance ?: 0.0))} ${unitSystem.distanceUnit}",
                    modifier = Modifier.weight(1f)
                )
                TripMetricChip(
                    icon = Icons.Filled.Timer,
                    label = stringResource(R.string.stat_duration),
                    value = if (trip.endTime == null) stringResource(R.string.ongoing_label)
                            else formatDuration(trip.duration ?: 0),
                    modifier = Modifier.weight(1f)
                )
                TripMetricChip(
                    icon = Icons.Filled.Eco,
                    label = stringResource(R.string.stat_avg_consumption),
                    value = trip.efficiency
                        ?.let { "${String.format("%.1f", unitSystem.convertEfficiency(it))} kWh/100${unitSystem.distanceUnit}" } ?: "—",
                    iconTint = RegenGreen,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Row 2: Energy | Max Regen | Regeneration Efficiency
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TripMetricChip(
                    icon = Icons.Filled.BatteryChargingFull,
                    label = stringResource(R.string.stat_energy_consumed),
                    value = trip.energyConsumed?.let {
                        val kwh = "${String.format("%.2f", it)} kWh"
                        if (tripCost != null)
                            "$kwh ($currencySymbol${String.format("%.2f", tripCost)})"
                        else kwh
                    } ?: "—",
                    iconTint = AccelerationOrange,
                    modifier = Modifier.weight(1f)
                )
                TripMetricChip(
                    icon = Icons.Filled.BatteryChargingFull,
                    label = stringResource(R.string.stat_max_regen),
                    value = "${abs(trip.maxRegenPower).toInt()} kW",
                    iconTint = RegenGreen,
                    modifier = Modifier.weight(1f)
                )
                TripMetricChip(
                    icon = Icons.Filled.VolunteerActivism,
                    label = stringResource(R.string.stat_regen_eff),
                    value = regenEfficiencyPct?.let { "%.1f%%".format(it) } ?: "—",
                    iconTint = RegenGreen,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Row 3: Avg Speed | Max Speed | SoC
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TripMetricChip(
                    icon = Icons.Filled.Speed,
                    label = stringResource(R.string.stat_avg_speed),
                    iconTint = BydEcoTealDim,
                    value = if (avgSpeedKmh != null) "$avgSpeedKmh ${unitSystem.speedUnit}" else "—",
                    modifier = Modifier.weight(1f)
                )
                TripMetricChip(
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    label = stringResource(R.string.stat_max_speed),
                    iconTint = BydErrorRed,
                    value = "${trip.maxSpeed.toInt()} ${unitSystem.speedUnit}",
                    modifier = Modifier.weight(1f)
                )
                TripMetricChip(
                    icon = Icons.Filled.Battery4Bar,
                    label = if (socSource == SocSource.PANEL) stringResource(R.string.stat_soc_panel) else stringResource(R.string.stat_soc_bms),
                    value = if (socSource == SocSource.PANEL) {
                        if (trip.endSocPanel != null)
                            "${trip.startSocPanel.toInt()}% → ${trip.endSocPanel.toInt()}%"
                        else "—"
                    } else {
                        if (trip.endSoc != null)
                            "${trip.startSoc.toInt()}% → ${trip.endSoc.toInt()}%"
                        else "—"
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.delete_trip_confirm_title)) },
            text = { Text(stringResource(R.string.cannot_be_undone)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

// ── Private helpers ───────────────────────────────────────────────────────────

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
