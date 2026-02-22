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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.ui.viewmodel.DashboardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripHistoryScreen(
    viewModel: DashboardViewModel,
    onTripClick: (Long) -> Unit,
    onNavigateBack: () -> Unit
) {
    val trips by viewModel.allTrips.collectAsState()
    var selectedTrips by remember { mutableStateOf(setOf<Long>()) }
    var selectionMode by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Trip History", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        if (!selectionMode) {
                            Text(
                                "Click trip for more details",
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
                    if (selectionMode && selectedTrips.size >= 2) {
                        IconButton(onClick = { showMergeDialog = true }) {
                            Icon(
                                Icons.Filled.MergeType,
                                contentDescription = "Merge trips",
                                modifier = Modifier.size(24.dp)
                            )
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
                items(trips) { trip ->
                    TripItem(
                        trip = trip,
                        isSelected = selectedTrips.contains(trip.id),
                        selectionMode = selectionMode,
                        isActive = trip.isActive,  // Pass active state
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
    
    // Merge confirmation dialog
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TripItem(
    trip: com.byd.tripstats.data.local.entity.TripEntity,
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
                enabled = !isActive || !selectionMode  // Disable interaction for active trips in selection mode
            ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isActive && selectionMode -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)  // Dimmed for active
                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    enabled = !isActive,  // Disable checkbox for active trips
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTimestamp(trip.startTime),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    // "In Progress" badge for ongoing trips
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
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    InfoChip(
                        icon = Icons.Filled.Route,
                        text = "${String.format("%.1f", trip.distance ?: 0.0)} km"
                    )
                    InfoChip(
                        icon = Icons.Filled.Timer,
                        text = if (trip.endTime == null) "Ongoing..." else formatDuration(trip.duration ?: 0)
                    )
                    trip.efficiency?.let {
                        InfoChip(
                            icon = Icons.Filled.Eco,
                            text = "${String.format("%.1f", it)} kWh / 100km"
                        )
                    }
                }
            }
            
            if (!selectionMode) {
                IconButton(
                    onClick = { showDeleteDialog = true },
                    enabled = !isActive  // Disable delete for active trips
                ) {
                    Icon(
                        Icons.Filled.Delete, 
                        "Delete", 
                        tint = if (isActive) 
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)  // Grayed out
                        else 
                            MaterialTheme.colorScheme.error
                    )
                }
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

@Composable
fun InfoChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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

private fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) {
        String.format("%dh %dm", hours, minutes)
    } else {
        String.format("%dm", minutes)
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val instant = java.time.Instant.ofEpochMilli(timestamp)
    val formatter = java.time.format.DateTimeFormatter
        .ofPattern("MMM dd, yyyy HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
    return formatter.format(instant)
}