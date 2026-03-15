package com.byd.tripstats.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.data.local.entity.ChargingSessionEntity
import com.byd.tripstats.ui.theme.*
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChargingHistoryScreen(
    viewModel: DashboardViewModel,
    onSessionClick: (Long) -> Unit,
    onNavigateBack: () -> Unit
) {
    val sessions by viewModel.allChargingSessions.collectAsState()
    val completed = sessions.filter { !it.isActive }
    val active    = sessions.firstOrNull { it.isActive }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Charging History", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", modifier = Modifier.size(28.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
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
                        "No charging sessions yet",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Sessions are recorded automatically\nwhen the car is plugged in",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
                // Active session pinned at top
                active?.let { session ->
                    item {
                        ChargingSessionCard(
                            session    = session,
                            isActive   = true,
                            onClick    = { onSessionClick(session.id) }
                        )
                    }
                }

                // Summary header
                if (completed.isNotEmpty()) {
                    item {
                        ChargingStatsSummary(completed)
                    }
                }

                items(completed, key = { it.id }) { session ->
                    ChargingSessionCard(
                        session  = session,
                        isActive = false,
                        onClick  = { onSessionClick(session.id) }
                    )
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

// ── Summary card ──────────────────────────────────────────────────────────────

@Composable
private fun ChargingStatsSummary(sessions: List<ChargingSessionEntity>) {
    val totalKwh    = sessions.sumOf { it.kwhAdded ?: 0.0 }
    val totalSessions = sessions.size
    val peakEver    = sessions.maxOfOrNull { it.peakKw } ?: 0.0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SummaryMetric(
                label = "Sessions",
                value = totalSessions.toString(),
                unit  = ""
            )
            SummaryMetric(
                label = "Total added",
                value = "%.1f".format(totalKwh),
                unit  = "kWh"
            )
            SummaryMetric(
                label = "Peak ever",
                value = "%.0f".format(peakEver),
                unit  = "kW"
            )
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text  = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text       = value,
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color      = RegenGreen
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text  = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }
    }
}

// ── Session card ──────────────────────────────────────────────────────────────

@Composable
private fun ChargingSessionCard(
    session : ChargingSessionEntity,
    isActive: Boolean,
    onClick : () -> Unit
) {
    val dateFmt     = remember { SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault()) }
    val startLabel  = dateFmt.format(Date(session.startTime))
    val durationStr = session.durationSeconds?.let { formatDuration(it) } ?: "In progress…"

    val socText = when {
        session.socEnd != null ->
            "%.0f%%  →  %.0f%%".format(session.socStart, session.socEnd)
        else ->
            "%.0f%%  →  …".format(session.socStart)
    }

    val kwhText = session.kwhAdded?.let { "%.2f kWh".format(it) } ?: "—"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = if (isActive) RegenGreen
                        else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // Header row: date + active badge
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                            text     = "● Charging",
                            style    = MaterialTheme.typography.labelSmall,
                            color    = RegenGreen,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
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
                    tint  = MaterialTheme.colorScheme.onSurfaceVariant
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
                        icon  = Icons.Filled.TrendingUp,
                        label = "Avg %.0f kW".format(session.avgKw),
                        tint  = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
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

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatDuration(seconds: Long): String {
    val h = TimeUnit.SECONDS.toHours(seconds)
    val m = TimeUnit.SECONDS.toMinutes(seconds) % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}