package com.byd.tripstats.ui.screens.charginghistory

import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.data.local.entity.ChargingSessionEntity
import com.byd.tripstats.data.preferences.SocSource
import com.byd.tripstats.data.preferences.UnitSystem
import com.byd.tripstats.data.preferences.convertDistance
import com.byd.tripstats.data.preferences.distanceUnit
import com.byd.tripstats.ui.theme.AccelerationOrange
import com.byd.tripstats.ui.theme.BatteryBlue
import com.byd.tripstats.ui.theme.ChargingYellow
import com.byd.tripstats.ui.theme.RegenGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ChargingSessionCard(
    session      : ChargingSessionEntity,
    isActive     : Boolean,
    isSelected   : Boolean = false,
    selectionMode: Boolean = false,
    socSource    : SocSource = SocSource.PANEL,
    distanceSinceLastCharge: Double? = null,
    currencySymbol: String = "€",
    defaultTariff: Double = 0.0,
    unitSystem   : UnitSystem = UnitSystem.METRIC,
    onClick      : () -> Unit,
    onLongClick  : () -> Unit = {},
    onToggleFavourite: () -> Unit = {},
    onDelete     : () -> Unit = {}
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault()) }
    val startLabel = dateFmt.format(Date(session.startTime))
    val durationStr =
        if (isActive) {
            formatDuration((System.currentTimeMillis() - session.startTime) / 1000)
        } else {
            session.durationSeconds?.let { formatDuration(it) } ?: "—"
        }

    val usePanelSoc = socSource == SocSource.PANEL && session.socStartPanel > 0.0
    val displaySocStart = if (usePanelSoc) session.socStartPanel else session.socStart
    val displaySocEnd   = if (usePanelSoc) session.socEndPanel else session.socEnd
    val socText =
        when {
            displaySocEnd != null ->
                "%.1f%%  →  %.1f%%".format(displaySocStart, displaySocEnd)
            else -> "%.1f%%  →  …".format(displaySocStart)
        }

    val kwhText = session.kwhAdded?.let { "%.2f kWh".format(it) } ?: "—"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                enabled = !isActive || !selectionMode
            )
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = if (isActive) RegenGreen
                        else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isActive && selectionMode -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.primaryContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // Header row: checkbox + date + active badge + delete icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Checkbox
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

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text  = startLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (isActive) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = RegenGreen.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text     = stringResource(R.string.charging_legend),
                                style    = MaterialTheme.typography.labelSmall,
                                color    = RegenGreen,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                // Favourite star — reserve space; only actionable for completed sessions
                Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    if (!selectionMode && !isActive) {
                        IconButton(
                            onClick = onToggleFavourite,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = if (session.isFavourite)
                                    Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = if (session.isFavourite)
                                    stringResource(R.string.remove_favourite_action) else stringResource(R.string.mark_favourite_action),
                                modifier = Modifier.size(20.dp),
                                tint = if (session.isFavourite)
                                    ChargingYellow
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                // Delete icon
                Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    if (!selectionMode) {
                        IconButton(
                            onClick = { showDeleteDialog = true },
                            enabled = !isActive,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.delete),
                                modifier = Modifier.size(18.dp),
                                tint =
                                    if (isActive)
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = 0.38f
                                        )
                                    else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Metrics row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SessionMetricChip(
                    icon  = Icons.Filled.Timer,
                    label = durationStr,
                    tint  = MaterialTheme.colorScheme.primary
                )
                SessionMetricChip(
                    icon  = Icons.Filled.BatteryChargingFull,
                    label = socText,
                    tint  = BatteryBlue
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SessionMetricChip(
                    icon  = Icons.Filled.ElectricalServices,
                    label = kwhText,
                    tint  = RegenGreen
                )
                if (session.peakKw > 0) {
                    SessionMetricChip(
                        icon  = Icons.Filled.Bolt,
                        label = "Peak %.0f kW".format(session.peakKw),
                        tint  = AccelerationOrange
                    )
                }
                if (session.avgKw > 0) {
                    SessionMetricChip(
                        icon  = Icons.AutoMirrored.Filled.TrendingUp,
                        label = "Avg %.0f kW".format(session.avgKw),
                        tint  = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Synthetic session badge — shown when no real-time data was captured
                if (session.peakKw == 0.0 && session.avgKw == 0.0 && !isActive) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = stringResource(R.string.reconstructed_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = androidx.compose.ui.Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // Distance-since-last-charge + cost. Cost is the real price when set, otherwise the
            // global-tariff estimate (shown with a "~" and muted). Only rendered when known.
            val explicitCost = session.explicitCost
            val estimatedCost = if (explicitCost == null && defaultTariff > 0.0)
                (session.kwhAdded ?: 0.0) * defaultTariff else null
            val displayCost = explicitCost ?: estimatedCost
            val isEstimated = explicitCost == null && estimatedCost != null
            if (!isActive && (distanceSinceLastCharge != null || displayCost != null)) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    distanceSinceLastCharge?.takeIf { it >= 0.0 }?.let { km ->
                        SessionMetricChip(
                            icon  = Icons.Filled.Route,
                            label = stringResource(
                                R.string.since_last_charge_chip,
                                "%.0f %s".format(unitSystem.convertDistance(km), unitSystem.distanceUnit)
                            ),
                            tint  = BatteryBlue
                        )
                    }
                    displayCost?.let { cost ->
                        SessionMetricChip(
                            icon  = Icons.Filled.Payments,
                            label = when {
                                cost <= 0.0 -> stringResource(R.string.free_label)
                                isEstimated -> "~$currencySymbol%.2f".format(cost)
                                else        -> "$currencySymbol%.2f".format(cost)
                            },
                            tint  = when {
                                cost <= 0.0 -> RegenGreen
                                isEstimated -> MaterialTheme.colorScheme.onSurfaceVariant
                                else        -> AccelerationOrange
                            }
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.delete_charging_session_title)) },
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

@Composable
private fun SessionMetricChip(
    icon : androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint : androidx.compose.ui.graphics.Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

private fun formatDuration(seconds: Long): String {
    val h = TimeUnit.SECONDS.toHours(seconds)
    val m = TimeUnit.SECONDS.toMinutes(seconds) % 60
    return if (h > 0) "${h}h ${m}min" else "${m}min"
}
