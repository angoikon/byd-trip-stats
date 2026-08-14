package com.byd.tripstats

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.byd.tripstats.BuildConfig
import com.byd.tripstats.R
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.byd.tripstats.adb.AdbPermissionManager
import com.byd.tripstats.util.DiagLog
import com.byd.tripstats.util.LocaleHelper
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.ThemeMode
import com.byd.tripstats.sdk.DiLink5Platform
import com.byd.tripstats.service.VehicleTelemetryService
import com.byd.tripstats.ui.components.ScreenshotFlashOverlay
import com.byd.tripstats.ui.navigation.AppNavigation
import com.byd.tripstats.ui.screens.InitializationScreen
import com.byd.tripstats.ui.theme.BydTripStatsTheme
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    private val viewModel: DashboardViewModel by viewModels()
    private var telemetryService: VehicleTelemetryService? = null
    private var bound = false

    // Shown once per app version update to remind the user to re-enable Autostart
    private val showAutostartReminder = mutableStateOf(false)
    private val showSetupRequired   = mutableStateOf(false)
    private val showHiddenApiConsent = mutableStateOf(false)
    private val showHiddenApiDeclineConfirm = mutableStateOf(false)

    // ── Locale override ───────────────────────────────────────────────────────

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    // ── Service binding ───────────────────────────────────────────────────────

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as VehicleTelemetryService.LocalBinder
            telemetryService = binder.getService()
            bound = true
            Log.i(TAG, "Telemetry service connected")
            telemetryService?.let { viewModel.observeTelemetryServiceState(it) }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            bound = false
            telemetryService = null
            Log.w(TAG, "Telemetry service disconnected unexpectedly")
        }
    }

    // ── Permission launcher ───────────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { granted -> !granted }.keys
        Log.d(TAG, "Permissions result — denied: $denied")
        if (denied.isNotEmpty()) Log.w(TAG, "Some optional permissions were denied: $denied")
        bindToTelemetryService()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Launch-cause marker. A process start with no BootReceiver line and no line
        // here came from a background source (JobScheduler/WorkManager kick); this line
        // means the UI was actually launched — by the user, by BYD's autostart, or by the
        // supervisor's activity-start fallback. Without it the diag.log can't tell them
        // apart, which is exactly what blocked the DiLink-5 "app didn't come up" reports.
        DiagLog.event(
            applicationContext, TAG,
            "MainActivity onCreate — UI launch (restored=${savedInstanceState != null} " +
                "caller=${runCatching { referrer?.host }.getOrNull() ?: "?"})",
        )
        requestRequiredPermissions()
        checkAndShowAutostartReminder()
        checkSetupRequired()

        setContent {
            val prefs = remember { PreferencesManager(applicationContext) }
            val themeMode by prefs.themeMode.collectAsState(initial = prefs.getCachedThemeMode())
            val isPro by com.byd.tripstats.data.entitlement.EntitlementManager.isPro.collectAsState()
            // Neon is a Pro, dark-only theme; a non-Pro fallback renders as normal dark.
            val neon = themeMode == ThemeMode.NEON && isPro
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT  -> false
                ThemeMode.DARK   -> true
                ThemeMode.NEON   -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            BydTripStatsTheme(darkTheme = darkTheme, neon = neon) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val cachedSelectedCarId = remember { prefs.getCachedSelectedCarId() }
                    val selectedCarId by prefs.selectedCarId.collectAsState(
                        initial = cachedSelectedCarId ?: "__loading__"
                    )
                    // Autostart reminder dialog — shown once after each version update
                    if (showAutostartReminder.value) {
                        AlertDialog(
                            onDismissRequest = { dismissAutostartReminder() },
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            title = { Text(stringResource(R.string.autostart_dialog_title)) },
                            text = { Text(stringResource(R.string.autostart_dialog_msg)) },
                            confirmButton = {
                                TextButton(onClick = { openAutostartManagementDialog() }) {
                                    Text(stringResource(R.string.autostart_got_it))
                                }
                            }
                        )
                    }

                    // Wrong-build warning: only a DiLink-5 car running a non-dilink5 build is broken
                    // (no D5 SDK path → no telemetry). The reverse (dilink5 build on a D3 car) works
                    // via the reflective bridge, so it's intentionally not flagged. Detection only —
                    // dismissible per session, re-warns each launch until the correct build is installed.
                    val flavorWarnOpen = remember { mutableStateOf(DiLink5Platform.isBuildUnsupportedForHardware) }
                    if (flavorWarnOpen.value) {
                        AlertDialog(
                            onDismissRequest = { flavorWarnOpen.value = false },
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            title = { Text(stringResource(R.string.flavor_mismatch_title)) },
                            text = {
                                Text(stringResource(
                                    R.string.flavor_mismatch_msg,
                                    DiLink5Platform.flavorLabel(BuildConfig.FLAVOR),
                                    DiLink5Platform.flavorLabel(DiLink5Platform.expectedFlavor),
                                ))
                            },
                            confirmButton = {
                                TextButton(onClick = { flavorWarnOpen.value = false }) {
                                    Text(stringResource(R.string.autostart_got_it))
                                }
                            }
                        )
                    }

                    // DiLink-5 hidden-API exemption — one-time opt-in (PR #8 item 2b). We change a
                    // GLOBAL head-unit setting, so ask before doing it; a decline is remembered and
                    // can be reversed later from Settings.
                    if (showHiddenApiConsent.value) {
                        AlertDialog(
                            onDismissRequest = { },
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            title = { Text(stringResource(R.string.d5_consent_title)) },
                            text = { Text(stringResource(R.string.d5_consent_body)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    AdbPermissionManager.setHiddenApiConsent(this@MainActivity, true)
                                    AdbPermissionManager.markHiddenApiPrompted(this@MainActivity)
                                    showHiddenApiConsent.value = false
                                    lifecycleScope.launch {
                                        // Apply the exemption, then restart our own process: the
                                        // hidden-API enforcement is latched at process fork, so the
                                        // SDK only binds after a fresh start (else the user must
                                        // manually force-stop + reopen).
                                        val applied = AdbPermissionManager.ensureVehicleApiAccess(this@MainActivity)
                                        if (applied) restartAppProcess()
                                    }
                                }) { Text(stringResource(R.string.d5_consent_allow)) }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    // Don't finalize yet — ask for confirmation first.
                                    showHiddenApiConsent.value = false
                                    showHiddenApiDeclineConfirm.value = true
                                }) { Text(stringResource(R.string.d5_consent_not_now)) }
                            }
                        )
                    }
                    // Confirm a decline: make sure the user knows the app shows no vehicle data
                    // without it, and that it can be enabled later from Settings.
                    if (showHiddenApiDeclineConfirm.value) {
                        AlertDialog(
                            onDismissRequest = { },
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            title = { Text(stringResource(R.string.d5_consent_decline_title)) },
                            text = { Text(stringResource(R.string.d5_consent_decline_body)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    // Still declining — remember it and close. Re-enable later in Settings.
                                    AdbPermissionManager.markHiddenApiPrompted(this@MainActivity)
                                    showHiddenApiDeclineConfirm.value = false
                                }) { Text(stringResource(R.string.d5_consent_decline_confirm)) }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    // Reconsidered — go back to the consent prompt.
                                    showHiddenApiDeclineConfirm.value = false
                                    showHiddenApiConsent.value = true
                                }) { Text(stringResource(R.string.d5_consent_decline_back)) }
                            }
                        )
                    }
                    // ADB permission setup — driven by AdbPermissionManager.state
                    if (showSetupRequired.value) {
                        val adbState by AdbPermissionManager.state.collectAsState()
                        if (adbState is AdbPermissionManager.SetupState.Done) {
                            showSetupRequired.value = false
                        } else {
                            val dialogTitle = when (adbState) {
                                is AdbPermissionManager.SetupState.Connecting  -> "Connecting..."
                                is AdbPermissionManager.SetupState.WaitingAuth -> "Waiting for authorization"
                                is AdbPermissionManager.SetupState.Granting    -> "Granting permissions..."
                                is AdbPermissionManager.SetupState.Failed      -> "Setup failed"
                                else -> "Setup Required"
                            }
                            val dialogBody = when (val s = adbState) {
                                is AdbPermissionManager.SetupState.Idle ->
                                    "BYD Trip Stats needs one-time ADB authorization to run " +
                                    "in the background.\n\nSteps:\n" +
                                    "1. Settings → Developer Options\n" +
                                    "2. Enable USB Debugging\n" +
                                    "3. Return here and tap Authorize"
                                is AdbPermissionManager.SetupState.Connecting ->
                                    "Connecting to ADB daemon..."
                                is AdbPermissionManager.SetupState.WaitingAuth ->
                                    "A dialog should appear on screen asking to allow " +
                                    "USB debugging. Tap Allow, then return here.\n\n" +
                                    "This is a one-time action. Future updates are fully automatic."
                                is AdbPermissionManager.SetupState.Granting ->
                                    "Granting background permissions..."
                                is AdbPermissionManager.SetupState.Failed ->
                                    s.reason + "\n\nTap Retry to try again."
                                else -> ""
                            }
                            val busy = adbState is AdbPermissionManager.SetupState.Connecting ||
                                adbState is AdbPermissionManager.SetupState.WaitingAuth ||
                                adbState is AdbPermissionManager.SetupState.Granting

                            AlertDialog(
                                onDismissRequest = { if (!busy) showSetupRequired.value = false },
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                title = { Text(dialogTitle) },
                                text = {
                                    Column {
                                        Text(dialogBody)
                                        if (busy) {
                                            Spacer(Modifier.height(12.dp))
                                            LinearProgressIndicator(Modifier.padding(top = 4.dp))
                                        }
                                    }
                                },
                                confirmButton = {
                                    if (adbState is AdbPermissionManager.SetupState.Idle) {
                                        TextButton(onClick = {
                                            runCatching {
                                                startActivity(Intent(
                                                    Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                                                ))
                                            }
                                            lifecycleScope.launch {
                                                AdbPermissionManager.runSetup(this@MainActivity)
                                            }
                                        }) { Text("Open Developer Settings") }
                                    } else if (adbState is AdbPermissionManager.SetupState.Failed) {
                                        TextButton(onClick = {
                                            lifecycleScope.launch {
                                                AdbPermissionManager.runSetup(this@MainActivity)
                                            }
                                        }) { Text("Retry") }
                                    }
                                },
                                dismissButton = {
                                    if (!busy) {
                                        TextButton(onClick = {
                                            showSetupRequired.value = false
                                        }) { Text("Later") }
                                    }
                                }
                            )
                        }
                    }
                    if (selectedCarId == "__loading__") {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (selectedCarId == null) {
                        InitializationScreen(
                            onContinue = { car ->
                                prefs.saveInitialSetup(car.id)
                            }
                        )
                    } else {
                        val navController = rememberNavController()
                        AppNavigation(
                            navController = navController,
                            viewModel = viewModel
                        )
                    }

                    ScreenshotFlashOverlay()
                }
            }
        }
    }

    private fun checkAndShowAutostartReminder() {
        val prefs = PreferencesManager(applicationContext)
        val currentVersion = BuildConfig.VERSION_CODE
        lifecycleScope.launch {
            val lastSeen = prefs.getLastSeenVersionCode()
            // Show dialog on first install or upgrade
            if (lastSeen < currentVersion) {
                showAutostartReminder.value = true
            }
            // Always persist the current version so we only show once per upgrade
            prefs.saveLastSeenVersionCode(currentVersion)
        }
    }

    private fun dismissAutostartReminder() {
        showAutostartReminder.value = false
    }

    private fun openAutostartManagementDialog() {
        dismissAutostartReminder()
        val intent = Intent("android.intent.action.BYD_APPSTARTMANAGEMENT").apply {
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        runCatching { startActivity(intent) }
            .onFailure { error ->
                Log.w(TAG, "Unable to launch BYD autostart management", error)
            }
    }

    private fun checkSetupRequired() {
        // DiLink-5 build on DiLink-5 hardware ONLY. The adb setup + hidden-API exemption exist solely
        // so the DiLink-5 bydauto SDK (present only in the dilink5 flavor) can bind. Skip it when:
        //  - not DiLink-5 hardware (D3 needs none of it), OR
        //  - not the dilink5 build — a dilink3 APK on a D5 car has no D5 code to exempt, so running
        //    the setup is pointless and would stack a consent dialog on top of the wrong-build warning.
        if (!DiLink5Platform.isDiLink5 || BuildConfig.FLAVOR != "dilink5") return

        // Idempotently re-assert DiLink-5 vehicle-API access on startup. This grants the bydauto
        // *_COMMON perms (app-scoped, always) and — only if the user consented — re-applies the
        // hidden-API exemption if it was reset (reboot). No-ops if adb isn't authorised yet.
        lifecycleScope.launch {
            AdbPermissionManager.ensureVehicleApiAccess(this@MainActivity)
        }

        // One-time opt-in for the hidden-API exemption (a global device change). Ask once; a decline
        // is remembered so we don't nag, and can be reversed from Settings.
        if (!AdbPermissionManager.hasHiddenApiConsent(this) &&
            !AdbPermissionManager.hasBeenPromptedForHiddenApi(this)) {
            showHiddenApiConsent.value = true
        }
        if (AdbPermissionManager.isSetupComplete(this)) return
        showSetupRequired.value = true
        lifecycleScope.launch {
            AdbPermissionManager.runSetup(this@MainActivity)
        }
    }

    /**
     * Restart our own process. The DiLink-5 hidden-API exemption is captured at process fork, so the
     * bydauto SDK only binds after a fresh start. Schedule a relaunch of the launcher activity a moment
     * out, then hard-exit so the process re-forks with the exemption in effect. Safe to call after the
     * consent is persisted — the consent dialog won't re-show, so there's no restart loop.
     */
    private fun restartAppProcess() = AdbPermissionManager.restartApp(this)

    override fun onStart() {
        super.onStart()
        // Rebind every time the Activity becomes visible — covers the CarPlay
        // scenario where DiLink kills and recreates the Activity while the
        // Vehicle telemetry foreground service keeps running uninterrupted.
        if (!bound) bindToTelemetryService()
        // App is on screen → a car-off auto-stop may be held for user confirmation.
        viewModel.setUiVisible(true)
    }

    override fun onStop() {
        super.onStop()
        // App left the screen → no one can confirm, so a held auto-stop is finalised
        // and future car-off stops happen silently (legacy behaviour).
        viewModel.setUiVisible(false)
        // Unbind when going to background so the binding is clean on next onStart.
        // The service itself keeps running (START_STICKY + foreground notification)
        // so trip recording and vehicle telemetry collection are unaffected.
        if (bound) {
            unbindService(connection)
            bound = false
            telemetryService = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unbind safety net — normally handled by onStop, but covers edge cases
        // where onStop was skipped (e.g. process death without lifecycle callbacks).
        if (bound) {
            unbindService(connection)
            bound = false
        }
        // Do NOT stop the service — it must keep running in the background
        // for auto trip detection and vehicle telemetry collection.
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requestRequiredPermissions() {
        val required = buildList {
            // POST_NOTIFICATIONS required on Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            // READ_EXTERNAL_STORAGE required on Android 10–12 to query MediaStore
            // entries not owned by the current app install (e.g. after a reinstall).
            // Superseded by READ_MEDIA_* on Android 13+ where it has no effect.
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
        // No else branch — onStart() handles binding when permissions are
        // already granted. The permissionLauncher callback handles first-grant.
    }

    // ── Service binding ───────────────────────────────────────────────────────

    /**
     * Binds to the vehicle telemetry service. The service is started by [BydStatsApplication] on
     * every process start, so it should already be running by the time the
     * Activity reaches this point. [Context.BIND_AUTO_CREATE] ensures it is
     * started if somehow it is not yet running (e.g. during first-ever launch
     * before Application.onCreate has finished initial service startup).
     *
     * We no longer use the deprecated [android.app.ActivityManager.getRunningServices]
     * to detect whether the service is running — that API is unreliable on
     * modern Android and was removed from the call path entirely.
     */
    private fun bindToTelemetryService() {
        Log.d(TAG, "Binding to telemetry service")
        val intent = Intent(this, VehicleTelemetryService::class.java)
        // Start the service explicitly before binding so it has an independent
        // lifecycle. Without this, the service is bound-only and dies when the
        // UI unbinds in onStop() — killing trip recording while driving.
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }
}