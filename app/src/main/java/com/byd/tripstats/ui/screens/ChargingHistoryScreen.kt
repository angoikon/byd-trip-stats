package com.byd.tripstats.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.R
import com.byd.tripstats.ui.components.BrandNavigationBar
import com.byd.tripstats.ui.theme.ChargingYellow
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import com.byd.tripstats.ui.screens.charginghistory.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChargingHistoryScreen(
    viewModel: DashboardViewModel,
    onSessionClick: (Long) -> Unit,
    onNavigateBack: () -> Unit
) {
    val sessions by viewModel.displayedChargingSessions.collectAsState()
    val favouritesOnly by viewModel.chargingFavouritesOnly.collectAsState()
    val socSource by viewModel.socSource.collectAsState()
    val chargingDistances by viewModel.chargingDistances.collectAsState()
    val currencySymbol by viewModel.currencySymbol.collectAsState()
    val electricityPrice by viewModel.electricityPricePerKwh.collectAsState()
    val unitSystem by viewModel.unitSystem.collectAsState()
    val completed = sessions.filter { !it.isActive }.sortedByDescending { it.startTime }
    val active = sessions.filter { it.isActive }.sortedByDescending { it.startTime }

    var selectedSessions by remember { mutableStateOf(setOf<Long>()) }
    var selectionMode by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    val isSplitScreen = com.byd.tripstats.ui.rememberIsSplitScreen()
    val onBackOrCancelSelection: () -> Unit = {
        if (selectionMode) {
            selectionMode = false
            selectedSessions = setOf()
        } else {
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selectionMode) {
                        Text(
                            stringResource(R.string.selected_count, selectedSessions.size),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.charging_history_title),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable(onClick = onBackOrCancelSelection)
                            )
                            // Subtitle hint is dropped in split-screen where there's no room.
                            if (!isSplitScreen) {
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
                    }
                },
                navigationIcon = {
                    BrandNavigationBar {
                    IconButton(onClick = onBackOrCancelSelection) {
                    Icon(
                        imageVector =
                            if (selectionMode) Icons.Filled.Close
                            else Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription =
                            if (selectionMode) stringResource(R.string.cancel) else stringResource(R.string.back),
                        modifier = Modifier.size(32.dp)
                    )
                    }
                    }
                },
                actions = {
                    if (selectionMode && selectedSessions.isNotEmpty()) {
                        IconButton(onClick = { showDeleteSelectedDialog = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.delete_selected_sessions),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else if (!selectionMode) {
                        IconButton(onClick = { viewModel.toggleChargingFavouritesOnly() }) {
                            Icon(
                                imageVector = if (favouritesOnly)
                                    Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = if (favouritesOnly)
                                    stringResource(R.string.show_all_sessions) else "Show favourites only",
                                tint = if (favouritesOnly)
                                    ChargingYellow else LocalContentColor.current,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
            )
        }
    ) { paddingValues ->
        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.BatteryChargingFull,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (favouritesOnly) stringResource(R.string.no_favourite_sessions) else stringResource(R.string.no_charging_sessions),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (favouritesOnly)
                            stringResource(R.string.mark_session_favourite_hint)
                        else
                            stringResource(R.string.sessions_reconstructed_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Active sessions at the top
                items(active, key = { it.id }) { session ->
                    ChargingSessionCard(
                        session = session,
                        isActive = true,
                        isSelected = selectedSessions.contains(session.id),
                        selectionMode = selectionMode,
                        socSource = socSource,
                        onClick = {
                            if (selectionMode) {
                                selectedSessions =
                                    if (selectedSessions.contains(session.id)) {
                                        selectedSessions - session.id
                                    } else {
                                        selectedSessions + session.id
                                    }
                            } else {
                                onSessionClick(session.id)
                            }
                        },
                        onLongClick = {
                            if (!selectionMode) {
                                selectionMode = true
                                selectedSessions = setOf(session.id)
                            }
                        },
                        onToggleFavourite = { viewModel.setChargingFavourite(session.id, !session.isFavourite) },
                        onDelete = { viewModel.deleteChargingSession(session.id) }
                    )
                }

                // Summary header
                if (completed.isNotEmpty()) {
                    item { ChargingStatsSummary(completed, socSource) }
                }

                items(completed, key = { it.id }) { session ->
                    ChargingSessionCard(
                        session = session,
                        isActive = false,
                        isSelected = selectedSessions.contains(session.id),
                        selectionMode = selectionMode,
                        socSource = socSource,
                        distanceSinceLastCharge = chargingDistances[session.id],
                        currencySymbol = currencySymbol,
                        defaultTariff = electricityPrice,
                        unitSystem = unitSystem,
                        onClick = {
                            if (selectionMode) {
                                selectedSessions =
                                    if (selectedSessions.contains(session.id)) {
                                        selectedSessions - session.id
                                    } else {
                                        selectedSessions + session.id
                                    }
                            } else {
                                onSessionClick(session.id)
                            }
                        },
                        onLongClick = {
                            if (!selectionMode) {
                                selectionMode = true
                                selectedSessions = setOf(session.id)
                            }
                        },
                        onToggleFavourite = { viewModel.setChargingFavourite(session.id, !session.isFavourite) },
                        onDelete = { viewModel.deleteChargingSession(session.id) }
                    )
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    if (showDeleteSelectedDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = {
                Text(
                    stringResource(R.string.delete_sessions_title, selectedSessions.size)
                )
            },
            text = {
                Text(
                    stringResource(R.string.delete_sessions_msg)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteChargingSessions(selectedSessions.toList())
                        showDeleteSelectedDialog = false
                        selectionMode = false
                        selectedSessions = setOf()
                    }
                ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
