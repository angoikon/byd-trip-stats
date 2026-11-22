package com.byd.tripstats.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.data.entitlement.EntitlementManager
import com.byd.tripstats.data.preferences.ClassicCardColumns
import com.byd.tripstats.data.preferences.ClassicChartSide
import com.byd.tripstats.data.preferences.DashboardLayout
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.ThemeMode
import com.byd.tripstats.ui.components.BrandSwitch
import kotlinx.coroutines.launch

/**
 * Preferences → Appearance: theme, dashboard layout, Classic's chart side and card columns, and the
 * dashboard's icons & animations.
 */
@Composable
internal fun AppearanceSection(
    preferencesManager: PreferencesManager,
    onNavigateToProTab: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val dashboardIconsEnabled by preferencesManager.dashboardAnimationsEnabled.collectAsState(
        initial = preferencesManager.getCachedAnimationsEnabled()
    )
    val themeMode by preferencesManager.themeMode.collectAsState(
        initial = preferencesManager.getCachedThemeMode()
    )
    val dashboardLayout by preferencesManager.dashboardLayout.collectAsState(
        initial = preferencesManager.getCachedDashboardLayout()
    )
    val classicChartSide by preferencesManager.classicChartSide.collectAsState(
        initial = preferencesManager.getCachedClassicChartSide()
    )
    val classicCardColumns by preferencesManager.classicCardColumns.collectAsState(
        initial = preferencesManager.getCachedClassicCardColumns()
    )
    val isPro by EntitlementManager.isPro.collectAsState()

    SectionHeader(icon = Icons.Filled.Palette, title = stringResource(R.string.pref_appearance_title))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.pref_theme),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.theme_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                buildList {
                    add(ThemeMode.SYSTEM to stringResource(R.string.theme_system))
                    add(ThemeMode.LIGHT to stringResource(R.string.theme_light))
                    add(ThemeMode.DARK to stringResource(R.string.theme_dark))
                    // Neon is a Pro, dark-only theme — always shown, but locked until unlocked.
                    add(ThemeMode.NEON to stringResource(R.string.theme_neon))
                }.forEach { (mode, label) ->
                    val locked = mode == ThemeMode.NEON && !isPro
                    val selected = themeMode == mode
                    Button(
                        onClick = {
                            if (locked) onNavigateToProTab()
                            else scope.launch { preferencesManager.saveThemeMode(mode) }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .then(if (locked) Modifier.alpha(0.55f) else Modifier),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selected)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        if (locked) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(label, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.pref_dashboard_layout),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    ProBadge()
                }
                // Locked — tapping the lock opens the Pro tab (a preview lives there).
                if (!isPro) {
                    IconButton(onClick = onNavigateToProTab) {
                        Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.unlock_pro_action))
                    }
                }
            }
            Text(
                stringResource(R.string.dashboard_layout_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    DashboardLayout.CLASSIC to stringResource(R.string.dashboard_layout_classic),
                    DashboardLayout.CARDS to stringResource(R.string.dashboard_layout_cards),
                ).forEach { (layout, label) ->
                    // Cards is a Pro layout — always shown, but locked until unlocked.
                    val locked = layout == DashboardLayout.CARDS && !isPro
                    val selected = dashboardLayout == layout
                    Button(
                        onClick = {
                            if (locked) onNavigateToProTab()
                            else scope.launch { preferencesManager.saveDashboardLayout(layout) }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .then(if (locked) Modifier.alpha(0.55f) else Modifier),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selected)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        if (locked) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(label, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }

    // Only while Classic is the layout in use; Cards arranges its own tiles.
    if (!(isPro && dashboardLayout == DashboardLayout.CARDS)) {
        // The buttons follow the Row's reading direction, as the dashboard does, so the one on
        // the left of the screen is always labelled Left (START in English, END in Hebrew).
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val left = stringResource(R.string.chart_side_left)
        val right = stringResource(R.string.chart_side_right)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.pref_chart_side),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.chart_side_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChoiceButtons(
                    options = listOf(
                        ClassicChartSide.START to if (rtl) right else left,
                        ClassicChartSide.END to if (rtl) left else right,
                    ),
                    selected = classicChartSide,
                    onSelect = { side -> scope.launch { preferencesManager.saveClassicChartSide(side) } }
                )

                // Full width only: split screen and a phone always stack the cards in one column.
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                Text(
                    stringResource(R.string.pref_card_columns),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.card_columns_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChoiceButtons(
                    options = listOf(
                        ClassicCardColumns.ONE to stringResource(R.string.card_columns_one),
                        ClassicCardColumns.TWO to stringResource(R.string.card_columns_two),
                    ),
                    selected = classicCardColumns,
                    onSelect = { columns -> scope.launch { preferencesManager.saveClassicCardColumns(columns) } }
                )
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        stringResource(R.string.pref_dashboard_icons),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.dashboard_icons_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                BrandSwitch(
                    checked = dashboardIconsEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            preferencesManager.saveDashboardAnimationsEnabled(enabled)
                        }
                    },
                )
            }
        }
    }
}

/** A row of equal-width buttons, one per option, the selected one in the primary colour. */
@Composable
private fun <T> ChoiceButtons(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (option, label) ->
            val isSelected = selected == option
            Button(
                onClick = { onSelect(option) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSelected)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (isSelected)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(label, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}
