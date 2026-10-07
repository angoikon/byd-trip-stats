package com.byd.tripstats.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.ui.res.stringResource
import com.byd.tripstats.R
import com.byd.tripstats.ui.components.BrandSwitch
import com.byd.tripstats.data.backup.LocalBackupManager
import com.byd.tripstats.data.backup.TelegramManager
import com.byd.tripstats.data.entitlement.EntitlementManager
import com.byd.tripstats.data.notify.TelegramNotifier
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.ui.components.BrandNavigationBar
import com.byd.tripstats.ui.theme.*
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import com.byd.tripstats.util.AppRestart
import com.byd.tripstats.worker.DatabaseTrimmer
import kotlinx.coroutines.CoroutineScope
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
    // The backup is snapshotted and compressed before TelegramManager's upload starts; this covers
    // that stretch too, so Send backup now can't be tapped again while it looks idle.
    val telegramPreparing by manager.telegramPreparing.collectAsState()
    val telegramActive = telegramBusy || telegramPreparing
    val isPro by EntitlementManager.isPro.collectAsState()  // SD card backup is Pro-gated

    // A backup that has been checked and is waiting for the user to confirm it — see
    // LocalBackupManager.prepareRestore. Every restore path ends up here.
    val pendingRestore by manager.pendingRestore.collectAsState()
    var deleteTarget  by remember { mutableStateOf<LocalBackupManager.BackupFile?>(null) }
    var pendingDeleteAfterPermission by remember { mutableStateOf<LocalBackupManager.BackupFile?>(null) }
    var pendingSdBackupAfterPermission by remember { mutableStateOf(false) }
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

    // ── Auto-restart after a restore ──────────────────────────────────────────
    // Also after one that failed mid-swap: Room is closed for good by then.
    LaunchedEffect(backupState) {
        val s = backupState
        val restart = (s is LocalBackupManager.BackupState.Success && s.restartRequired) ||
            (s is LocalBackupManager.BackupState.Error && s.restartRequired)
        if (restart) {
            delay(2000)
            AppRestart.restart(
                context     = context,
                body        = context.getString(R.string.reopen_after_restore),
                reason      = "db-restore",
                requestCode = AppRestart.REQUEST_RESTORE,
            )
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
      Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
        // ── Backup state banner ───────────────────────────────────────────────
        // Pinned above the list instead of scrolling with it: the backup and restore buttons
        // sit well down the screen, so a banner at the top of the list reported their progress
        // and errors off-screen. Here it shows wherever the list is scrolled to, and the list
        // keeps its place.
        // The last message is kept so the banner still has it while it slides away.
        val lastBanner = remember { arrayOfNulls<LocalBackupManager.BackupState>(1) }
        if (backupState !is LocalBackupManager.BackupState.Idle) lastBanner[0] = backupState
        AnimatedVisibility(
            visible = backupState !is LocalBackupManager.BackupState.Idle,
            enter   = expandVertically() + fadeIn(),
            exit    = shrinkVertically() + fadeOut()
        ) {
            Box(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)) {
                lastBanner[0]?.let { BackupStateBanner(it, onDismiss = { manager.resetState() }) }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── SETTINGS group ────────────────────────────────────────────────
            // First on the screen deliberately: it used to sit under the restore list,
            // which grows with every backup, and was easy to miss entirely.
            item {
                SettingsBackupSection(
                    manager   = manager,
                    viewModel = viewModel,
                    scope     = scope,
                    isBusy    = isBusy,
                )
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
                                onRestore = {
                                    manager.resetState()
                                    scope.launch {
                                        manager.prepareRestore(backup.uri, backup.name, manager.settingsFileFor(backup.name))
                                    }
                                },
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
                                manager.resetState()
                                scope.launch { manager.prepareRestore(Uri.fromFile(file), file.name) }
                            }
                        )
                    }
                }
                }
            }

            // ── TELEGRAM group ────────────────────────────────────────────────
            item {
                GroupSection(title = stringResource(R.string.app_mgmt_telegram_label), icon = Icons.AutoMirrored.Filled.Send) {
                    SectionCard(title = stringResource(R.string.telegram_backup_label), icon = Icons.AutoMirrored.Filled.Send) {
                Text(
                    stringResource(R.string.telegram_setup_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
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
                        BrandSwitch(
                            checked         = telegramAuto,
                            onCheckedChange = { telegramManager.setAutoEnabled(it) },
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
                            BrandSwitch(
                                checked         = telegramWifiOnly,
                                onCheckedChange = { telegramManager.setWifiOnly(it) },
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
                        enabled  = !isBusy && !telegramActive,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (telegramActive) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color       = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                telegramBusy      -> stringResource(R.string.sending)
                                telegramPreparing -> stringResource(R.string.running)
                                else              -> stringResource(R.string.send_backup_now_action)
                            }
                        )
                    }

                } else {
                    // ── Not linked yet ────────────────────────────────────
                    // The bot is a connection, and connections are set up on the Connections
                    // tab — including this one, which several features share. Backup only
                    // says where to go rather than offering a second place to paste a token.
                    StatusBanner(
                        text = stringResource(R.string.telegram_setup_in_connections),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        icon = Icons.Filled.Info,
                        iconTint = MaterialTheme.colorScheme.primary
                    )
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
                                enabled = !telegramActive && !isBusy
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
                                    enabled   = !isBusy && !telegramActive,
                                    onRestore = {
                                        manager.resetState()
                                        telegramManager.resetState()
                                        scope.launch { manager.prepareTelegramRestore(backup) }
                                    },
                                    onDelete  = { telegramDeleteTarget  = backup }
                                )
                            }
                        }
                    }
                }
                }
            }

            // ── MAINTENANCE group ─────────────────────────────────────────────
            item {
                DatabaseTrimSection(scope = scope, isBusy = isBusy)
            }

            // ── Danger Zone ───────────────────────────────────────────────────
            item {
                var showResetConfirm by remember { mutableStateOf(false) }
                val resetBusy = backupState is LocalBackupManager.BackupState.InProgress
                GroupSection(title = stringResource(R.string.danger_zone_title), icon = Icons.Filled.DeleteForever) {
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
                                            AppRestart.restart(
                                                context     = context,
                                                body        = context.getString(R.string.reopen_after_reset),
                                                reason      = "db-reset",
                                                requestCode = AppRestart.REQUEST_RESET,
                                                delayMs     = 800L,
                                            )
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
    }
      }


    // ── Restore confirm dialog ────────────────────────────────────────────────
    // Shown once the backup has been decoded and checked, whichever list it came from.
    pendingRestore?.let { pending ->
        RestoreConfirmDialog(
            pending   = pending,
            onConfirm = { alsoSettings -> scope.launch { manager.commitRestore(alsoSettings) } },
            onDismiss = { manager.cancelRestore() }
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

/** The backup / restore progress, result or error — pinned above the screen's list. */
@Composable
private fun BackupStateBanner(state: LocalBackupManager.BackupState, onDismiss: () -> Unit) {
    when (state) {
        is LocalBackupManager.BackupState.InProgress -> StatusBanner(
            text    = state.message,
            color   = MaterialTheme.colorScheme.primaryContainer,
            icon    = Icons.Filled.HourglassTop,
            loading = true
        )
        is LocalBackupManager.BackupState.Success -> StatusBanner(
            text      = state.message,
            color     = RegenGreen.copy(alpha = 0.15f),
            icon      = Icons.Filled.CheckCircle,
            iconTint  = RegenGreen,
            onDismiss = onDismiss
        )
        is LocalBackupManager.BackupState.Error -> StatusBanner(
            text      = state.message,
            color     = MaterialTheme.colorScheme.errorContainer,
            icon      = Icons.Filled.Error,
            iconTint  = MaterialTheme.colorScheme.error,
            onDismiss = onDismiss
        )
        LocalBackupManager.BackupState.Idle -> {}
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

/**
 * Confirms a restore that has already been checked, showing what the backup holds next to
 * what it replaces — the numbers that would have caught a wrong pick before it cost anything.
 *
 * The settings file saved alongside the backup ([LocalBackupManager.PendingRestore.settings])
 * is null when there isn't one (a backup from before settings were included, one whose file
 * was deleted, a Telegram or browsed file). When present the user chooses whether to restore
 * it too — restoring an old database onto a working install shouldn't silently replace the
 * current broker password or tariff.
 */
@Composable
private fun RestoreConfirmDialog(
    pending: LocalBackupManager.PendingRestore,
    onConfirm: (restoreSettings: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val settingsFileName = pending.settings?.name
    var restoreSettings by remember(settingsFileName) { mutableStateOf(settingsFileName != null) }
    val current = pending.current
    val replacesData = current != null && (current.trips > 0 || current.chargingSessions > 0)

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
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.restore_database_msg, pending.label))
                Spacer(Modifier.height(12.dp))
                Text(
                    summaryLine(R.string.restore_contents_backup, pending.backup),
                    style      = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (current != null) {
                    Text(
                        summaryLine(R.string.restore_contents_current, current),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (pending.losesData) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.restore_loses_data_warning),
                        style      = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color      = MaterialTheme.colorScheme.error
                    )
                }
                if (replacesData) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.restore_safety_copy_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (settingsFileName == null) {
                    Text(
                        stringResource(R.string.restore_no_settings_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(
                        modifier          = Modifier
                            .fillMaxWidth()
                            .clickable { restoreSettings = !restoreSettings },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked         = restoreSettings,
                            onCheckedChange = { restoreSettings = it }
                        )
                        Spacer(Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.restore_with_settings_label),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                settingsFileName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(restoreSettings) },
                colors  = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text(stringResource(R.string.restore_section_label)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** "trips: N (first – last) · charging sessions: M" for one side of the restore dialog. */
@Composable
private fun summaryLine(@androidx.annotation.StringRes res: Int, s: LocalBackupManager.DbSummary): String {
    val fmt = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM) }
    val range = if (s.firstTrip == null || s.lastTrip == null) "—"
        else "${fmt.format(java.util.Date(s.firstTrip))} – ${fmt.format(java.util.Date(s.lastTrip))}"
    return stringResource(res, s.trips, range, s.chargingSessions)
}

/**
 * Settings backup: the sibling of the database backup above. One is written automatically
 * with every database backup; this card exists for the standalone cases — exporting after
 * changing a setting, importing on a fresh install, and choosing whether the file carries
 * credentials.
 */
@Composable
private fun SettingsBackupSection(
    manager: LocalBackupManager,
    viewModel: DashboardViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    isBusy: Boolean,
) {
    val context  = LocalContext.current
    val activity = context as? android.app.Activity
    val prefs    = remember { PreferencesManager(context.applicationContext) }
    val includeCredentials by prefs.settingsBackupIncludeCredentials.collectAsState(initial = true)
    val settingsFiles by manager.settingsFiles.collectAsState()

    var restoreTarget by remember { mutableStateOf<LocalBackupManager.SettingsFile?>(null) }
    var deleteTarget  by remember { mutableStateOf<LocalBackupManager.SettingsFile?>(null) }

    // Deleting the copy in the public Download folder is a direct filesystem write on this
    // legacy-storage setup, so it needs WRITE_EXTERNAL_STORAGE — not granted on a fresh
    // install. Same pattern as the database delete above.
    var pendingDelete by remember { mutableStateOf<LocalBackupManager.SettingsFile?>(null) }
    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val target = pendingDelete
        pendingDelete = null
        if (granted && target != null) scope.launch { manager.deleteSettingsFile(target) }
    }

    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault()) }

    GroupSection(title = stringResource(R.string.settings_backup_label), icon = Icons.Filled.Tune) {
    SectionCard(title = stringResource(R.string.settings_backup_label), icon = Icons.Filled.Tune) {
        Text(
            stringResource(R.string.settings_backup_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_include_credentials_label),
                    style      = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.settings_include_credentials_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked         = includeCredentials,
                onCheckedChange = { scope.launch { prefs.saveSettingsBackupIncludeCredentials(it) } }
            )
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick  = {
                manager.resetState()
                scope.launch { manager.backupSettings() }
            },
            enabled  = !isBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Save, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (isBusy) stringResource(R.string.running) else stringResource(R.string.settings_backup_now_action))
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.available_settings_files_label),
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(
                onClick = { scope.launch { manager.scanSettingsFiles() } },
                enabled = !isBusy
            ) {
                Icon(Icons.Filled.Refresh, stringResource(R.string.refresh), modifier = Modifier.size(22.dp))
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        if (settingsFiles.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.no_settings_files),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            settingsFiles.forEachIndexed { index, file ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier          = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Tune, null,
                        modifier = Modifier.size(22.dp),
                        tint     = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(file.name,
                            style      = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium)
                        Text(
                            "${dateFmt.format(Date(file.dateModified))}  ·  %.1f KB".format(file.sizeBytes / 1_024.0),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (file.source.isNotEmpty()) {
                            Text(file.source,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    TextButton(onClick = { restoreTarget = file }, enabled = !isBusy) {
                        Text(stringResource(R.string.restore_section_label))
                    }
                    IconButton(onClick = { deleteTarget = file }, enabled = !isBusy) {
                        Icon(
                            Icons.Filled.DeleteOutline, stringResource(R.string.delete),
                            modifier = Modifier.size(20.dp),
                            tint     = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
    }

    restoreTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            icon  = {
                Icon(Icons.Filled.Tune, null,
                    tint     = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp))
            },
            title = { Text(stringResource(R.string.restore_settings_title), fontWeight = FontWeight.Bold) },
            text  = { Text(stringResource(R.string.restore_settings_msg, file.name)) },
            confirmButton = {
                Button(onClick = {
                    val f = file
                    restoreTarget = null
                    manager.resetState()
                    scope.launch {
                        val result = manager.restoreSettings(f)
                        if (result != null) {
                            // The goals live behind an Activity-scoped ViewModel that read
                            // them at construction; without this the dashboard would show
                            // the old ones until the process restarted.
                            viewModel.reloadTripGoals()
                            if (result.localeChanged) {
                                // Let the success banner be readable before the Activity
                                // restarts to pick up the restored language.
                                delay(1500)
                                activity?.recreate()
                            }
                        }
                    }
                }) { Text(stringResource(R.string.restore_section_label)) }
            },
            dismissButton = {
                TextButton(onClick = { restoreTarget = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    deleteTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.delete_settings_title)) },
            text  = { Text(stringResource(R.string.delete_backup_confirm, file.name)) },
            confirmButton = {
                TextButton(onClick = {
                    val f = file
                    deleteTarget = null
                    val needsWritePermission = Build.VERSION.SDK_INT <= 32 &&
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) != PackageManager.PERMISSION_GRANTED
                    if (needsWritePermission) {
                        pendingDelete = f
                        writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        scope.launch { manager.deleteSettingsFile(f) }
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
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
    // the process must restart for the schema to reopen cleanly. Only reached on
    // DiLink-3: DatabaseTrimmer skips VACUUM (and so restartRequired) everywhere else.
    LaunchedEffect(trimState) {
        val s = trimState
        if (s is DatabaseTrimmer.State.Success && s.restartRequired) {
            delay(3000)
            AppRestart.restart(
                context     = context,
                body        = context.getString(R.string.reopen_after_trim),
                reason      = "db-trim",
                requestCode = AppRestart.REQUEST_TRIM,
            )
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
