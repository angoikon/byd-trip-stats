package com.byd.tripstats.ui.screens

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.byd.tripstats.data.backup.LocalBackupManager
import com.byd.tripstats.data.backup.TelegramManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalBackupScreen(
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

    val isBusy = backupState is LocalBackupManager.BackupState.InProgress
    val telegramBusy = telegramState is TelegramManager.TelegramState.InProgress

    var restoreTarget by remember { mutableStateOf<LocalBackupManager.BackupFile?>(null) }

    // ── Auto-dismiss Success banners after 4 seconds ──────────────────────────
    LaunchedEffect(backupState) {
        if (backupState is LocalBackupManager.BackupState.Success &&
            !(backupState as LocalBackupManager.BackupState.Success).restartRequired) {
            delay(4000)
            manager.resetState()
        }
    }
    LaunchedEffect(telegramState) {
        if (telegramState is TelegramManager.TelegramState.Success) {
            delay(4000)
            telegramManager.resetState()
        }
    }

    // ── Auto-restart after successful restore ─────────────────────────────────
    LaunchedEffect(backupState) {
        val s = backupState
        if (s is LocalBackupManager.BackupState.Success && s.restartRequired) {
            delay(2000)
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
            if (launchIntent != null) {
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

    // ── Load local backup list on first open ──────────────────────────────────
    LaunchedEffect(Unit) {
        manager.scanLocalBackups()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Database Backup & Restore",
                        fontSize = 22.sp, fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, "Back", modifier = Modifier.size(28.dp))
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
                        color     = Color(0xFF1B5E20).copy(alpha = 0.15f),
                        icon      = Icons.Filled.CheckCircle,
                        iconTint  = Color(0xFF4CAF50),
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

            // ── Backup to Downloads ───────────────────────────────────────────
            item {
                SectionCard(title = "Backup to Downloads", icon = Icons.Filled.CloudUpload) {
                    Text(
                        "Saves the full trip database to:\nDownloads/BydTripStats/byd_stats_backup_DATE.db",
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
                        Text(if (isBusy) "Working…" else "Backup Now")
                    }
                }
            }

            // ── Telegram backup ───────────────────────────────────────────────
            item {
                var tokenInput by remember { mutableStateOf(telegramConfig?.token ?: "") }

                SectionCard(title = "Telegram Backup", icon = Icons.Filled.Send) {
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
                            color     = Color(0xFF1B5E20).copy(alpha = 0.15f),
                            icon      = Icons.Filled.CheckCircle,
                            iconTint  = Color(0xFF4CAF50),
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
                                tint = Color(0xFF4CAF50)
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
                                        "Last auto-backup: $it",
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
                                "Automatic backup",
                                style    = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked         = telegramAuto,
                                onCheckedChange = { telegramManager.setAutoEnabled(it) }
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
                                "Backup interval",
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
                                                scheduleChanged = s.label
                                            }
                                        }
                                    )
                                    Text(
                                        text  = s.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                }
                            }

                            scheduleChanged?.let { label ->
                                Spacer(Modifier.height(4.dp))
                                StatusBanner(
                                    text     = "Schedule updated to $label. Next auto-backup will follow the new interval.",
                                    color    = Color(0xFF1B5E20).copy(alpha = 0.15f),
                                    icon     = Icons.Filled.CheckCircle,
                                    iconTint = Color(0xFF4CAF50),
                                    onDismiss = { scheduleChanged = null }
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
                                Icon(Icons.Filled.Send, null, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(if (telegramBusy) "Sending…" else "Send Backup Now")
                        }

                        Spacer(Modifier.height(4.dp))

                        TextButton(
                            onClick  = { telegramManager.clearConfig() },
                            enabled  = !telegramBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Disconnect bot",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                    } else {
                        // ── Setup state ───────────────────────────────────────

                        StatusBanner(
                            text = "Once connected, backups can be sent manually or on a schedule. " +
                                       "Each backup lands as a .db file in your private chat, " +
                                       "accessible from any device with Telegram.",
                            color = MaterialTheme.colorScheme.primaryContainer,
                            icon = Icons.Filled.Info,
                            iconTint = MaterialTheme.colorScheme.primary
                        )

                        Spacer(Modifier.height(10.dp))

                        Text(
                            "1. Message @BotFather on Telegram → /newbot → copy the token\n" +
                            "2. Send any message to your new bot\n" +
                            "3. Paste the token below and tap Validate",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(10.dp))

                        OutlinedTextField(
                            value         = tokenInput,
                            onValueChange = { tokenInput = it },
                            label         = { Text("Bot Token") },
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
                            Text(if (telegramBusy) "Validating…" else "Validate & Save")
                        }
                    }
                }
            }

            // ── Restore ───────────────────────────────────────────────────────
            item {
                SectionCard(title = "Restore", icon = Icons.Filled.CloudDownload) {
                    Text(
                        "Restoring will replace ALL current trip data. The app will close and reopen automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text(
                            "Available backups:",
                            style      = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        IconButton(
                            onClick  = { scope.launch { manager.scanLocalBackups() } },
                            enabled  = !isBusy
                        ) {
                            Icon(Icons.Filled.Refresh, "Refresh", modifier = Modifier.size(22.dp))
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    if (localBackups.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "No backups found. Run a backup first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        localBackups.forEachIndexed { index, backup ->
                            if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            BackupListItem(
                                backup    = backup,
                                enabled   = !isBusy,
                                onRestore = { restoreTarget = backup }
                            )
                        }
                    }
                }
            }
        }
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
}

// ── Composable helpers ────────────────────────────────────────────────────────

@Composable
private fun SectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
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
    onRestore: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault()) }
    val sizeMb  = "%.1f MB".format(backup.sizeBytes / 1_048_576.0)
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
        TextButton(onClick = onRestore, enabled = enabled) { Text("Restore") }
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
        icon  = {
            Icon(Icons.Filled.Warning, null,
                tint     = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp))
        },
        title = { Text("Restore Database?") },
        text  = {
            Text(
                "This will permanently replace ALL current trip data with $description.\n\n" +
                "The app will close and reopen automatically after restore."
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors  = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Restore") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}