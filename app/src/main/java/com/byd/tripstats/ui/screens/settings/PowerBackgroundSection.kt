package com.byd.tripstats.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.ui.components.BrandSwitch
import com.byd.tripstats.adb.AdbPermissionManager
import com.byd.tripstats.data.preferences.OffStateMode
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.receiver.OffStateKeepaliveReceiver
import com.byd.tripstats.sdk.DiLink5Platform
import com.byd.tripstats.service.VehicleTelemetryService
import com.byd.tripstats.util.WifiKeepalive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * "Power & background" settings: the off-state background-activity mode (Always On / Minimal /
 * Deep Sleep), the Wi-Fi keepalive that runs while the car is off, and — DiLink-5 only — the
 * vehicle-data access switch. These are system behaviours on the head unit rather than display
 * preferences, so they are a page of the App tab (beside backups, compatibility and diagnostics)
 * rather than of Preferences.
 */
@Composable
internal fun PowerBackgroundSection(context: Context, scope: CoroutineScope) {
    val preferencesManager = remember { PreferencesManager(context) }
    val offStateMode by preferencesManager.offStateMode.collectAsState(
        initial = preferencesManager.getCachedOffStateMode()
    )
    val wifiKeepalive by preferencesManager.wifiKeepaliveWhenOff.collectAsState(
        initial = preferencesManager.getCachedWifiKeepaliveWhenOff()
    )
    // The Wi-Fi keepalive (while the car is off) runs in the privileged UID-2000 daemon (DiLink-3 only) and needs the
    // adb setup complete; without it there is nothing to enforce the toggle.
    val wifiKeepaliveSetupOk = remember { AdbPermissionManager.isSetupComplete(context) }

    SectionHeader(icon = Icons.Filled.PowerSettingsNew, title = stringResource(R.string.section_power))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.pref_background_activity),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.background_activity_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    OffStateMode.ENABLED    to stringResource(R.string.bg_always_on),
                    OffStateMode.DISABLED   to stringResource(R.string.bg_minimal),
                    OffStateMode.DEEP_SLEEP to stringResource(R.string.bg_deep_sleep),
                ).forEach { (mode, label) ->
                    Button(
                        onClick = {
                            scope.launch { preferencesManager.saveOffStateMode(mode) }
                            // Reconcile background scheduling to the new mode immediately.
                            // saveOffStateMode only persists the pref; without this, the
                            // keepalive chain is (re)armed only on the next ACC_OFF, so
                            // switching e.g. Deep Sleep → Minimal while the car is already
                            // parked would leave NO keepalive armed (Deep Sleep cancels it),
                            // and off-state charging would never be sampled until the next
                            // drive. Apply the change now instead of waiting for a car cycle.
                            when (mode) {
                                OffStateMode.DEEP_SLEEP ->
                                    OffStateKeepaliveReceiver.cancel(context)
                                OffStateMode.DISABLED ->
                                    // Arm only if absent — if the service is still running it
                                    // will schedule the chain itself on self-stop.
                                    OffStateKeepaliveReceiver.ensureScheduled(context, "settings:minimal")
                                OffStateMode.ENABLED -> {
                                    // Always On: keepalive is redundant (watchdog/restarter
                                    // keep the service alive); start the service now so it
                                    // goes resident even if the car is currently parked.
                                    OffStateKeepaliveReceiver.cancel(context)
                                    VehicleTelemetryService.start(context)
                                }
                            }
                        },
                        // Fixed height rather than letting content set it: only Always On has
                        // a second line, so without this the other two would either be shorter
                        // pills or need a blank line — and a blank line pushes their label off
                        // centre, which is what it used to do.
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (offStateMode == mode)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (offStateMode == mode)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        // A Button centres its content, so a lone label sits in the middle of
                        // the pill while Always On's pair straddles it.
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, fontWeight = FontWeight.Bold)
                            if (mode == OffStateMode.ENABLED) {
                                Text(
                                    stringResource(R.string.bg_default_label),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
            listOf(
                OffStateMode.ENABLED    to stringResource(R.string.bg_always_on_desc),
                OffStateMode.DISABLED   to stringResource(R.string.bg_minimal_desc),
                OffStateMode.DEEP_SLEEP to stringResource(R.string.bg_deep_sleep_desc),
            ).forEach { (mode, description) ->
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (offStateMode == mode)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (offStateMode == mode) FontWeight.Medium else FontWeight.Normal,
                )
            }
            // Minimal-mode wake-alarm health — only relevant in Minimal (the 90-min charging-check
            // alarm). Always On runs the service continuously and cancels this alarm, and Deep Sleep
            // deliberately has none, so showing "never fired" in those modes is misleading (and got
            // confused with the Wi-Fi keepalive). Show it only where it actually applies.
            if (offStateMode == OffStateMode.DISABLED) {
                val kaLastFired = OffStateKeepaliveReceiver.lastFiredMs(context)
                val kaCount     = OffStateKeepaliveReceiver.fireCount(context)
                val kaArmed     = OffStateKeepaliveReceiver.isScheduled(context)
                val armedSuffix = if (kaArmed) stringResource(R.string.keepalive_armed) else ""
                val notArmedSuffix = if (kaArmed) "" else stringResource(R.string.keepalive_not_armed)
                val kaStatus = if (kaLastFired <= 0L) {
                    stringResource(R.string.keepalive_never_fired, armedSuffix)
                } else {
                    val agoMin = ((System.currentTimeMillis() - kaLastFired) / 60_000L).coerceAtLeast(0)
                    val ago = if (agoMin < 60) "$agoMin min ago" else "${agoMin / 60}h ${agoMin % 60}m ago"
                    stringResource(R.string.keepalive_fired, ago, kaCount, notArmedSuffix)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                Text(
                    kaStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Wi-Fi keepalive while the car is off (DiLink-3, privileged daemon). Keeps the head unit
            // reachable on the LAN after the car is switched off by re-enabling Wi-Fi when the MCU cuts it.
            if (!DiLink5Platform.isDiLink5) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            stringResource(R.string.wifi_keepalive_label),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(
                                if (wifiKeepaliveSetupOk) R.string.wifi_keepalive_desc
                                else R.string.wifi_keepalive_needs_setup
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    BrandSwitch(
                        checked = wifiKeepalive,
                        enabled = wifiKeepaliveSetupOk,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                preferencesManager.saveWifiKeepaliveWhenOff(enabled)
                                WifiKeepalive.apply(context, enabled)
                            }
                        },
                    )
                }
            }
        }
    }

    // DiLink-5 only: opt-in for the global hidden-API exemption that lets the app read vehicle
    // data. Lets a user who declined the first-run prompt enable it later (or turn it back off).
    // It changes a device-wide head-unit setting, which is why it sits here and not in Preferences.
    if (DiLink5Platform.isDiLink5) {
        var vehicleAccessOn by remember {
            mutableStateOf(AdbPermissionManager.hasHiddenApiConsent(context))
        }
        SettingsGroupLabel(stringResource(R.string.d5_vehicle_access_section))
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
                            stringResource(R.string.d5_vehicle_access_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(R.string.d5_vehicle_access_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = vehicleAccessOn,
                        onCheckedChange = { on ->
                            vehicleAccessOn = on
                            AdbPermissionManager.setHiddenApiConsent(context, on)
                            AdbPermissionManager.markHiddenApiPrompted(context)
                            if (on) scope.launch {
                                // Apply the exemption, then restart so the SDK binds on a fresh
                                // fork (same fork-latch as the first-run consent dialog).
                                val applied = AdbPermissionManager.ensureVehicleApiAccess(context)
                                if (applied) AdbPermissionManager.restartApp(context)
                            }
                        }
                    )
                }
            }
        }
    }
}
