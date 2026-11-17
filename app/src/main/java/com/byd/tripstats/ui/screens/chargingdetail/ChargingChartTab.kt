package com.byd.tripstats.ui.screens.chargingdetail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.R
import com.byd.tripstats.data.preferences.OffStateMode
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.SocSource

/**
 * Single-series charging chart (e.g. HV pack voltage). Thin wrapper over [ChargingSeriesChart],
 * which supplies the time/SoC x-axis toggle, gap-skipping and area fill.
 */
@Composable
internal fun ChargingChartTab(
    dataPoints    : List<ChargingChartPoint>,
    isSynthetic   : Boolean = false,
    title         : String,
    yAxisLabel    : String,
    lineColor     : Color,
    socSource     : SocSource = SocSource.PANEL,
    xAxisMode     : ChargingXAxisMode,
    onXAxisModeChange: (ChargingXAxisMode) -> Unit,
    valueSelector : (ChargingChartPoint) -> Double
) {
    ChargingSeriesChart(
        dataPoints   = dataPoints,
        isSynthetic  = isSynthetic,
        title        = title,
        yAxisLabel   = yAxisLabel,
        socSource    = socSource,
        xAxisMode    = xAxisMode,
        onXAxisModeChange = onXAxisModeChange,
        series       = listOf(ChargingSeriesSpec(title, lineColor, 3f, valueSelector)),
        crosshairFor = { p ->
            valueSelector(p).takeIf { it > 0.0 }?.let {
                ChargingCrosshairInfo(it, listOf("%.1f %s".format(it, yAxisLabel)), lineColor)
            }
        },
    )
}

// ── Shared helpers (used across the charging-detail charts) ───────────────────

@Composable
internal fun ChartEmptyState(isSynthetic: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            if (isSynthetic) {
                Text("⚡", fontSize = 40.sp)
                Text(
                    stringResource(R.string.reconstructed_session_label),
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.reconstructed_session_desc),
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                // A charge is reconstructed because the app wasn't running through it, not because
                // the car was off: with Always On a parked charge is recorded live. Only Deep Sleep
                // (DiLink-3; DiLink-5 always runs Always On) is worth pointing at.
                val context = LocalContext.current
                val deepSleep = remember {
                    PreferencesManager(context).getCachedOffStateMode() == OffStateMode.DEEP_SLEEP
                }
                if (deepSleep) {
                    Text(
                        stringResource(R.string.reconstructed_session_deep_sleep_hint),
                        style     = MaterialTheme.typography.bodyMedium,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Text(
                    stringResource(R.string.not_enough_data_label),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun ChartLegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(20.dp, 3.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 3f)
        }
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
