package com.byd.tripstats.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.byd.tripstats.R
import com.byd.tripstats.data.entitlement.EntitlementManager
import com.byd.tripstats.data.preferences.DashboardLayout
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.SocSource
import com.byd.tripstats.data.preferences.ThemeMode
import com.byd.tripstats.data.preferences.UnitSystem
import com.byd.tripstats.data.preferences.consumptionUnit
import com.byd.tripstats.data.preferences.convertDistance
import com.byd.tripstats.data.preferences.convertEfficiency
import com.byd.tripstats.data.preferences.distanceUnit
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import com.byd.tripstats.util.LocaleHelper

/**
 * Settings → Preferences: how the app looks, and how it records and prices what the car does.
 *
 * Laid out like Connections — an overview of cards, each opening on its own page — instead of one
 * scroll of eleven cards under four group labels. Goals already had a screen of its own, so its
 * card goes straight there.
 */
private object PreferencesPage {
    const val APPEARANCE = "appearance"
    const val LANGUAGE_UNITS = "language_units"
    const val TRIP_RECORDING = "trip_recording"
    const val BATTERY = "battery"
    const val COSTS = "costs"
    const val GOALS = "goals"
}

@Composable
internal fun AppPreferencesTab(
    viewModel: DashboardViewModel,
    preferencesManager: PreferencesManager,
    onNavigateToTripGoals: () -> Unit,
    onNavigateToProTab: () -> Unit = {}
) {
    val context = LocalContext.current

    SettingsHub(
        icon = Icons.Filled.Tune,
        title = stringResource(R.string.settings_tab_preferences),
        description = stringResource(R.string.settings_overview_desc),
        cards = {
            val themeMode by preferencesManager.themeMode.collectAsState(
                initial = preferencesManager.getCachedThemeMode()
            )
            val dashboardLayout by preferencesManager.dashboardLayout.collectAsState(
                initial = preferencesManager.getCachedDashboardLayout()
            )
            val carOffTimeoutMinutes by preferencesManager.carOffTimeoutMinutes.collectAsState(
                initial = preferencesManager.getCachedCarOffTimeoutMinutes()
            )
            val minTripDistanceKm by preferencesManager.minTripDistanceKm.collectAsState(
                initial = preferencesManager.getCachedMinTripDistanceKm()
            )
            val socSource by preferencesManager.socSource.collectAsState(
                initial = preferencesManager.getCachedSocSource()
            )
            val cellImbalanceAlertEnabled by preferencesManager.cellImbalanceAlertEnabled.collectAsState(
                initial = preferencesManager.getCachedCellImbalanceAlertEnabled()
            )
            val isPro by EntitlementManager.isPro.collectAsState()
            val unitSystem by viewModel.unitSystem.collectAsState()
            val electricityPrice by viewModel.electricityPricePerKwh.collectAsState()
            val currencySymbol by viewModel.currencySymbol.collectAsState()
            val tripGoals by viewModel.tripGoals.collectAsState()

            val themeLabel = stringResource(
                when (themeMode) {
                    ThemeMode.SYSTEM -> R.string.theme_system
                    ThemeMode.LIGHT  -> R.string.theme_light
                    ThemeMode.DARK   -> R.string.theme_dark
                    ThemeMode.NEON   -> R.string.theme_neon
                }
            )
            val layoutLabel = stringResource(
                when (dashboardLayout) {
                    DashboardLayout.CLASSIC -> R.string.dashboard_layout_classic
                    DashboardLayout.CARDS   -> R.string.dashboard_layout_cards
                }
            )
            val unitsLabel = stringResource(
                if (unitSystem == UnitSystem.IMPERIAL) R.string.units_imperial else R.string.units_metric
            )
            val socLabel = stringResource(
                if (socSource == SocSource.BMS) R.string.soc_bms_option else R.string.soc_panel_option
            )

            listOf(
                SettingsHubCard(
                    key = PreferencesPage.APPEARANCE,
                    icon = Icons.Filled.Palette,
                    title = stringResource(R.string.pref_appearance_title),
                    body = stringResource(R.string.pref_appearance_desc),
                    statusLine = "$themeLabel • $layoutLabel"
                ),
                SettingsHubCard(
                    key = PreferencesPage.LANGUAGE_UNITS,
                    icon = Icons.Filled.Translate,
                    title = stringResource(R.string.pref_language_units_title),
                    body = stringResource(R.string.pref_language_units_desc),
                    statusLine = "${LocaleHelper.displayNameForTag(LocaleHelper.getSelectedTag(context))} • $unitsLabel"
                ),
                SettingsHubCard(
                    key = PreferencesPage.TRIP_RECORDING,
                    icon = Icons.Filled.Timer,
                    title = stringResource(R.string.pref_trip_recording_title),
                    body = stringResource(R.string.pref_trip_recording_desc),
                    statusLine = if (minTripDistanceKm > 0.0) {
                        stringResource(
                            R.string.trip_recording_status,
                            carOffTimeoutMinutes,
                            "%.2f %s".format(unitSystem.convertDistance(minTripDistanceKm), unitSystem.distanceUnit)
                        )
                    } else {
                        stringResource(R.string.trip_recording_status_no_min, carOffTimeoutMinutes)
                    }
                ),
                SettingsHubCard(
                    key = PreferencesPage.BATTERY,
                    icon = Icons.Filled.BatteryChargingFull,
                    title = stringResource(R.string.pref_battery_title),
                    body = stringResource(R.string.pref_battery_desc),
                    statusLine = stringResource(
                        // The saved switch outlives a lapsed licence; the alert only runs with Pro.
                        if (isPro && cellImbalanceAlertEnabled) R.string.battery_status_alert_on
                        else R.string.battery_status_alert_off,
                        socLabel
                    )
                ),
                SettingsHubCard(
                    key = PreferencesPage.COSTS,
                    icon = Icons.Filled.Payments,
                    title = stringResource(R.string.section_costs),
                    body = stringResource(R.string.pref_costs_desc),
                    statusLine = if (electricityPrice > 0.0) {
                        stringResource(R.string.current_rate_value, "%.4f".format(electricityPrice), currencySymbol)
                    } else {
                        stringResource(R.string.not_set)
                    }
                ),
                SettingsHubCard(
                    key = PreferencesPage.GOALS,
                    icon = Icons.Filled.EmojiEvents,
                    title = stringResource(R.string.pref_goals_label),
                    body = stringResource(R.string.pref_goals_desc),
                    statusLine = tripGoals.targetConsumptionKwhPer100km?.let {
                        stringResource(
                            R.string.goal_consumption_value,
                            "%.1f ${unitSystem.consumptionUnit}".format(unitSystem.convertEfficiency(it))
                        )
                    } ?: tripGoals.targetDistanceKmPerMonth?.let {
                        stringResource(
                            R.string.goal_monthly_distance_value,
                            "%.0f ${unitSystem.distanceUnit}".format(unitSystem.convertDistance(it))
                        )
                    } ?: stringResource(R.string.not_set),
                    onOpen = onNavigateToTripGoals
                )
            )
        }
    ) { page ->
        when (page) {
            PreferencesPage.APPEARANCE     -> AppearanceSection(preferencesManager, onNavigateToProTab)
            PreferencesPage.LANGUAGE_UNITS -> LanguageUnitsSection(viewModel)
            PreferencesPage.TRIP_RECORDING -> TripRecordingSection(viewModel, preferencesManager)
            PreferencesPage.BATTERY        -> BatterySection(preferencesManager, onNavigateToProTab)
            PreferencesPage.COSTS          -> CostsSection(viewModel)
        }
    }
}
