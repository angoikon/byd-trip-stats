package com.byd.tripstats.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.data.preferences.SocSource
import com.byd.tripstats.data.preferences.UnitSystem
import com.byd.tripstats.data.repository.MergeEligibility
import com.byd.tripstats.data.repository.MergeResult
import com.byd.tripstats.ui.components.ApplyTagDialog
import com.byd.tripstats.ui.components.BrandNavigationBar
import com.byd.tripstats.ui.theme.*
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import com.byd.tripstats.ui.viewmodel.DashboardViewModel.TripSortOrder
import androidx.compose.ui.res.stringResource
import com.byd.tripstats.R
import com.byd.tripstats.ui.screens.triphistory.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripHistoryScreen(
    viewModel: DashboardViewModel,
    onTripClick: (Long) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToSeasonalAnalysis: () -> Unit = {},
    onNavigateToRoutes: () -> Unit = {},
    onNavigateToTags: () -> Unit = {}
) {
    val trips         by viewModel.sortedFilteredTrips.collectAsState()
    val displayMetrics by viewModel.tripDisplayMetrics.collectAsState()
    val monthlyCosts   by viewModel.monthlyCosts.collectAsState()
    val currencySymbol by viewModel.currencySymbol.collectAsState()
    val unitSystem     by viewModel.unitSystem.collectAsState()
    val socSource      by viewModel.socSource.collectAsState()
    val sortField     by viewModel.sortField.collectAsState()
    val sortOrder     by viewModel.sortOrder.collectAsState()
    val filterState   by viewModel.filterState.collectAsState()
    val allTags       by viewModel.allTags.collectAsState()
    val tripTagsMap   by viewModel.tripTagsMap.collectAsState()

    var selectedTrips           by remember { mutableStateOf(setOf<Long>()) }
    var selectionMode           by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    var showSortSheet           by remember { mutableStateOf(false) }
    var showFilterSheet         by remember { mutableStateOf(false) }
    var showCompareSheet        by remember { mutableStateOf(false) }
    var showMergeDialog         by remember { mutableStateOf(false) }
    var showApplyTagDialog      by remember { mutableStateOf(false) }

    val activeFilters = filterState.activeFilterCount
    val isSplitScreen = com.byd.tripstats.ui.rememberIsSplitScreen()
    val onBackOrCancelSelection: () -> Unit = {
        if (selectionMode) {
            selectionMode = false
            selectedTrips = setOf()
        } else {
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.nav_trip_history), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = onBackOrCancelSelection)
                        )
                        // Subtitle hint is dropped in split-screen where there's no room.
                        if (!selectionMode && !isSplitScreen) {
                            VerticalDivider(
                                modifier = Modifier.height(14.dp),
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(R.string.session_history_hint),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    BrandNavigationBar {
                        IconButton(onClick = onBackOrCancelSelection) {
                            Icon(
                                imageVector = if (selectionMode) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (selectionMode) stringResource(R.string.cancel) else stringResource(R.string.back),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                },
                actions = {
                    if (selectionMode && selectedTrips.size >= 1) {
                        val comparableSelected = selectedTrips.filter { id ->
                            trips.firstOrNull { it.id == id }?.isActive == false
                        }
                        if (comparableSelected.size in 2..3) {
                            IconButton(onClick = {
                                viewModel.loadCompareData(comparableSelected)
                                showCompareSheet = true
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.CompareArrows,
                                    contentDescription = stringResource(R.string.compare_trips_action),
                                    tint = BydElectricAzure,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        if (comparableSelected.size == 2) {
                            IconButton(onClick = { showMergeDialog = true }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.CallMerge,
                                    contentDescription = stringResource(R.string.merge_trips_action),
                                    tint = BydElectricAzure,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        if (comparableSelected.isNotEmpty()) {
                            IconButton(onClick = { showApplyTagDialog = true }) {
                                Icon(
                                    Icons.Filled.LocalOffer,
                                    contentDescription = stringResource(R.string.tag_selected_action),
                                    tint = BydElectricAzure,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        IconButton(onClick = { showDeleteSelectedDialog = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.delete_selected_action),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.selected_count, selectedTrips.size),
                            modifier = Modifier.padding(end = 16.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                    } else if (!selectionMode) {
                        IconButton(onClick = { viewModel.toggleFavouritesOnly() }) {
                            Icon(
                                imageVector = if (filterState.favouritesOnly)
                                    Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = if (filterState.favouritesOnly)
                                    stringResource(R.string.show_all_trips) else stringResource(R.string.show_favourites_only),
                                tint = if (filterState.favouritesOnly)
                                    ChargingYellow else LocalContentColor.current,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(onClick = { showSortSheet = true }) {
                            Icon(
                                imageVector = if (sortOrder == TripSortOrder.DESC)
                                    Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                                contentDescription = stringResource(R.string.sort_label),
                                modifier = Modifier.size(22.dp)
                            )
                        }
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
                                    contentDescription = stringResource(R.string.filter_label),
                                    tint = if (activeFilters > 0)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        LocalContentColor.current,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        IconButton(onClick = onNavigateToTags) {
                            Icon(Icons.Filled.LocalOffer, stringResource(R.string.tags_label), modifier = Modifier.size(22.dp))
                        }
                        IconButton(onClick = onNavigateToRoutes) {
                            Icon(Icons.Filled.Route, stringResource(R.string.recurring_routes_nav), modifier = Modifier.size(22.dp))
                        }
                        IconButton(onClick = onNavigateToSeasonalAnalysis) {
                            Icon(Icons.Filled.WbSunny, stringResource(R.string.seasonal_analysis_nav), modifier = Modifier.size(22.dp))
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
                    val filtersOrFav = activeFilters > 0 || filterState.favouritesOnly
                    Text(
                        text = when {
                            filterState.favouritesOnly && activeFilters == 0 -> stringResource(R.string.no_favourite_trips)
                            filtersOrFav -> stringResource(R.string.no_matching_trips)
                            else -> stringResource(R.string.no_trips_yet)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = when {
                            filterState.favouritesOnly && activeFilters == 0 -> stringResource(R.string.mark_favourite_hint)
                            filtersOrFav -> stringResource(R.string.adjust_filters_hint)
                            else -> stringResource(R.string.start_driving_hint)
                        },
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
                if (monthlyCosts.isNotEmpty()) {
                    item(key = "monthly_cost_summary") {
                        // Monthly Cost card now includes the cost-history bar chart as its visual,
                        // with the per-month numeric breakdown as the expandable detail.
                        MonthlyCostSummaryCard(
                            months         = monthlyCosts,
                            currencySymbol = currencySymbol
                        )
                    }
                }

                items(trips, key = { it.id }) { trip ->
                    val metrics = displayMetrics[trip.id]
                    TripItem(
                        trip = trip,
                        tags = tripTagsMap[trip.id] ?: emptyList(),
                        avgSpeedKmh = metrics?.avgSpeedKmh,
                        tripScore = metrics?.tripScore,
                        regenEfficiencyPct = metrics?.regenEfficiencyPct,
                        tripCost = metrics?.tripCost,
                        currencySymbol = currencySymbol,
                        unitSystem = unitSystem,
                        socSource = socSource,
                        isSelected = selectedTrips.contains(trip.id),
                        selectionMode = selectionMode,
                        isActive = trip.isActive,
                        onClick = {
                            if (selectionMode) {
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
                            if (!selectionMode && !trip.isActive) {
                                selectionMode = true
                                selectedTrips = setOf(trip.id)
                            }
                        },
                        onToggleFavourite = { viewModel.setTripFavourite(trip.id, !trip.isFavourite) },
                        onDelete = { viewModel.deleteTrip(trip.id) }
                    )
                }
            }
        }
    }

    // ── Compare bottom sheet ─────────────────────────────────────────────────
    if (showCompareSheet) {
        val comparableSelected = selectedTrips.filter { id ->
            trips.firstOrNull { it.id == id }?.isActive == false
        }
        val compareTrips = trips.filter { it.id in comparableSelected }
        TripCompareSheet(
            trips       = compareTrips,
            viewModel   = viewModel,
            onDismiss   = {
                showCompareSheet = false
                viewModel.clearCompareData()
            }
        )
    }

    // ── Delete selected dialog ────────────────────────────────────────────────
    if (showDeleteSelectedDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(if (selectedTrips.size > 1) stringResource(R.string.delete_trips_plural, selectedTrips.size) else stringResource(R.string.delete_trip_single)) },
            text = { Text(stringResource(R.string.delete_trips_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTrips(selectedTrips.toList())
                        showDeleteSelectedDialog = false
                        selectionMode = false
                        selectedTrips = setOf()
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // ── Merge selected dialog ─────────────────────────────────────────────────
    if (showMergeDialog) {
        val context = LocalContext.current
        val mergeIds = selectedTrips.filter { id ->
            trips.firstOrNull { it.id == id }?.isActive == false
        }
        val tripA = trips.firstOrNull { it.id == mergeIds.getOrNull(0) }
        val tripB = trips.firstOrNull { it.id == mergeIds.getOrNull(1) }
        val eligibility = if (tripA != null && tripB != null)
            viewModel.checkMergeEligibility(tripA, tripB)
        else
            MergeEligibility(false, "Select exactly two completed trips to merge")

        AlertDialog(
            onDismissRequest = { showMergeDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.merge_trips_title)) },
            text = {
                Text(
                    if (eligibility.eligible)
                        stringResource(R.string.merge_trips_msg)
                    else
                        eligibility.reason ?: "These trips can't be merged."
                )
            },
            confirmButton = {
                if (eligibility.eligible) {
                    TextButton(
                        onClick = {
                            viewModel.mergeTrips(mergeIds) { result ->
                                val msg = when (result) {
                                    is MergeResult.Success -> "Trips merged"
                                    is MergeResult.Failure -> result.reason
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                            showMergeDialog = false
                            selectionMode = false
                            selectedTrips = setOf()
                        }
                    ) {
                        Text(stringResource(R.string.merge), color = BydElectricAzure)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showMergeDialog = false }) {
                    Text(if (eligibility.eligible) stringResource(R.string.cancel) else "OK")
                }
            }
        )
    }

    // ── Apply-tag dialog (bulk) ───────────────────────────────────────────────
    if (showApplyTagDialog) {
        val taggable = selectedTrips.filter { id ->
            trips.firstOrNull { it.id == id }?.isActive == false
        }
        ApplyTagDialog(
            allTags = allTags,
            onPick = { tag ->
                viewModel.applyTagToTrips(tag.id, taggable)
                showApplyTagDialog = false
                selectionMode = false
                selectedTrips = setOf()
            },
            onCreate = { name ->
                viewModel.addNewTagToTrips(taggable, name)
                showApplyTagDialog = false
                selectionMode = false
                selectedTrips = setOf()
            },
            onDismiss = { showApplyTagDialog = false }
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
                current     = filterState,
                unitSystem  = unitSystem,
                allTags     = allTags,
                onApply     = { viewModel.setFilter(it); showFilterSheet = false },
                onClear     = { viewModel.clearFilters(); showFilterSheet = false }
            )
        }
    }
}
