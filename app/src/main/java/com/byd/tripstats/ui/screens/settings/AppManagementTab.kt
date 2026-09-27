package com.byd.tripstats.ui.screens.settings

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.byd.tripstats.R
import com.byd.tripstats.adb.AdbPermissionManager
import com.byd.tripstats.data.preferences.OffStateMode
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.sdk.DiLink5Platform
import com.byd.tripstats.sdk.VehicleCompatibilityProbe
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.CoroutineScope

/**
 * Settings → App: how the app runs on the head unit, and the tools for looking after it.
 *
 * Laid out like Connections — an overview of cards, each opening on its own page — because the
 * four sections used to stack into one long scroll, with the diagnostics charts and ADB shell
 * sitting between the user and whatever came after them. Backup & Restore already had a screen of
 * its own, so its card goes straight there.
 */
private object AppPage {
    const val POWER = "power"
    const val BACKUP = "backup"
    const val COMPATIBILITY = "compatibility"
    const val DIAGNOSTICS = "diagnostics"
}

@Composable
internal fun AppManagementTab(
    viewModel         : DashboardViewModel,
    context           : Context,
    onNavigateToBackup: () -> Unit,
    scope             : CoroutineScope
) {
    val preferencesManager = remember { PreferencesManager(context) }

    SettingsHub(
        icon = Icons.Filled.Settings,
        title = stringResource(R.string.settings_tab_app),
        description = stringResource(R.string.settings_overview_desc),
        cards = {
            val offStateMode by preferencesManager.offStateMode.collectAsState(
                initial = preferencesManager.getCachedOffStateMode()
            )
            val wifiKeepalive by preferencesManager.wifiKeepaliveWhenOff.collectAsState(
                initial = preferencesManager.getCachedWifiKeepaliveWhenOff()
            )
            val probeEnabled by VehicleCompatibilityProbe.isEnabled.collectAsState()
            val probeEntries by VehicleCompatibilityProbe.entryCount.collectAsState()
            val probeLastCapture by VehicleCompatibilityProbe.lastCaptureAt.collectAsState()
            // The persisted flag, read without initialising the monitor: initialising starts its
            // CPU/memory sampling, which only the Diagnostics page itself should do.
            val diagnosticsEnabled = AppDiagnosticsMonitor.isEnabled(context)

            val lastBackup = remember { viewModel.listDatabaseBackups().firstOrNull() }
            val backupStatus = if (lastBackup == null) {
                stringResource(R.string.app_mgmt_no_local_backup)
            } else {
                stringResource(R.string.last_backup_label, formatFriendlyTimestamp(lastBackup.lastModified()))
            }

            val powerStatus = buildList {
                add(
                    stringResource(
                        when (offStateMode) {
                            OffStateMode.ENABLED    -> R.string.bg_always_on
                            OffStateMode.DISABLED   -> R.string.bg_minimal
                            OffStateMode.DEEP_SLEEP -> R.string.bg_deep_sleep
                        }
                    )
                )
                if (DiLink5Platform.isDiLink5) {
                    // Off here means the app shows no vehicle data at all, so it earns a mention.
                    if (!AdbPermissionManager.hasHiddenApiConsent(context)) {
                        add(stringResource(R.string.d5_vehicle_access_off_status))
                    }
                } else if (wifiKeepalive) {
                    add(stringResource(R.string.wifi_keepalive_on_status))
                }
            }.joinToString(" • ")

            listOf(
                SettingsHubCard(
                    key = AppPage.POWER,
                    icon = Icons.Filled.PowerSettingsNew,
                    title = stringResource(R.string.section_power),
                    body = stringResource(
                        if (DiLink5Platform.isDiLink5) R.string.power_card_desc_d5 else R.string.power_card_desc
                    ),
                    statusLine = powerStatus
                ),
                SettingsHubCard(
                    key = AppPage.BACKUP,
                    icon = Icons.Filled.Backup,
                    title = stringResource(R.string.backup_card_title),
                    body = stringResource(R.string.backup_card_desc),
                    statusLine = backupStatus,
                    onOpen = onNavigateToBackup
                ),
                SettingsHubCard(
                    key = AppPage.COMPATIBILITY,
                    icon = Icons.Filled.BugReport,
                    title = stringResource(R.string.vehicle_compat_label),
                    body = stringResource(R.string.compat_card_desc),
                    statusLine = if (probeEnabled) {
                        val lastCaptureSuffix = probeLastCapture
                            ?.let { stringResource(R.string.last_capture_suffix, it.substringBefore('T')) }
                            ?: ""
                        stringResource(R.string.active_probe_status, probeEntries, lastCaptureSuffix)
                    } else {
                        stringResource(R.string.probe_off_status)
                    }
                ),
                SettingsHubCard(
                    key = AppPage.DIAGNOSTICS,
                    icon = Icons.Filled.Memory,
                    title = stringResource(R.string.app_diagnostics_label),
                    body = stringResource(R.string.diagnostics_card_desc),
                    statusLine = stringResource(
                        if (diagnosticsEnabled) R.string.diagnostics_live_on_status
                        else R.string.diagnostics_live_off_status
                    )
                )
            )
        }
    ) { page ->
        when (page) {
            AppPage.POWER         -> PowerBackgroundSection(context = context, scope = scope)
            AppPage.COMPATIBILITY -> VehicleCompatibilitySection(context = context, scope = scope)
            AppPage.DIAGNOSTICS   -> AppDiagnosticsCard()
        }
    }
}
