package com.byd.tripstats.ui.screens.triphistory

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.byd.tripstats.ui.theme.AccelerationOrange
import com.byd.tripstats.ui.viewmodel.DashboardViewModel

/** Above this many months the bars would cram, so switch to fixed-width bars that scroll. */
private const val SCROLL_THRESHOLD = 8
private val SCROLL_BAR_WIDTH = 40.dp
private val BAR_MAX_HEIGHT = 100.dp

/**
 * Monthly electricity-cost bars — the cost analogue of the kWh-consumption charts, embedded as the
 * visual header of [MonthlyCostSummaryCard] (which supplies the title, total/avg and the numeric
 * breakdown). Bars run oldest→newest (left→right), each proportional to that month's total cost.
 *
 * Up to [SCROLL_THRESHOLD] months the bars share the width evenly; beyond that (the source caps at
 * 13 months for year-over-year comparison) they take a fixed width and the strip scrolls
 * horizontally, so a full year never crams into unreadable slivers.
 * Reads override-aware costs from [DashboardViewModel.monthlyCosts].
 */
@Composable
internal fun CostHistoryBars(
    months         : List<DashboardViewModel.MonthlyCost>,
    currencySymbol : String
) {
    if (months.size < 2) return
    // monthlyCosts is delivered newest-first; a left→right time axis wants oldest-first.
    val chronological = months.asReversed()
    val maxCost = chronological.maxOf { it.costAmount }.coerceAtLeast(0.01)

    if (chronological.size > SCROLL_THRESHOLD) {
        // Fixed-width bars in a horizontally scrollable strip; the two rows scroll together.
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                chronological.forEach { CostBar(it, maxCost, currencySymbol, Modifier.width(SCROLL_BAR_WIDTH)) }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chronological.forEach { CostBarLabel(it, Modifier.width(SCROLL_BAR_WIDTH)) }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                chronological.forEach { CostBar(it, maxCost, currencySymbol, Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                chronological.forEach { CostBarLabel(it, Modifier.weight(1f)) }
            }
        }
    }
}

/** A value label + bar. Absolute bar height (not fillMaxHeight) so the value label above always
 *  has room — the tallest bar can't eat the column and squeeze out its own label. */
@Composable
private fun CostBar(
    month          : DashboardViewModel.MonthlyCost,
    maxCost        : Double,
    currencySymbol : String,
    modifier       : Modifier
) {
    val fraction = (month.costAmount / maxCost).toFloat().coerceIn(0f, 1f)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        Text(
            "$currencySymbol${"%.0f".format(month.costAmount)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        // Floor of ~2 % so a near-zero month is still a visible sliver.
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(BAR_MAX_HEIGHT * fraction.coerceAtLeast(0.02f))
                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                .background(AccelerationOrange.copy(alpha = 0.85f))
        )
    }
}

@Composable
private fun CostBarLabel(month: DashboardViewModel.MonthlyCost, modifier: Modifier) {
    Text(
        month.label.substringBefore(' '),   // "Mar 2026" → "Mar"
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        textAlign = TextAlign.Center
    )
}
