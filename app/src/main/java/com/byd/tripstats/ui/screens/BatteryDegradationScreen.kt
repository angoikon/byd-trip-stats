package com.byd.tripstats.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.R
import com.byd.tripstats.data.entitlement.EntitlementManager
import com.byd.tripstats.ui.components.BrandNavigationBar
import com.byd.tripstats.ui.theme.*
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import com.byd.tripstats.util.BatteryHealthReport
import com.byd.tripstats.ui.screens.batterydegrad.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryDegradationScreen(
    viewModel: DashboardViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit = {}
) {
    val data by viewModel.batteryDegradationData.collectAsState()
    val liveSoh by viewModel.liveStatisticSoh.collectAsState()
    val sohExclusion by viewModel.sohExclusion.collectAsState()
    val excludedTripCount by viewModel.sohExcludedTripCount.collectAsState()
    // Decline rate, 80% projection and the exportable report are Pro features.
    val isPro by EntitlementManager.isPro.collectAsState()
    val selectedCar by viewModel.selectedCarConfig.collectAsState(initial = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.battery_degradation_title), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { onNavigateBack() }
                        )
                        VerticalDivider(
                            modifier = Modifier.height(14.dp),
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(stringResource(R.string.soh_over_time_subtitle), fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                  BrandNavigationBar {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back),
                            modifier = Modifier.size(32.dp))
                    }
                  }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (data.size < 2) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Filled.BatteryChargingFull,
                        contentDescription = null,
                        tint = BatteryBlue,
                        modifier = Modifier.size(56.dp)
                    )
                    Text(
                        stringResource(R.string.not_enough_data_yet),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.complete_trips_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    // All (or all but one) trips are legacy and currently excluded — give the
                    // user a way out so they aren't stranded on an empty screen.
                    if (excludedTripCount > 0) {
                        Text(
                            stringResource(R.string.excluded_trips_hidden, excludedTripCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        OutlinedButton(onClick = { viewModel.setSohExclusionOff() }) {
                            Icon(Icons.Filled.Visibility, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.show_all_recorded_data))
                        }
                    }
                }
            }
            return@Scaffold
        }

        // The car's live statistic SoH, so this agrees with the dashboard tile at once; the chart
        // and its trend stay per-trip history. Without a live value, the last trip's average.
        val currentSoh = liveSoh ?: data.last().avgSoh

        // ── Compute regression + projection ───────────────────────────────────
        val regression  = computeLinearRegression(data)
        val stats       = buildDegradationScreenStats(regression)
        val strNotDeclining = stringResource(R.string.soh_not_declining)
        val strFarFuture    = stringResource(R.string.soh_far_future)
        val localizedProjLabel = when (stats.projectedAt80Label) {
            "Not declining" -> strNotDeclining
            "Far future"    -> strFarFuture
            else            -> stats.projectedAt80Label
        }

        // Assemble the report payload from the same data/stats shown on screen.
        fun buildReportData(): BatteryHealthReport.Data {
            val df = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            val declineLabel = if (stats.declinePerYear < 0.01) "< 0.01% / yr"
                else "${"%.2f".format(stats.declinePerYear)}% / yr"
            return BatteryHealthReport.Data(
                vehicleName = selectedCar?.displayName ?: "BYD vehicle",
                batteryKwh = selectedCar?.batteryKwh,
                appVersion = com.byd.tripstats.BuildConfig.VERSION_NAME,
                currentSoh = currentSoh,
                declinePerYearLabel = declineLabel,
                projectedAt80Label = stats.projectedAt80Label,
                firstDate = df.format(Date(data.first().timestamp)),
                lastDate = df.format(Date(data.last().timestamp)),
                tripsAnalyzed = data.size,
                warrantyNote = context.getString(R.string.battery_report_warranty_note),
                exclusionNote = if (excludedTripCount > 0)
                    context.getString(
                        R.string.battery_report_exclusion_note,
                        excludedTripCount,
                        df.format(Date(sohExclusion.cutoffMs ?: 0L))
                    )
                    else null,
                entries = data.map {
                    BatteryHealthReport.Entry(
                        date = df.format(Date(it.timestamp)),
                        odometerKm = it.odometer,
                        soh = it.avgSoh
                    )
                }
            )
        }

        // Generate the report (PDF or HTML) and toast the saved path / any error.
        fun saveReport(asPdf: Boolean) {
            scope.launch {
                try {
                    val d = buildReportData()
                    val name = if (asPdf) BatteryHealthReport.generatePdfAndSave(context, d)
                               else BatteryHealthReport.generateAndSave(context, d)
                    Toast.makeText(
                        context,
                        context.getString(R.string.report_saved_msg, "Download/BydTripStats/$name"),
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.report_failed_msg, e.message ?: ""),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── Summary stat cards ─────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DegradationStatCard(
                    modifier  = Modifier.weight(1f),
                    label     = stringResource(R.string.current_soh_label),
                    value     = "${"%.1f".format(currentSoh)}%",
                    icon      = Icons.Filled.BatteryChargingFull,
                    color     = sohColor(currentSoh)
                )
                DegradationStatCard(
                    modifier  = Modifier.weight(1f),
                    label     = stringResource(R.string.trips_analysed_label),
                    value     = "${data.size}",
                    icon      = Icons.Filled.Route,
                    color     = BatteryBlue
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DegradationStatCard(
                    modifier  = Modifier.weight(1f),
                    label     = stringResource(R.string.decline_rate_label),
                    value     = if (stats.declinePerYear < 0.01) "< 0.01% / yr"
                                else "${"%.2f".format(stats.declinePerYear)}% / yr",
                    icon      = Icons.Filled.Timeline,
                    color     = AccelerationOrange
                )
                DegradationStatCard(
                    modifier  = Modifier.weight(1f),
                    label     = stringResource(R.string.projected_80_label),
                    value     = localizedProjLabel,
                    icon      = Icons.Filled.CalendarMonth,
                    color     = MaterialTheme.colorScheme.primary
                )
            }

            // ── Chart ──────────────────────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(300.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.soh_history_section), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        // Legend
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LegendDot(BatteryBlue,      stringResource(R.string.legend_recorded))
                            LegendDot(AccelerationOrange, stringResource(R.string.legend_trend))
                            LegendDot(AccelerationOrange.copy(alpha = 0.5f), stringResource(R.string.legend_projected))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    DegradationChart(
                        data       = data,
                        regression = regression,
                        modifier   = Modifier.fillMaxSize()
                    )
                }
            }

            // ── Health interpretation card ─────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Info, null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.how_to_read_label), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                    }
                    Text(
                        stringResource(R.string.soh_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider()
                    HealthBand(range = "95 - 100%", label = stringResource(R.string.soh_excellent),
                        color = RegenGreen)
                    HealthBand(range = "90 - 95%",  label = stringResource(R.string.soh_good),
                        color = BatteryBlue)
                    HealthBand(range = "80 - 90%",  label = stringResource(R.string.soh_fair),
                        color = AccelerationOrange)
                    HealthBand(range = "< 80%",     label = stringResource(R.string.soh_poor),
                        color = MaterialTheme.colorScheme.error)
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.soh_warranty_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // ── Battery health report card (Pro) ───────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.Description, null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.battery_health_report_title), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        if (!isPro) {
                            Spacer(Modifier.width(4.dp))
                            ProBadge()
                        }
                    }
                    Text(
                        stringResource(R.string.battery_report_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isPro) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { saveReport(asPdf = true) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.PictureAsPdf, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.btn_pdf))
                            }
                            OutlinedButton(
                                onClick = { saveReport(asPdf = false) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Description, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.btn_html))
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = onNavigateToSettings,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Lock, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.unlock_pro_action))
                        }
                    }
                }
            }

            // ── SoH data range (legacy exclusion) card ─────────────────────────
            val dateFmt = remember {
                java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
            }
            var advancedOpen by remember { mutableStateOf(false) }
            var showShowAllConfirm by remember { mutableStateOf(false) }

            // Apply a change and offer an UNDO snackbar; captures the prior state first.
            fun applyExclusion(change: () -> Unit) {
                val prev = sohExclusion
                change()
                scope.launch {
                    val res = snackbarHostState.showSnackbar(
                        message = context.getString(R.string.data_range_updated_msg),
                        actionLabel = context.getString(R.string.undo),
                        duration = SnackbarDuration.Short
                    )
                    if (res == SnackbarResult.ActionPerformed) viewModel.restoreSohExclusion(prev)
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.FilterList, null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.battery_data_range_section), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                    }
                    val cutoffLabel = sohExclusion.cutoffMs?.let { dateFmt.format(java.util.Date(it)) }
                    val statusText = when (sohExclusion.mode) {
                        DashboardViewModel.SohExclusionMode.AUTO ->
                            if (excludedTripCount > 0)
                                stringResource(R.string.data_range_auto, excludedTripCount, cutoffLabel ?: "")
                            else
                                stringResource(R.string.data_range_none)
                        DashboardViewModel.SohExclusionMode.CUSTOM ->
                            stringResource(R.string.data_range_custom, excludedTripCount, cutoffLabel ?: "")
                        DashboardViewModel.SohExclusionMode.OFF ->
                            stringResource(R.string.data_range_off)
                    }
                    Text(statusText, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(R.string.data_range_info),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    TextButton(onClick = { advancedOpen = !advancedOpen }) {
                        Icon(if (advancedOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (advancedOpen) stringResource(R.string.hide_options_action) else stringResource(R.string.adjust_data_range_action))
                    }
                    if (advancedOpen) {
                        if (sohExclusion.mode != DashboardViewModel.SohExclusionMode.AUTO) {
                            OutlinedButton(
                                onClick = { applyExclusion { viewModel.setSohExclusionAuto() } },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Recommend, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.use_recommended_range))
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                applyExclusion { viewModel.setSohExclusionCutoff(System.currentTimeMillis()) }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.RestartAlt, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.start_fresh_today))
                        }
                        if (sohExclusion.mode != DashboardViewModel.SohExclusionMode.OFF) {
                            OutlinedButton(
                                onClick = { showShowAllConfirm = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Visibility, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.show_all_recorded_data))
                            }
                        }
                    }
                }
            }

            if (showShowAllConfirm) {
                AlertDialog(
                    onDismissRequest = { showShowAllConfirm = false },
                    icon = { Icon(Icons.Filled.Warning, null) },
                    title = { Text(stringResource(R.string.show_all_data_title)) },
                    text = {
                        Text(stringResource(R.string.show_all_data_msg))
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showShowAllConfirm = false
                            applyExclusion { viewModel.setSohExclusionOff() }
                        }) { Text(stringResource(R.string.show_all_btn)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showShowAllConfirm = false }) { Text(stringResource(R.string.cancel)) }
                    }
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
