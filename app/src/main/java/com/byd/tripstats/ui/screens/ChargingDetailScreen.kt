package com.byd.tripstats.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.R
import com.byd.tripstats.data.preferences.SocSource
import com.byd.tripstats.ui.components.BrandNavigationBar
import com.byd.tripstats.ui.theme.*
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.byd.tripstats.ui.screens.chargingdetail.*

private const val MAX_CHART_RENDER_POINTS = 500

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChargingDetailScreen(
    sessionId: Long,
    viewModel: DashboardViewModel,
    onNavigateBack: () -> Unit
) {
    LaunchedEffect(sessionId) { viewModel.selectSession(sessionId) }
    DisposableEffect(Unit) { onDispose { viewModel.clearSelectedSession() } }

    val session    by viewModel.selectedSession.collectAsState()
    val dataPoints by viewModel.selectedSessionDataPoints.collectAsState()
    val liveTelemetry by viewModel.displayTelemetry.collectAsState()
    val socSource by viewModel.socSource.collectAsState()
    val chargingDistances by viewModel.chargingDistances.collectAsState()
    val electricityPrice by viewModel.electricityPricePerKwh.collectAsState()
    val currencySymbol by viewModel.currencySymbol.collectAsState()
    val unitSystem by viewModel.unitSystem.collectAsState()

    var baseChartPoints by remember { mutableStateOf<List<ChargingChartPoint>>(emptyList()) }
    LaunchedEffect(dataPoints) {
        baseChartPoints = withContext(Dispatchers.Default) {
            dataPoints.map { it.toBaseChartPoint() }
        }
    }
    val powerSummary = remember(session, baseChartPoints) {
        session?.let { buildChargingPowerSummary(it, baseChartPoints) }
    }
    val chartPoints = remember(baseChartPoints) {
        if (baseChartPoints.size <= MAX_CHART_RENDER_POINTS) baseChartPoints
        else {
            val step = baseChartPoints.size / MAX_CHART_RENDER_POINTS
            baseChartPoints.filterIndexed { i, _ -> i % step == 0 }
        }
    }

    var selectedTab by remember { mutableStateOf(0) }
    // Shared across every charging chart (Power, Voltage, Cell V, Temperature) so the Time/SoC
    // choice carries between tabs instead of resetting per chart.
    var xAxisMode by remember { mutableStateOf(ChargingXAxisMode.TIME) }
    val tabs = listOf(
        stringResource(R.string.tab_overview),
        stringResource(R.string.tab_power),
        stringResource(R.string.tab_voltage),
        stringResource(R.string.tab_cell_voltage),
        stringResource(R.string.tab_temperature)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.charging_detail_title), fontSize = 24.sp, fontWeight = FontWeight.Bold,
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
                    session?.takeIf { !it.isActive }?.let { s ->
                        IconButton(onClick = { viewModel.setChargingFavourite(s.id, !s.isFavourite) }) {
                            Icon(
                                imageVector = if (s.isFavourite)
                                    Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = if (s.isFavourite)
                                    stringResource(R.string.remove_favourite_action) else stringResource(R.string.mark_favourite_action),
                                tint = if (s.isFavourite)
                                    ChargingYellow else LocalContentColor.current,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        if (session == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(modifier = Modifier.size(60.dp)) }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(selectedTabIndex = selectedTab, modifier = Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { i, title ->
                    Tab(
                        selected = selectedTab == i,
                        onClick  = { selectedTab = i },
                        text = {
                            Text(
                                title,
                                fontSize   = 14.sp,
                                fontWeight = if (selectedTab == i) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            val isSynthetic = session!!.peakKw == 0.0 && session!!.avgKw == 0.0

            when (selectedTab) {
                0 -> ChargingOverviewTab(
                    session = session!!,
                    dataPoints = chartPoints,
                    powerSummary = powerSummary,
                    socSource = socSource,
                    distanceSinceLastCharge = chargingDistances[session!!.id],
                    defaultTariff = electricityPrice,
                    currencySymbol = currencySymbol,
                    unitSystem = unitSystem,
                    onSavePrice = { rate -> viewModel.saveChargingSessionPrice(session!!.id, rate) }
                )
                1 -> if (session!!.isActive && chartPoints.size < 2) {
                    ActiveChargingPowerTab(
                        latestKw = liveTelemetry?.chargingPower?.takeIf { it > 0.1 }
                            ?: chartPoints.asReversed().firstOrNull { it.chargingPowerKw > 0.1 }?.chargingPowerKw
                    )
                } else {
                    ChargingPowerSocTab(
                        dataPoints  = chartPoints,
                        isSynthetic = isSynthetic,
                        socSource   = socSource,
                        xAxisMode   = xAxisMode,
                        onXAxisModeChange = { xAxisMode = it },
                    )
                }
                2 -> ChargingChartTab(
                    dataPoints    = chartPoints,
                    isSynthetic   = isSynthetic,
                    title         = stringResource(R.string.chart_hv_battery_voltage),
                    yAxisLabel    = "V",
                    lineColor     = BydEcoTealDim,
                    socSource     = socSource,
                    xAxisMode     = xAxisMode,
                    onXAxisModeChange = { xAxisMode = it },
                    valueSelector = { it.batteryTotalVoltageV }
                )
                3 -> ChargingCellVoltageTab(chartPoints, isSynthetic, socSource, xAxisMode) { xAxisMode = it }
                4 -> ChargingTempTab(chartPoints, isSynthetic, socSource, xAxisMode) { xAxisMode = it }
            }
        }
    }
}
