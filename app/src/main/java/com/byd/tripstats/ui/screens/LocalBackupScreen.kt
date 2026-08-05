package com.byd.tripstats.ui.screens

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.core.content.ContextCompat
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.ui.res.stringResource
import com.byd.tripstats.R
import com.byd.tripstats.data.backup.LocalBackupManager
import com.byd.tripstats.data.backup.TelegramManager
import com.byd.tripstats.data.entitlement.EntitlementManager
import com.byd.tripstats.sdk.DiLink5Platform
import com.byd.tripstats.ui.components.BrandNavigationBar
import com.byd.tripstats.ui.theme.*
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import com.byd.tripstats.worker.DatabaseTrimmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalBackupScreen(
    viewModel: DashboardViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val manager = remember { LocalBackupManager.getInstance(context) }
    val scope = rememberCoroutineScope()

    val backupState by manager.state.collectAsState()
    val localBackups by manager.localBackups.collectAsState()

    val telegramManager = remember { TelegramManager.getInstance(context) }
    val telegramState by telegramManager.state.collectAsState()
    val telegramConfig by telegramManager.config.collectAsState()      // StateFlow — reactive
    val telegramSchedule by telegramManager.schedule.collectAsState()
    val telegramAuto by telegramManager.autoEnabled.collectAsState()
    val telegramWifiOnly by telegramManager.wifiOnly.collectAsState()

    val telegramBackups by telegramManager.telegramBackups.collectAsState()

    val isBusy = backupState is LocalBackupManager.BackupState.InProgress
    val telegramBusy = telegramState is TelegramManager.TelegramState.InProgress
    val isPro by EntitlementManager.isPro.collectAsState()  // SD card backup is Pro-gated

    var restoreTarget by remember { mutableStateOf<LocalBackupManager.BackupFile?>(null) }
    var deleteTarget  by remember { mutableStateOf<LocalBackupManager.BackupFile?>(null) }
    var pendingDeleteAfterPermission by remember { mutableStateOf<LocalBackupManager.BackupFile?>(null) }
    var pendingSdBackupAfterPermission by remember { mutableStateOf(false) }
    var telegramRestoreTarget by remember { mutableStateOf<TelegramManager.TelegramBackupFile?>(null) }
    var telegramDeleteTarget  by remember { mutableStateOf<TelegramManager.TelegramBackupFile?>(null) }

    // WRITE_EXTERNAL_STORAGE is needed for direct-file writes to shared storage: deleting a
    // filesystem backup, and writing an SD-card backup. On a fresh install it isn't granted (and
    // on API 30–32 it's only grantable because the manifest cap was raised to 32), so request it
    // on demand and run the pending action on grant.
    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pendingDelete = pendingDeleteAfterPermission
        val pendingSd = pendingSdBackupAfterPermission
        pendingDeleteAfterPermission = null
        pendingSdBackupAfterPermission = false
        if (granted && pendingDelete != null) {
            scope.launch { manager.deleteBackup(pendingDelete) }
        }
        if (granted && pendingSd) {
            scope.launch { manager.backupDatabaseToSdCard() }
        }
    }
    // Hoisted out of item{} so it survives LazyColumn recycling
    var tokenInput by remember { mutableStateOf("") }
    LaunchedEffect(telegramConfig) {
        tokenInput = telegramConfig?.token ?: ""
    }

    // ── Auto-dismiss Success banners after 4 seconds ──────────────────────────
    LaunchedEffect(backupState) {
        if (backupState is LocalBackupManager.BackupState.Success &&
            !(backupState as LocalBackupManager.BackupState.Success).restartRequired) {
            delay(4000)
            manager.resetState()
        }
        if (backupState is LocalBackupManager.BackupState.Error &&
            (backupState as LocalBackupManager.BackupState.Error).message == "No backups found. Run a backup first.") {
            delay(4000)
            manager.resetState()
        }
    }
    LaunchedEffect(telegramState) {
        if (telegramState is TelegramManager.TelegramState.Success) {
            delay(4000)
            telegramManager.resetState()
        }
        if (telegramState is TelegramManager.TelegramState.Error &&
            (telegramState as TelegramManager.TelegramState.Error).message == "No backups found. Send a backup first.") {
            delay(4000)
            telegramManager.resetState()
        }
    }

    // ── Auto-restart after successful restore ─────────────────────────────────
    LaunchedEffect(backupState) {
        val s = backupState
        if (s is LocalBackupManager.BackupState.Success && s.restartRequired) {
            delay(2000)
            // DiLink-5: do NOT auto-relaunch. An app-initiated kill + immediate relaunch
            // races the bydauto SDK classloader injection and boot-loops the head unit
            // (2.13.0 incident). The process just ends; the user reopens (safe, like adb install -r).
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
            if (launchIntent != null && !DiLink5Platform.isDiLink5) {
                val pending = PendingIntent.getActivity(
                    context, 0, launchIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val alarm = context.getSystemService(android.app.AlarmManager::class.java)
                alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 500L, pending)
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    // Listing backups from shared storage (Download/SD) needs READ_EXTERNAL_STORAGE on this
    // legacy-storage setup. After a fresh install it isn't granted, so the scan silently finds
    // nothing — including backups that SURVIVED an uninstall (the restore-after-reinstall case).
    // Request it, then re-scan on grant. (Not requestable on API 33+, where the perm is gone.)
    val readPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) scope.launch { manager.scanLocalBackups() } }

    // Restore from any .db the user picks — a fallback for when the scan can't surface a backup
    // (an orphaned file after reinstall, a different folder, an adb-pushed file, another device's
    // backup). Uses our own in-app FileBrowserDialog rather than SAF's OpenDocument: DiLink head
    // units ship no DocumentsUI, so SAF degrades to a third-party-app chooser.
    var showFileBrowser by remember { mutableStateOf(false) }
    val sdCardRootDir = remember { manager.sdCardRoot() }

    // ── Load local backup list and telegram list on first open ────────────────
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT <= 32 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            readPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        manager.scanLocalBackups()
        if (telegramConfig != null) telegramManager.listTelegramBackups()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.backup_restore_title),
                        fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onNavigateBack() })
                },
                navigationIcon = {
                  BrandNavigationBar {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), modifier = Modifier.size(32.dp))
                    }
                  }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Backup state banner ───────────────────────────────────────────
            item {
                when (val s = backupState) {
                    is LocalBackupManager.BackupState.InProgress -> StatusBanner(
                        text    = s.message,
                        color   = MaterialTheme.colorScheme.primaryContainer,
                        icon    = Icons.Filled.HourglassTop,
                        loading = true
                    )
                    is LocalBackupManager.BackupState.Success -> StatusBanner(
                        text      = s.message,
                        color     = RegenGreen.copy(alpha = 0.15f),
                        icon      = Icons.Filled.CheckCircle,
                        iconTint  = RegenGreen,
                        onDismiss = { manager.resetState() }
                    )
                    is LocalBackupManager.BackupState.Error -> StatusBanner(
                        text      = s.message,
                        color     = MaterialTheme.colorScheme.errorContainer,
                        icon      = Icons.Filled.Error,
                        iconTint  = MaterialTheme.colorScheme.error,
                        onDismiss = { manager.resetState() }
                    )
                    else -> {}
                }
            }

            // ── LOCAL group ───────────────────────────────────────────────────
            item {
                GroupSection(title = stringResource(R.string.app_mgmt_backup_local_label), icon = Icons.Filled.Storage) {
                    SectionCard(title = stringResource(R.string.backup_to_download_label), icon = Icons.Filled.CloudUpload) {
                Text(
                    stringResource(R.string.backup_download_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        manager.resetState()
                        scope.launch { manager.backupDatabase() }
                    },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color       = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Filled.Save, null, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (isBusy) stringResource(R.string.running) else stringResource(R.string.backup_now_action))
                }
            }
                    Spacer(Modifier.height(8.dp))
                    SectionCard(title = stringResource(R.string.backup_to_sd_label), icon = Icons.Filled.SdCard) {
                        Text(
                            stringResource(R.string.sd_backup_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        if (!isPro) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Filled.Lock, null, modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    stringResource(R.string.sd_card_pro_notice),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.sd_card_pro_notice),
                                        Toast.LENGTH_LONG
                                    ).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Lock, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.unlock_pro_action))
                            }
                        } else {
                            val sdAvailable = manager.isSdCardAvailable()
                            if (!sdAvailable) {
                                Text(
                                    stringResource(R.string.sd_card_not_detected),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            Button(
                                onClick = {
                                    manager.resetState()
                                    if (Build.VERSION.SDK_INT <= 32 &&
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                            != PackageManager.PERMISSION_GRANTED) {
                                        pendingSdBackupAfterPermission = true
                                        writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    } else {
                                        scope.launch { manager.backupDatabaseToSdCard() }
                                    }
                                },
                                enabled = !isBusy && sdAvailable,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isBusy) {
                                    CircularProgressIndicator(
                                        modifier    = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color       = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Icon(Icons.Filled.SdCard, null, modifier = Modifier.size(20.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(if (isBusy) stringResource(R.string.running) else stringResource(R.string.backup_to_sd_label))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SectionCard(title = stringResource(R.string.restore_section_label), icon = Icons.Filled.CloudDownload) {
                    Text(
                        stringResource(R.string.restore_warning_msg),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.available_backups_label),
                            style      = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        IconButton(
                            onClick  = { scope.launch { manager.scanLocalBackups() } },
                            enabled  = !isBusy
                        ) {
                            Icon(Icons.Filled.Refresh, stringResource(R.string.refresh), modifier = Modifier.size(22.dp))
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    if (localBackups.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.no_backups_found),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        localBackups.forEachIndexed { index, backup ->
                            if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            BackupListItem(
                                backup    = backup,
                                enabled   = !isBusy,
                                onRestore = { restoreTarget = backup },
                                onDelete  = { deleteTarget  = backup },
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick  = { showFileBrowser = true },
                        enabled  = !isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.restore_from_file_action))
                    }

                    if (showFileBrowser) {
                        FileBrowserDialog(
                            startDir = File(Environment.getExternalStorageDirectory(), "Download/BydTripStats"),
                            sdCardRoot = sdCardRootDir,
                            onDismiss = { showFileBrowser = false },
                            onFileSelected = { file ->
                                showFileBrowser = false
                                scope.launch { manager.restoreFromUri(Uri.fromFile(file)) }
                            }
                        )
                    }
                }
                }
            }

            // ── MAINTENANCE group ─────────────────────────────────────────────
            item {
                DatabaseTrimSection(scope = scope, isBusy = isBusy)
            }

            // ── TELEGRAM group ────────────────────────────────────────────────
            item {
                GroupSection(title = stringResource(R.string.app_mgmt_telegram_label), icon = Icons.AutoMirrored.Filled.Send) {
                    SectionCard(title = stringResource(R.string.telegram_backup_label), icon = Icons.AutoMirrored.Filled.Send) {
                // Telegram status banner
                when (val s = telegramState) {
                    is TelegramManager.TelegramState.InProgress -> StatusBanner(
                        text    = s.message,
                        color   = MaterialTheme.colorScheme.primaryContainer,
                        icon    = Icons.Filled.HourglassTop,
                        loading = true
                    )
                    is TelegramManager.TelegramState.Success -> StatusBanner(
                        text      = s.message,
                        color     = RegenGreen.copy(alpha = 0.15f),
                        icon      = Icons.Filled.CheckCircle,
                        iconTint  = RegenGreen,
                        onDismiss = { telegramManager.resetState() }
                    )
                    is TelegramManager.TelegramState.Error -> StatusBanner(
                        text      = s.message,
                        color     = MaterialTheme.colorScheme.errorContainer,
                        icon      = Icons.Filled.Error,
                        iconTint  = MaterialTheme.colorScheme.error,
                        onDismiss = { telegramManager.resetState() }
                    )
                    else -> {}
                }

                Spacer(Modifier.height(8.dp))

                if (telegramConfig != null) {
                    // ── Connected state ───────────────────────────────────

                    // Bot info row
                    Row(
                        modifier          = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = RegenGreen
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "@${telegramConfig!!.botName}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Chat ID: ${telegramConfig!!.chatId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            telegramManager.lastAutoBackup?.let {
                                Text(
                                    stringResource(R.string.last_auto_backup_label, it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                    // Auto-backup toggle + schedule selector
                    Row(
                        modifier          = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.auto_backup_label),
                            style    = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked         = telegramAuto,
                            onCheckedChange = { telegramManager.setAutoEnabled(it) },
                            thumbContent = if (!telegramAuto) {
                                {
                                    // Donut effect: white outer thumb + coloured inner circle
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .background(ToggleUncheckedTrack, CircleShape)
                                    )
                                }
                            } else null,
                            colors = SwitchDefaults.colors(
                                uncheckedThumbColor  = Color.White,
                                uncheckedTrackColor  = ToggleUncheckedTrack,
                                uncheckedBorderColor = ToggleUncheckedTrack
                            )
                        )
                    }

                    if (telegramAuto) {
                        var scheduleChanged by remember { mutableStateOf<String?>(null) }

                        // Auto-clear the "schedule changed" notice after 3 seconds
                        LaunchedEffect(scheduleChanged) {
                            if (scheduleChanged != null) {
                                delay(3000)
                                scheduleChanged = null
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.backup_interval_label),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        TelegramManager.Schedule.entries.forEach { s ->
                            Row(
                                modifier          = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = s == telegramSchedule,
                                    onClick  = {
                                        if (s != telegramSchedule) {
                                            telegramManager.setSchedule(s)
                                            scheduleChanged = context.getString(s.labelRes)
                                        }
                                    }
                                )
                                Text(
                                    text  = stringResource(s.labelRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }

                        scheduleChanged?.let { label ->
                            Spacer(Modifier.height(4.dp))
                            StatusBanner(
                                text     = stringResource(R.string.schedule_updated_msg, label),
                                color    = RegenGreen.copy(alpha = 0.15f),
                                icon     = Icons.Filled.CheckCircle,
                                iconTint = RegenGreen,
                                onDismiss = { scheduleChanged = null }
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        // Wi-Fi-only toggle — restricts scheduled backups to unmetered
                        // networks so they don't eat a limited mobile-data plan.
                        Row(
                            modifier          = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.wifi_only_label),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    stringResource(R.string.wifi_only_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked         = telegramWifiOnly,
                                onCheckedChange = { telegramManager.setWifiOnly(it) },
                                thumbContent = if (!telegramWifiOnly) {
                                    {
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .background(ToggleUncheckedTrack, CircleShape)
                                        )
                                    }
                                } else null,
                                colors = SwitchDefaults.colors(
                                    uncheckedThumbColor  = Color.White,
                                    uncheckedTrackColor  = ToggleUncheckedTrack,
                                    uncheckedBorderColor = ToggleUncheckedTrack
                                )
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                    // Manual send button
                    Button(
                        onClick = {
                            telegramManager.resetState()
                            scope.launch { manager.backupToTelegram() }
                        },
                        enabled  = !isBusy && !telegramBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (telegramBusy) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color       = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (telegramBusy) stringResource(R.string.sending) else stringResource(R.string.send_backup_now_action))
                    }

                    Spacer(Modifier.height(4.dp))

                    TextButton(
                        onClick  = { telegramManager.clearConfig() },
                        enabled  = !telegramBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.disconnect_bot_action),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                } else {
                    // ── Setup state ───────────────────────────────────────

                    StatusBanner(
                        text = stringResource(R.string.telegram_setup_info),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        icon = Icons.Filled.Info,
                        iconTint = MaterialTheme.colorScheme.primary
                    )

                    Spacer(Modifier.height(10.dp))

                    Text(
                        stringResource(R.string.telegram_instructions),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value         = tokenInput,
                        onValueChange = { tokenInput = it },
                        label         = { Text(stringResource(R.string.bot_token_label)) },
                        placeholder   = { Text("123456789:ABCdef…") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                        enabled       = !telegramBusy
                    )

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick  = { scope.launch { telegramManager.validateAndSave(tokenInput) } },
                        enabled  = tokenInput.isNotBlank() && !telegramBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (telegramBusy) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color       = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Filled.Check, null, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (telegramBusy) stringResource(R.string.uploading) else stringResource(R.string.validate_save_action))
                    }
                }
            }
                    Spacer(Modifier.height(8.dp))
                    SectionCard(title = stringResource(R.string.restore_from_telegram_label), icon = Icons.Filled.CloudDownload) {
                        Text(
                        stringResource(R.string.restore_warning_msg),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    // Reuse telegram state banner for download/delete progress and results
                    when (val s = telegramState) {
                        is TelegramManager.TelegramState.InProgress -> StatusBanner(
                            text    = s.message,
                            color   = MaterialTheme.colorScheme.primaryContainer,
                            icon    = Icons.Filled.HourglassTop,
                            loading = true
                        )
                        is TelegramManager.TelegramState.Success -> StatusBanner(
                            text      = s.message,
                            color     = RegenGreen.copy(alpha = 0.15f),
                            icon      = Icons.Filled.CheckCircle,
                            iconTint  = RegenGreen,
                            onDismiss = { telegramManager.resetState() }
                        )
                        is TelegramManager.TelegramState.Error -> StatusBanner(
                            text      = s.message,
                            color     = MaterialTheme.colorScheme.errorContainer,
                            icon      = Icons.Filled.Error,
                            iconTint  = MaterialTheme.colorScheme.error,
                            onDismiss = { telegramManager.resetState() }
                        )
                        else -> {}
                    }

                    if (telegramConfig == null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.connect_telegram_first),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.backups_in_chat_label),
                                style      = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            IconButton(
                                onClick = {
                                    telegramManager.resetState()
                                    telegramManager.clearTelegramBackups()
                                    telegramManager.listTelegramBackups()
                                },
                                enabled = !telegramBusy && !isBusy
                            ) {
                                Icon(Icons.Filled.Refresh, stringResource(R.string.refresh), modifier = Modifier.size(22.dp))
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        if (telegramBackups.isEmpty()) {
                            Text(
                                stringResource(R.string.scan_telegram_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            telegramBackups.forEachIndexed { index, backup ->
                                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                TelegramBackupListItem(
                                    backup    = backup,
                                    enabled   = !isBusy && !telegramBusy,
                                    onRestore = { telegramRestoreTarget = backup },
                                    onDelete  = { telegramDeleteTarget  = backup }
                                )
                            }
                        }
                    }
                }
                }
            }

            // ── Danger Zone ───────────────────────────────────────────────────
            item {
                var showResetConfirm by remember { mutableStateOf(false) }
                val resetBusy = backupState is LocalBackupManager.BackupState.InProgress
                SectionCard(title = stringResource(R.string.danger_zone_title), icon = Icons.Filled.DeleteForever) {
                    Text(
                        text = stringResource(R.string.danger_zone_desc),
                        style = MaterialTheme.typography.bodyLarge,
                        color = BydErrorRed
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick  = { showResetConfirm = true },
                        enabled  = !resetBusy && !isBusy,
                        modifier = Modifier.fillMaxWidth(),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = BydErrorRed
                        )
                    ) {
                        Icon(Icons.Filled.DeleteForever, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.reset_all_data_label))
                    }

                    if (showResetConfirm) {
                        AlertDialog(
                            onDismissRequest = { showResetConfirm = false },
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            icon = {
                                Icon(Icons.Filled.Warning, null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(32.dp))
                            },
                            title = { Text(stringResource(R.string.reset_confirm_title), fontWeight = FontWeight.Bold) },
                            text  = {
                                Text(
                                    stringResource(R.string.reset_confirm_msg),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showResetConfirm = false
                                        scope.launch {
                                            // 1. Back up first via LocalBackupManager so it
                                            //    appears in the Download list like any other backup
                                            manager.backupDatabase()
                                            // 2. Wipe via ViewModel (closes Room, deletes file)
                                            viewModel.resetDatabase()
                                            // 3. Restart app so Room recreates the schema cleanly.
                                            //    DiLink-5: skip auto-relaunch (SDK-injection race → head-unit
                                            //    boot loop, 2.13.0 incident). Process ends; user reopens.
                                            val launchIntent = context.packageManager
                                                .getLaunchIntentForPackage(context.packageName)
                                                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
                                            if (launchIntent != null && !DiLink5Platform.isDiLink5) {
                                                val pending = PendingIntent.getActivity(
                                                    context, 1, launchIntent,
                                                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                                                )
                                                val alarm = context.getSystemService(android.app.AlarmManager::class.java)
                                                alarm.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 800L, pending)
                                            }
                                            android.os.Process.killProcess(android.os.Process.myPid())
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = BydErrorRed
                                    )
                                ) { Text(stringResource(R.string.yes_reset_everything)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showResetConfirm = false }) { Text(stringResource(R.string.cancel)) }
                            }
                        )
                    }
                }
            }
    }


    // ── Telegram restore confirm dialog ───────────────────────────────────────
    telegramRestoreTarget?.let { backup ->
        RestoreConfirmDialog(
            description = backup.fileName,
            onConfirm = {
                val b = backup
                telegramRestoreTarget = null
                manager.resetState()
                telegramManager.resetState()
                scope.launch { manager.restoreFromTelegram(b) }
            },
            onDismiss = { telegramRestoreTarget = null }
        )
    }

    // ── Telegram delete confirm dialog ────────────────────────────────────────
    telegramDeleteTarget?.let { backup ->
        AlertDialog(
            onDismissRequest = { telegramDeleteTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.delete_backup_title)) },
            text  = { Text(stringResource(R.string.delete_telegram_backup_confirm, backup.fileName)) },
            confirmButton = {
                TextButton(onClick = {
                    val b = backup
                    telegramDeleteTarget = null
                    telegramManager.resetState()
                    scope.launch { telegramManager.deleteBackup(b) }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { telegramDeleteTarget = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // ── Restore confirm dialog ────────────────────────────────────────────────
    restoreTarget?.let { backup ->
        RestoreConfirmDialog(
            description = backup.name,
            onConfirm = {
                val b = backup
                restoreTarget = null
                manager.resetState()
                scope.launch { manager.restoreFromBackupFile(b) }
            },
            onDismiss = { restoreTarget = null }
        )
    }

    deleteTarget?.let { backup ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.delete_charging_session_title)) },
            text  = { Text(stringResource(R.string.delete_backup_confirm, backup.name)) },
            confirmButton = {
                TextButton(onClick = {
                    val b = backup
                    deleteTarget = null
                    val needsWritePermission = b.uri.scheme == "file" &&
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) != PackageManager.PERMISSION_GRANTED
                    if (needsWritePermission) {
                        pendingDeleteAfterPermission = b
                        writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        scope.launch { manager.deleteBackup(b) }
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
}

// ── Composable helpers ────────────────────────────────────────────────────────

@Composable
private fun GroupSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Section header row
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier              = Modifier.padding(start = 4.dp, bottom = 8.dp)
        ) {
            Icon(
                icon, null,
                modifier = Modifier.size(20.dp),
                tint     = MaterialTheme.colorScheme.primary
            )
            Text(
                title,
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.primary
            )
        }
        // Bordered container
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier            = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(icon, null, modifier = Modifier.size(22.dp))
                Text(
                    title,
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun StatusBanner(
    text: String,
    color: Color,
    icon: ImageVector,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    loading: Boolean = false,
    onDismiss: (() -> Unit)? = null
) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = color),
        shape    = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, "Dismiss", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun BackupListItem(
    backup: LocalBackupManager.BackupFile,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault()) }
    val sizeMb  = if (backup.sizeBytes < 1_048_576L)
        "%.0f KB".format(backup.sizeBytes / 1_024.0)
    else
        "%.1f MB".format(backup.sizeBytes / 1_048_576.0)
    val date    = remember(backup.dateModified) { dateFmt.format(Date(backup.dateModified)) }

    Row(
        modifier          = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Backup, null,
            modifier = Modifier.size(22.dp),
            tint     = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(backup.name,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium)
            Text("$date  ·  $sizeMb",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (backup.source.isNotEmpty()) {
                Text(backup.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        TextButton(onClick = onRestore, enabled = enabled) { Text(stringResource(R.string.restore_section_label)) }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                Icons.Filled.DeleteOutline, "Delete backup",
                modifier = Modifier.size(20.dp),
                tint     = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun TelegramBackupListItem(
    backup: TelegramManager.TelegramBackupFile,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault()) }
    val sizeMb  = if (backup.fileSize < 1_048_576L)
        "%.0f KB".format(backup.fileSize / 1_024.0)
    else
        "%.1f MB".format(backup.fileSize / 1_048_576.0)
    val date = remember(backup.date) { dateFmt.format(Date(backup.date)) }

    Row(
        modifier          = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.CloudDownload, null,
            modifier = Modifier.size(22.dp),
            tint     = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                backup.fileName,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                "$date  ·  $sizeMb",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onRestore, enabled = enabled) { Text(stringResource(R.string.restore_section_label)) }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                Icons.Filled.DeleteOutline, stringResource(R.string.delete_backup_action),
                modifier = Modifier.size(20.dp),
                tint     = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun RestoreConfirmDialog(
    description: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        icon  = {
            Icon(Icons.Filled.Warning, null,
                tint     = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp))
        },
        title = { Text(stringResource(R.string.restore_database_title)) },
        text  = {
            Text(stringResource(R.string.restore_database_msg, description))
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors  = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text(stringResource(R.string.restore_section_label)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
@Composable
private fun DatabaseTrimSection(
    scope: kotlinx.coroutines.CoroutineScope,
    isBusy: Boolean,
) {
    val context = LocalContext.current
    val trimState by DatabaseTrimmer.state.collectAsState()
    var lastRun by remember { mutableStateOf(DatabaseTrimmer.getLastRun(context)) }
    var showConfirm by remember { mutableStateOf(false) }

    // Refresh last-run when a Success state arrives so the label updates immediately.
    LaunchedEffect(trimState) {
        if (trimState is DatabaseTrimmer.State.Success) {
            lastRun = DatabaseTrimmer.getLastRun(context)
        }
    }

    // Auto-restart after VACUUM completes — Room was closed to allow VACUUM, so
    // the process must restart for the schema to reopen cleanly.
    LaunchedEffect(trimState) {
        val s = trimState
        if (s is DatabaseTrimmer.State.Success && s.restartRequired) {
            delay(3000)
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
            if (launchIntent != null) {
                val pending = PendingIntent.getActivity(
                    context, 2, launchIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val alarm = context.getSystemService(AlarmManager::class.java)
                alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 500L, pending)
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    val running = trimState is DatabaseTrimmer.State.InProgress
    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault()) }

    GroupSection(title = stringResource(R.string.trim_database_label), icon = Icons.Filled.CleaningServices) {
        SectionCard(title = stringResource(R.string.trim_database_label), icon = Icons.Filled.CleaningServices) {
            Text(
                stringResource(R.string.trim_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            Text(
                stringResource(R.string.trim_warning_msg),
                style      = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color      = BydErrorRed
            )

            Spacer(Modifier.height(8.dp))

            // Last-run info
            val lastRunText = lastRun?.let { stringResource(R.string.last_trimmed_label, dateFmt.format(Date(it.timestamp))) }
                ?: stringResource(R.string.never_trimmed)
            Text(
                lastRunText,
                style      = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.onSurface
            )
            lastRun?.let {
                Text(
                    it.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))

            // Status banner during/after run
            when (val s = trimState) {
                is DatabaseTrimmer.State.InProgress -> StatusBanner(
                    text    = s.phase,
                    color   = MaterialTheme.colorScheme.primaryContainer,
                    icon    = Icons.Filled.HourglassTop,
                    loading = true
                )
                is DatabaseTrimmer.State.Success -> StatusBanner(
                    text      = if (s.restartRequired)
                        stringResource(R.string.trim_complete_msg)
                    else
                        stringResource(R.string.trim_skip_vacuum_msg),
                    color     = RegenGreen.copy(alpha = 0.15f),
                    icon      = Icons.Filled.CheckCircle,
                    iconTint  = RegenGreen,
                    onDismiss = if (s.restartRequired) null else ({ DatabaseTrimmer.resetState() })
                )
                is DatabaseTrimmer.State.Error -> StatusBanner(
                    text      = stringResource(R.string.trim_failed_msg, s.message),
                    color     = MaterialTheme.colorScheme.errorContainer,
                    icon      = Icons.Filled.Error,
                    iconTint  = MaterialTheme.colorScheme.error,
                    onDismiss = { DatabaseTrimmer.resetState() }
                )
                else -> {}
            }

            if (trimState !is DatabaseTrimmer.State.Idle) Spacer(Modifier.height(8.dp))

            Button(
                onClick  = { showConfirm = true },
                enabled  = !running && !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color       = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Filled.CleaningServices, null, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(if (running) stringResource(R.string.running) else stringResource(R.string.trim_now_action))
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            icon = {
                Icon(Icons.Filled.CleaningServices, null,
                    tint     = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp))
            },
            title = { Text(stringResource(R.string.trim_confirm_title), fontWeight = FontWeight.Bold) },
            text  = {
                Text(
                    "Diagnostic data for trips older than 45 days will be cleared, trip points " +
                    "older than 60 days will be downsampled to one per minute, and per-second AC " +
                    "charging history older than 45 days will be deleted. DC charging history is " +
                    "preserved.\n\n" +
                    "Trip summaries, statistics and charging session totals are not affected.\n\n" +
                    "Once the rows are processed the telemetry service is briefly stopped, the " +
                    "database is vacuumed to reclaim disk space, and the app will close and reopen " +
                    "automatically. Total time: ~1–2 minutes on a large database."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showConfirm = false
                    scope.launch(Dispatchers.IO) { DatabaseTrimmer.trim(context) }
                }) { Text(stringResource(R.string.trim_now_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
