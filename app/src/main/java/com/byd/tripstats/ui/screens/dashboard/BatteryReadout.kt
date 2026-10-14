package com.byd.tripstats.ui.screens.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.byd.tripstats.R
import com.byd.tripstats.data.preferences.SocSource

/**
 * What the live dashboard battery tile is showing. A tap toggles the tile between the SoC the user
 * picked in Settings → Preferences (Panel or BMS) and the BMS remaining-EV energy — a dashboard-only
 * view flag ([PreferencesManager.dashboardShowRemainingKwh]). The Panel/BMS choice itself is owned by
 * Settings and the app-wide [SocSource]; tapping the tile never changes it, so charts/history and the
 * setting are untouched. Tapping again returns to whichever SoC source is currently selected.
 */
enum class BatteryReadoutMode { SOC_PANEL, SOC_BMS, REMAINING_KWH }

fun batteryReadoutMode(socSource: SocSource, showRemainingKwh: Boolean): BatteryReadoutMode = when {
    showRemainingKwh -> BatteryReadoutMode.REMAINING_KWH
    socSource == SocSource.PANEL -> BatteryReadoutMode.SOC_PANEL
    else -> BatteryReadoutMode.SOC_BMS
}

@Composable
fun batteryReadoutLabel(mode: BatteryReadoutMode): String = when (mode) {
    BatteryReadoutMode.SOC_PANEL -> stringResource(R.string.stat_soc_panel)
    BatteryReadoutMode.SOC_BMS -> stringResource(R.string.stat_soc_bms)
    BatteryReadoutMode.REMAINING_KWH -> stringResource(R.string.stat_remaining_kwh)
}

/**
 * The kWh the battery tile shows as remaining, from the car's remaining-EV-energy reading
 * ([rawKwh], `powerBatteryRemainPowerEV`), or null for an em dash.
 *
 * A reading the pack can't hold is dropped: more than the whole battery (a Shark 6 reports a
 * constant 128 on a 29.58 kWh pack), or, on a plug-in hybrid, the SoC percentage passed off as
 * kWh — a known PHEV firmware bug (a Sealion 6 DM-i read 84.1 at 84 %, a Shark 6 95.5 at 95 %).
 * No PHEV pack is near 100 kWh, so its kWh can't track its percentage; below 10 % SoC the two
 * meet legitimately, so that check stops there. A BEV is only held to the pack size, which
 * leaves every sane reading as it was.
 *
 * Where the reading is dropped and the car's EV reserve is measured (CarConfig
 * .phevEvReserveSocPct), the tile shows the energy above that reserve — the same figure the
 * range projection uses — instead of a dash.
 */
fun remainingKwhForTile(
    rawKwh: Double?,
    socPercent: Double,
    car: com.byd.tripstats.data.config.CarConfig?,
): Double? {
    val pack = car?.batteryKwh
    val plausible = rawKwh?.takeIf { it.isFinite() && it > 0.0 }
        ?.takeIf { pack == null || it <= pack * 1.05 }
        ?.takeIf { car?.isPhev != true || socPercent < 10.0 || kotlin.math.abs(it - socPercent) >= 5.0 }
    if (plausible != null) return plausible
    val reserve = car?.phevEvReserveSocPct
    if (car?.isPhev == true && pack != null && reserve != null && socPercent > 0.0) {
        return pack * ((socPercent - reserve) / 100.0).coerceAtLeast(0.0)
    }
    return null
}

/**
 * `(value, unit)` for the readout. [remainingKwh] is the BMS remaining-EV energy
 * (`powerBatteryRemainPowerEV`); an em dash is shown when it isn't reported — which is itself the
 * signal that the usable-kWh feed is absent on this hardware.
 */
fun batteryReadoutValueUnit(
    mode: BatteryReadoutMode,
    socPanel: Int,
    socBms: Double,
    remainingKwh: Double?,
): Pair<String, String> = when (mode) {
    BatteryReadoutMode.SOC_PANEL -> "$socPanel" to "%"
    BatteryReadoutMode.SOC_BMS -> "%.1f".format(socBms) to "%"
    // "kWh" unit keeps the tile uniform with the others; the label is just "Remaining".
    BatteryReadoutMode.REMAINING_KWH -> (remainingKwh?.let { "%.1f".format(it) } ?: "—") to "kWh"
}
