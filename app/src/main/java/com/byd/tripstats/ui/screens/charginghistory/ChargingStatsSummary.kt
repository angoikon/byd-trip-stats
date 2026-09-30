package com.byd.tripstats.ui.screens.charginghistory

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.byd.tripstats.ui.theme.RegenGreen

@Composable
internal fun ChargingStatsSummary(
    sessions : List<ChargingSessionEntity>,
    socSource: SocSource = SocSource.PANEL,
) {
    val totalKwh      = sessions.sumOf { it.kwhAdded ?: 0.0 }
    val totalSessions = sessions.size
    val panelAvg = if (socSource == SocSource.PANEL) {
        sessions.mapNotNull { it.socPanelDelta.takeIf { d -> d != null && it.socStartPanel > 0.0 } }
            .takeIf { it.isNotEmpty() }?.average()
    } else null
    val avgSocDelta = panelAvg
        ?: sessions.mapNotNull { it.socDelta }.takeIf { it.isNotEmpty() }?.average()
        ?: 0.0
    // Panel in whole percent, BMS with its decimal — by the reading the average came from, since
    // Panel falls back to BMS when no session recorded a panel figure.
    val avgSocFmt = if (panelAvg != null) "%.0f" else "%.1f"

    Card(
        modifier =
            Modifier.fillMaxWidth()
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(12.dp)
                ),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SummaryMetric(label = stringResource(R.string.charging_sessions_label), value = totalSessions.toString(), unit = "")
            SummaryMetric(label = stringResource(R.string.total_added_label), value = "%.1f".format(totalKwh), unit = "kWh")
            SummaryMetric(label = stringResource(R.string.avg_soc_gain_label), value = avgSocFmt.format(avgSocDelta), unit = "%")
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = RegenGreen
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }
    }
}
