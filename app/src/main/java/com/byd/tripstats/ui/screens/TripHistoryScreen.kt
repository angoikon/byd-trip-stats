package com.byd.tripstats.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.ui.theme.*
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import com.byd.tripstats.ui.viewmodel.DashboardViewModel.TripFilterState
import com.byd.tripstats.ui.viewmodel.DashboardViewModel.TripSortField
import com.byd.tripstats.ui.viewmodel.DashboardViewModel.TripSortOrder
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripHistoryScreen(
    viewModel: DashboardViewModel,
    onTripClick: (Long) -> Unit,
    onNavigateBack: () -> Unit
) {
    val trips         by viewModel.sortedFilteredTrips.collectAsState()
    val displayMetrics by viewModel.tripDisplayMetrics.collectAsState()
    val sortField     by viewModel.sortField.collectAsState()
    val sortOrder     by viewModel.sortOrder.collectAsState()
    val filterState   by viewModel.filterState.collectAsState()

    var selectedTrips           by remember { mutableStateOf(setOf<Long>()) }
    var selectionMode           by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    var showSortSheet           by remember { mutableStateOf(false) }
    var showFilterSheet         by remember { mutableStateOf(false) }

    val activeFilters = filterState.activeFilterCount

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
                                "(Touch trip for analytics, long-press to select for multiple deletion)",
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
                        Text(
                            text = "${selectedTrips.size} selected",
                            modifier = Modifier.padding(end = 16.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                    } else if (!selectionMode) {
                        // Sort button — icon reflects current direction
                        IconButton(onClick = { showSortSheet = true }) {
                            Icon(
                                imageVector = if (sortOrder == TripSortOrder.DESC)
                                    Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                                contentDescription = "Sort",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        // Filter button — BadgedBox shows active filter count
                        BadgedBox(
                            modifier = Modifier.padding(end = 8.dp),
                            badge = {
                                if (activeFilters > 0) {
                                    Badge { Text("$activeFilters") }
                                }
                            }
                        ) {
                            IconButton(onClick = { showFilterSheet = true }) {
                                Icon(
                                    imageVector = Icons.Filled.FilterList,
                                    contentDescription = "Filter",
                                    tint = if (activeFilters > 0)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        LocalContentColor.current,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
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
                        text = if (activeFilters > 0) "No trips match your filters" else "No trips yet",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = if (activeFilters > 0) "Try adjusting or clearing the filters"
                               else "Start driving to record your first trip!",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
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
                                    selectedTrips = if (selectedTrips.contains(trip.id))
                                        selectedTrips - trip.id
                                    else selectedTrips + trip.id
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

    // ── Delete selected dialog ────────────────────────────────────────────────
    if (showDeleteSelectedDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Delete ${selectedTrips.size} Trip${if (selectedTrips.size > 1) "s" else ""}?") },
            text = { Text("This will permanently delete the selected trips and all their data. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTrips(selectedTrips.toList())
                        showDeleteSelectedDialog = false
                        selectionMode = false
                        selectedTrips = setOf()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) { Text("Cancel") }
            }
        )
    }

    // ── Sort bottom sheet ─────────────────────────────────────────────────────
    if (showSortSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSortSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            SortSheetContent(
                currentField = sortField,
                currentOrder = sortOrder,
                onFieldSelected = { viewModel.setSortField(it) },
                onOrderToggle   = { viewModel.toggleSortOrder() },
                onDismiss       = { showSortSheet = false }
            )
        }
    }

    // ── Filter bottom sheet ───────────────────────────────────────────────────
    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            FilterSheetContent(
                current   = filterState,
                onApply   = { viewModel.setFilter(it); showFilterSheet = false },
                onClear   = { viewModel.clearFilters(); showFilterSheet = false },
                onDismiss = { showFilterSheet = false }
            )
        }
    }
}

// ── Sort sheet ────────────────────────────────────────────────────────────────

@Composable
private fun SortSheetContent(
    currentField: TripSortField,
    currentOrder: TripSortOrder,
    onFieldSelected: (TripSortField) -> Unit,
    onOrderToggle: () -> Unit,
    onDismiss: () -> Unit
) {
    val fields = listOf(
        TripSortField.DATE        to "Date",
        TripSortField.DISTANCE    to "Distance",
        TripSortField.DURATION    to "Duration",
        TripSortField.CONSUMPTION to "Avg Consumption",
        TripSortField.REGEN_EFF   to "Regen Efficiency",
        TripSortField.MAX_SPEED   to "Max Speed"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Sort by", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            // Direction toggle
            FilledTonalButton(onClick = onOrderToggle) {
                Icon(
                    imageVector = if (currentOrder == TripSortOrder.DESC)
                        Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (currentOrder == TripSortOrder.DESC) "Descending" else "Ascending")
            }
        }

        Spacer(Modifier.height(16.dp))

        fields.forEach { (field, label) ->
            val selected = field == currentField
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected,
                    onClick = { onFieldSelected(field); onDismiss() }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

// ── Filter sheet ──────────────────────────────────────────────────────────────

@Composable
private fun FilterSheetContent(
    current: TripFilterState,
    onApply: (TripFilterState) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    // Local mutable draft — only applied when the user taps Apply
    var distMin    by remember { mutableStateOf(current.distanceMin?.toString()    ?: "") }
    var distMax    by remember { mutableStateOf(current.distanceMax?.toString()    ?: "") }
    var durMin     by remember { mutableStateOf(current.durationMin?.toString()    ?: "") }
    var durMax     by remember { mutableStateOf(current.durationMax?.toString()    ?: "") }
    var consMin    by remember { mutableStateOf(current.consumptionMin?.toString() ?: "") }
    var consMax    by remember { mutableStateOf(current.consumptionMax?.toString() ?: "") }
    var regenMin   by remember { mutableStateOf(current.regenEffMin?.toString()    ?: "") }
    var regenMax   by remember { mutableStateOf(current.regenEffMax?.toString()    ?: "") }
    var speedMin   by remember { mutableStateOf(current.maxSpeedMin?.toString()    ?: "") }
    var speedMax   by remember { mutableStateOf(current.maxSpeedMax?.toString()    ?: "") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Filter trips", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        FilterRangeRow("Distance (km)",           distMin,  distMax)  { a, b -> distMin  = a; distMax  = b }
        FilterRangeRow("Duration (min)",           durMin,   durMax)   { a, b -> durMin   = a; durMax   = b }
        FilterRangeRow("Avg Consumption (kWh/100km)", consMin,  consMax)  { a, b -> consMin  = a; consMax  = b }
        FilterRangeRow("Regen Efficiency (%)",     regenMin, regenMax) { a, b -> regenMin = a; regenMax = b }
        FilterRangeRow("Max Speed (km/h)",         speedMin, speedMax) { a, b -> speedMin = a; speedMax = b }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.weight(1f)
            ) { Text("Clear all") }

            Button(
                onClick = {
                    onApply(TripFilterState(
                        distanceMin    = distMin.toFloatOrNull(),
                        distanceMax    = distMax.toFloatOrNull(),
                        durationMin    = durMin.toFloatOrNull(),
                        durationMax    = durMax.toFloatOrNull(),
                        consumptionMin = consMin.toFloatOrNull(),
                        consumptionMax = consMax.toFloatOrNull(),
                        regenEffMin    = regenMin.toFloatOrNull(),
                        regenEffMax    = regenMax.toFloatOrNull(),
                        maxSpeedMin    = speedMin.toFloatOrNull(),
                        maxSpeedMax    = speedMax.toFloatOrNull()
                    ))
                },
                modifier = Modifier.weight(1f)
            ) { Text("Apply") }
        }
    }
}

@Composable
private fun FilterRangeRow(
    label: String,
    minVal: String,
    maxVal: String,
    onValuesChange: (String, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = minVal,
                onValueChange = { onValuesChange(it, maxVal) },
                modifier = Modifier.weight(1f),
                label = { Text("Min") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            Text("–", style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = maxVal,
                onValueChange = { onValuesChange(minVal, it) },
                modifier = Modifier.weight(1f),
                label = { Text("Max") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
        }
    }
}

// ── TripItem ──────────────────────────────────────────────────────────────────

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
                    iconTint = MaterialTheme.colorScheme.secondary,
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
                horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                    iconTint = BydEcoTealDim,
                    value = if (avgSpeedKmh != null) "$avgSpeedKmh km/h" else "—",
                    modifier = Modifier.weight(1f)
                )
                TripMetricChip(
                    icon = Icons.Filled.TrendingUp,
                    label = "Max Speed",
                    iconTint = BydErrorRed,
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
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
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
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
        score >= 60   -> BatteryBlue
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

    val bgColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.05f)
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
                Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = scoreColor)
                Spacer(Modifier.width(3.dp))
                Text("Score", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(2.dp))
            if (score != null) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Start) {
                    Text("$score", style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold, color = scoreColor)
                    Spacer(Modifier.width(3.dp))
                    Text("($grade)", style = MaterialTheme.typography.labelSmall, color = scoreColor)
                }
            } else {
                Text("—", style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.05f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
                Icon(icon, null, Modifier.size(14.dp), tint = iconTint)
                Spacer(Modifier.width(3.dp))
                Text(label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

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