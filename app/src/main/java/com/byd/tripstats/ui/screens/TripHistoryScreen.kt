package com.byd.tripstats.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.ui.theme.*
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripHistoryScreen(
    viewModel: DashboardViewModel,
    onTripClick: (Long) -> Unit,
    onNavigateBack: () -> Unit
) {
    val trips by viewModel.allTrips.collectAsState()
    val displayMetrics by viewModel.tripDisplayMetrics.collectAsState()
    var selectedTrips by remember { mutableStateOf(setOf<Long>()) }
    var selectionMode by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Trip History", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        if (!selectionMode) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "(Touch trip for analytics, long-press to select for merging / multiple deletion)",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectionMode) {
                            selectionMode = false
                            selectedTrips = setOf()
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(
                            imageVector = if (selectionMode) Icons.Filled.Close else Icons.Filled.ArrowBack,
                            contentDescription = if (selectionMode) "Cancel" else "Back",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                },
                actions = {
                    if (selectionMode && selectedTrips.size >= 1) {
                        IconButton(onClick = { showDeleteSelectedDialog = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete selected trips",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        if (selectedTrips.size >= 2) {
                            IconButton(onClick = { showMergeDialog = true }) {
                                Icon(
                                    Icons.Filled.MergeType,
                                    contentDescription = "Merge trips",
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = "${selectedTrips.size} selected",
                            modifier = Modifier.padding(end = 16.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                    } else if (!selectionMode) {
                        TextButton(onClick = {
                            selectionMode = true
                            selectedTrips = setOf()
                        }) {
                            Text("Merge trips")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        if (trips.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.DirectionsCar,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No trips yet",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Start driving to record your first trip!",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(trips, key = { it.id }) { trip ->
                    val metrics = displayMetrics[trip.id]
                    TripItem(
                        trip = trip,
                        avgSpeedKmh = metrics?.avgSpeedKmh,
                        tripScore = metrics?.tripScore,
                        regenEfficiencyPct = metrics?.regenEfficiencyPct,
                        isSelected = selectedTrips.contains(trip.id),
                        selectionMode = selectionMode,
                        isActive = trip.isActive,
                        onClick = {
                            if (selectionMode) {
                                // Don't allow selecting active trips
                                if (!trip.isActive) {
                                    selectedTrips = if (selectedTrips.contains(trip.id)) {
                                        selectedTrips - trip.id
                                    } else {
                                        selectedTrips + trip.id
                                    }
                                }
                            } else {
                                onTripClick(trip.id)
                            }
                        },
                        onLongClick = {
                            // Don't allow long-press on active trips
                            if (!selectionMode && !trip.isActive) {
                                selectionMode = true
                                selectedTrips = setOf(trip.id)
                            }
                        },
                        onDelete = { viewModel.deleteTrip(trip.id) }
                    )
                }
            }
        }
    }

    if (showMergeDialog) {
        AlertDialog(
            onDismissRequest = { showMergeDialog = false },
            title = { Text("Merge ${selectedTrips.size} Trips?") },
            text = {
                Text("This will combine the selected trips into a single trip. All data points will be preserved. This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.mergeTrips(selectedTrips.toList())
                        showMergeDialog = false
                        selectionMode = false
                        selectedTrips = setOf()
                    }
                ) {
                    Text("Merge")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMergeDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteSelectedDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = { Text("Delete ${selectedTrips.size} Trip${if (selectedTrips.size > 1) "s" else ""}?") },
            text = { Text("This will permanently delete the selected trips and all their data. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedTrips.forEach { viewModel.deleteTrip(it) }
                        showDeleteSelectedDialog = false
                        selectionMode = false
                        selectedTrips = setOf()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TripItem(
    trip: com.byd.tripstats.data.local.entity.TripEntity,
    avgSpeedKmh: Int?,
    tripScore: Int?,
    regenEfficiencyPct: Double?,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    isActive: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
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
            )            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isActive && selectionMode -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header row: checkbox (left) + date + "In Progress" badge + delete (right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Checkbox - always reserve space so date doesn't shift
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (trip.endTime != null)
                            "${formatTimestamp(trip.startTime)}  ->  ${formatTimestamp(trip.endTime!!)}   | "
                        else
                            formatTimestamp(trip.startTime),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
                            text = "In Progress",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                // Delete icon - always reserve space so date doesn't shift
                Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    if (!selectionMode) {
                        IconButton(
                            onClick = { showDeleteDialog = true },
                            enabled = !isActive,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                "Delete",
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

            Spacer(modifier = Modifier.height(10.dp))

            // ── Row 1: Distance | Duration | Avg Consumption
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TripMetricChip(
                    icon = Icons.Filled.Route,
                    label = "Distance",
                    value = "${String.format("%.1f", trip.distance ?: 0.0)} km",
                    modifier = Modifier.weight(1f)
                )
                TripMetricChip(
                    icon = Icons.Filled.Timer,
                    label = "Duration",
                    value = if (trip.endTime == null) "Ongoing…"
                            else formatDuration(trip.duration ?: 0),
                    modifier = Modifier.weight(1f)
                )
                TripMetricChip(
                    icon = Icons.Filled.Eco,
                    label = "Avg Consumption",
                    value = trip.efficiency
                        ?.let { "${String.format("%.1f", it)} kWh/100" } ?: "—",
                    iconTint = RegenGreen,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Row 2: Energy | Max Regen | Regeneration Efficiency
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TripMetricChip(
                    icon = Icons.Filled.BatteryChargingFull,
                    label = "Energy consumed",
                    value = trip.energyConsumed
                        ?.let { "${String.format("%.2f", it)} kWh" } ?: "—",
                    iconTint = AccelerationOrange,
                    modifier = Modifier.weight(1f)
                )
                TripMetricChip(
                    icon = Icons.Filled.BatteryChargingFull,
                    label = "Max Regen",
                    value = "${abs(trip.maxRegenPower).toInt()} kW",
                    iconTint = RegenGreen,
                    modifier = Modifier.weight(1f)
                )
                TripMetricChip(
                    icon = Icons.Filled.VolunteerActivism,
                    label = "Regen Eff.",
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
                    label = "Avg Speed",
                    value = if (avgSpeedKmh != null) "$avgSpeedKmh km/h" else "—",
                    modifier = Modifier.weight(1f)
                )
                TripMetricChip(
                    icon = Icons.Filled.TrendingUp,
                    label = "Max Speed",
                    value = "${trip.maxSpeed.toInt()} km/h",
                    modifier = Modifier.weight(1f)
                )
                TripMetricChip(
                    icon = Icons.Filled.Battery4Bar,
                    label = "SOC",
                    value = if (trip.endSoc != null)
                        "${trip.startSoc.toInt()}%-> ${trip.endSoc!!.toInt()}%"
                    else "—",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Trip?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Score chip - displays trip score 0-100 with colour feedback.
 * Green >=80, Yellow 60-79, Orange 40-59, Red <40
 */
@Composable
fun ScoreChip(
    score: Int?,
    modifier: Modifier = Modifier
) {
    val scoreColor = when {
        score == null -> MaterialTheme.colorScheme.onSurfaceVariant
        score >= 80   -> RegenGreen
        score >= 60   -> Color(0xFFFFDD00)
        score >= 40   -> AccelerationOrange
        else          -> BydErrorRed
    }
    val grade = when {
        score == null -> "—"
        score >= 80   -> "A"
        score >= 60   -> "B"
        score >= 40   -> "C"
        else          -> "D"
    }

    val bgColor = MaterialTheme.colorScheme.surface
    val shape = MaterialTheme.shapes.small
    Box(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = scoreColor
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "Score",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            if (score != null) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "$score",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scoreColor
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "($grade)",
                        style = MaterialTheme.typography.labelSmall,
                        color = scoreColor
                    )
                }
            } else {
                Text(
                    text = "—",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Compact labelled metric cell used inside TripItem rows.
 * Uses Box + background instead of Surface to avoid unnecessary composition overhead
 * when rendering many chips inside a LazyColumn.
 */
@Composable
fun TripMetricChip(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary
) {
    val bgColor = MaterialTheme.colorScheme.surface
    val shape = MaterialTheme.shapes.small
    Box(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = iconTint
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ── Legacy InfoChip kept for potential reuse elsewhere ─────────────────────

@Composable
fun InfoChip(
    icon: ImageVector,
    text: String
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) String.format("%dh %dm", hours, minutes)
    else String.format("%dm", minutes)
}

private fun formatTimestamp(timestamp: Long): String {
    val instant = java.time.Instant.ofEpochMilli(timestamp)
    val formatter = java.time.format.DateTimeFormatter
        .ofPattern("MMM dd, yyyy HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
    return formatter.format(instant)
}