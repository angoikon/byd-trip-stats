package com.byd.tripstats.ui.screens.chargingdetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.data.preferences.SocSource
import com.byd.tripstats.sdk.DiLink5Platform
import com.byd.tripstats.ui.theme.AccelerationOrange
import com.byd.tripstats.ui.theme.RegenGreen

/**
 * Per-cell high/low voltage across a charge, with the high−low spread (cell imbalance) on the
 * crosshair. Cell min/max can be absent early on some firmwares (only the aggregate is reported at
 * first) — [ChargingSeriesChart]'s per-series gap-skip handles that. X-axis toggles time / SoC.
 *
 * DiLink-5 reports no cell voltages, so its sessions would draw an empty chart; they get a note
 * instead. Decided by the data, so a DiLink-5 session that does record them still charts normally.
 */
@Composable
internal fun ChargingCellVoltageTab(
    dataPoints  : List<ChargingChartPoint>,
    isSynthetic : Boolean = false,
    socSource   : SocSource = SocSource.PANEL,
    xAxisMode   : ChargingXAxisMode,
    onXAxisModeChange: (ChargingXAxisMode) -> Unit,
) {
    val noCellVoltages = DiLink5Platform.isDiLink5 && dataPoints.size >= 2 &&
        dataPoints.none { it.batteryCellVoltageMaxV > 0.0 || it.batteryCellVoltageMinV > 0.0 }
    if (noCellVoltages) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.cell_voltage_unavailable_di5),
                style     = MaterialTheme.typography.bodyLarge,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    } else {
        val cellVoltageTitle = stringResource(R.string.charging_chart_cell_voltage)
        val strCellLow  = stringResource(R.string.cell_low_label)
        val strCellHigh = stringResource(R.string.cell_high_label)
        val strXHigh    = stringResource(R.string.crosshair_cell_high)
        val strXLow     = stringResource(R.string.crosshair_cell_low)
        ChargingSeriesChart(
            dataPoints  = dataPoints,
            isSynthetic = isSynthetic,
            title       = cellVoltageTitle,
            yAxisLabel  = "V",
            socSource   = socSource,
            xAxisMode   = xAxisMode,
            onXAxisModeChange = onXAxisModeChange,
            series = listOf(
                ChargingSeriesSpec(strCellLow,  RegenGreen,         2.5f) { it.batteryCellVoltageMinV },
                ChargingSeriesSpec(strCellHigh, AccelerationOrange, 2.5f) { it.batteryCellVoltageMaxV },
            ),
            crosshairFor = { p ->
                val hi = p.batteryCellVoltageMaxV
                val lo = p.batteryCellVoltageMinV
                if (hi > 0.0 && lo > 0.0) {
                    ChargingCrosshairInfo(
                        anchor = hi,
                        lines = listOf(
                            strXHigh.format(hi),
                            strXLow.format(lo),
                            "Δ %.0f mV".format((hi - lo) * 1000),
                        ),
                        color = AccelerationOrange,
                    )
                } else null
            },
        )
    }
}
