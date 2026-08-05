package com.byd.tripstats.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.preferences.SocSource
import com.byd.tripstats.data.preferences.UnitSystem
import com.byd.tripstats.data.preferences.isImperial
import com.byd.tripstats.ui.components.BrandNavigationBar
import com.byd.tripstats.ui.components.ManageTripTagsDialog
import com.byd.tripstats.ui.components.RouteAnalysisTab
import com.byd.tripstats.ui.components.TagChip
import com.byd.tripstats.ui.components.TripHeatmapsTab
import com.byd.tripstats.ui.theme.*
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import androidx.compose.ui.res.stringResource
import com.byd.tripstats.R
import com.byd.tripstats.ui.screens.tripdetail.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    tripId: Long,
    viewModel: DashboardViewModel,
    onNavigateBack: () -> Unit
) {
    val trip by remember(tripId) { viewModel.getTripDetails(tripId) }.collectAsState()
    val dataPoints by remember(tripId) { viewModel.getTripDataPoints(tripId) }.collectAsState()
    val stats by remember(tripId) { viewModel.getTripStats(tripId) }.collectAsState()
    val tripMetrics by viewModel.tripDisplayMetrics.collectAsState()
    val regenEfficiencyPct = tripMetrics[tripId]?.regenEfficiencyPct
    val tripBlendedRates by viewModel.tripBlendedRates.collectAsState()
    val currencySymbol   by viewModel.currencySymbol.collectAsState()
    val unitSystem       by viewModel.unitSystem.collectAsState()
    val socSource        by viewModel.socSource.collectAsState()
    val selectedCarConfig by viewModel.selectedCarConfig.collectAsState(initial = null)
    val tripTags by remember(tripId) { viewModel.tagsForTrip(tripId) }.collectAsState(initial = emptyList())
    val allTags by viewModel.allTags.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    var showTagDialog by remember { mutableStateOf(false) }
    val tabs = listOf(
        stringResource(R.string.tab_overview),
        stringResource(R.string.tab_charts),
        stringResource(R.string.tab_heatmaps),
        stringResource(R.string.tab_route),
        stringResource(R.string.tab_analysis)
    )

    var dialogData by remember {
        mutableStateOf<Pair<TripEntity, List<TripDataPointEntity>>?>(null)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.nav_trip_details), fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onNavigateBack() }
                    )
                },
                navigationIcon = {
                    BrandNavigationBar {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), modifier = Modifier.size(32.dp))
                        }
                    }
                },
                actions = {
                    trip?.let { currentTrip ->
                        IconButton(
                            onClick = { viewModel.setTripFavourite(currentTrip.id, !currentTrip.isFavourite) }
                        ) {
                            Icon(
                                imageVector = if (currentTrip.isFavourite)
                                    Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = if (currentTrip.isFavourite)
                                    stringResource(R.string.remove_favourite_action) else stringResource(R.string.mark_favourite_action),
                                tint = if (currentTrip.isFavourite)
                                    ChargingYellow else LocalContentColor.current,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            trip?.let { currentTrip ->
                                dialogData = currentTrip to dataPoints.toList()
                            }
                        }
                    ) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = stringResource(R.string.export_trip_data_action),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        if (trip == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(60.dp))
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                TripTagsBar(
                    tags = tripTags,
                    onRemove = { tag -> viewModel.removeTagFromTrip(tripId, tag.id) },
                    onAdd = { showTagDialog = true }
                )

                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    text = title,
                                    fontSize = 18.sp,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when (selectedTab) {
                        0 -> TripOverviewTab(
                            trip = trip!!,
                            stats = stats,
                            dataPoints = dataPoints,
                            selectedCarConfig = selectedCarConfig,
                            regenEfficiencyPct = regenEfficiencyPct,
                            currencySymbol = currencySymbol,
                            unitSystem = unitSystem,
                            socSource = socSource,
                            blendedRate = tripBlendedRates[tripId]
                        )
                        1 -> TripChartsTab(dataPoints = dataPoints, useImperial = unitSystem.isImperial)
                        2 -> TripHeatmapsTab(dataPoints = dataPoints)
                        3 -> TripRouteTab(dataPoints = dataPoints, useImperial = unitSystem.isImperial)
                        4 -> RouteAnalysisTab(
                            trip        = trip,
                            dataPoints  = dataPoints,
                            useImperial = unitSystem.isImperial,
                            socSource   = socSource
                        )
                    }
                }
            }
        }
    }

    dialogData?.let { (capturedTrip, capturedPoints) ->
        ExportDialog(
            trip = capturedTrip,
            dataPoints = capturedPoints,
            onDismiss = { dialogData = null },
            unitSystem = unitSystem,
            socSource = socSource
        )
    }

    if (showTagDialog) {
        val selected = tripTags.map { it.id }.toSet()
        ManageTripTagsDialog(
            allTags = allTags,
            selectedIds = selected,
            onToggle = { tag ->
                if (tag.id in selected) viewModel.removeTagFromTrip(tripId, tag.id)
                else viewModel.addTagToTrip(tripId, tag.id)
            },
            onCreate = { name -> viewModel.addNewTagToTrip(tripId, name) },
            onDismiss = { showTagDialog = false }
        )
    }
}

/** Horizontally-scrolling strip of a trip's tags with an "add tag" affordance. */
@Composable
private fun TripTagsBar(
    tags: List<com.byd.tripstats.data.local.entity.TagEntity>,
    onRemove: (com.byd.tripstats.data.local.entity.TagEntity) -> Unit,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tags.forEach { tag ->
            TagChip(tag = tag, onRemove = { onRemove(tag) })
        }
        AssistChip(
            onClick = onAdd,
            label = { Text(if (tags.isEmpty()) stringResource(R.string.add_tag_action) else stringResource(R.string.tags_label)) },
            leadingIcon = { Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp)) }
        )
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun EditableDetailRow(label: String, value: String, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit", modifier = Modifier.size(18.dp))
            }
        }
    }
}
