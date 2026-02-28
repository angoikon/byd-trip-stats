package com.byd.tripstats.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalBackupScreen(
    onNavigateBack: () -> Unit
) {
    val context   = LocalContext.current
    val manager   = remember { LocalBackupManager.getInstance(context) }
    val scope     = rememberCoroutineScope()

    val backupState  by manager.state.collectAsState()
    val localBackups by manager.localBackups.collectAsState()

    val telegramManager = remember { TelegramManager.getInstance(context) }
    val telegramState by telegramManager.state.collectAsState()
    val telegramConfig = telegramManager.config

    val isBusy = backupState is LocalBackupManager.BackupState.InProgress

    var restoreTarget    by remember { mutableStateOf<LocalBackupManager.BackupFile?>(null) }
    var pickerRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var filePickerError  by remember { mutableStateOf<String?>(null) }

    // ── Restore file picker ───────────────────────────────────────────────────
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Persist read permission across restarts
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            pickerRestoreUri = uri
        }
    }

    LaunchedEffect(backupState) {
        val s = backupState
        if (s is LocalBackupManager.BackupState.Success && s.restartRequired) {
            kotlinx.coroutines.delay(2000)
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

            // ── Status banner ─────────────────────────────────────────────────
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
                filePickerError?.let {
                    Spacer(Modifier.height(8.dp))
                    StatusBanner(
                        text      = it,
                        color     = MaterialTheme.colorScheme.errorContainer,
                        icon      = Icons.Filled.Warning,
                        iconTint  = MaterialTheme.colorScheme.error,
                        onDismiss = { filePickerError = null }
                    )
                }
            }

            // ── Backup to Downloads ───────────────────────────────────────────
            item {
                SectionCard(title = "Backup to Downloads", icon = Icons.Filled.CloudUpload) {
                    Text(
                        "Saves the full trip database to:\n" +
                            "Downloads/BydTripStats/byd_stats_backup_DATE.db",
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


            // ── Telegram backup ─────────────────────────────────────────────────
            item {
                var tokenInput by remember { mutableStateOf(telegramConfig?.token ?: "") }
                val telegramBusy = telegramState is TelegramManager.TelegramState.InProgress

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
                        // Configured state — show bot info + backup button
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint     = Color(0xFF4CAF50)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "@${telegramConfig.botName}",
                                    style      = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "Chat ID: ${telegramConfig.chatId}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

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
                            Text(if (telegramBusy) "Sending…" else "Send Backup to Telegram")
                        }

                        TextButton(
                            onClick  = { telegramManager.clearConfig() },
                            enabled  = !telegramBusy
                        ) {
                            Text(
                                "Disconnect bot",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                    } else {
                        // Setup state — show token input
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
                            onClick = {
                                scope.launch { telegramManager.validateAndSave(tokenInput) }
                            },
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
                        "Restoring will replace ALL current trip data and restart the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(12.dp))

                    // Option A: file picker
                    OutlinedButton(
                        onClick = {
                            try {
                                filePickerLauncher.launch(
                                    arrayOf("application/octet-stream", "*/*")
                                )
                            } catch (e: Exception) {
                                filePickerError = "No file manager found on this device.\n" +
                                    "Use the folder scan below instead."
                            }
                        },
                        enabled  = !isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Pick File (File Manager)")
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    // Option B: folder scan
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text(
                            "Or pick from Downloads/BydTripStats/:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        IconButton(
                            onClick  = { scope.launch { manager.scanLocalBackups() } },
                            enabled  = !isBusy
                        ) {
                            Icon(Icons.Filled.Refresh, "Refresh", modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }

            // ── Downloads backup list ─────────────────────────────────────────
            if (localBackups.isEmpty()) {
                item {
                    Text(
                        "No backups found in Downloads/BydTripStats/.\n" +
                            "Run a backup first, or place a .db file there manually.",
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            } else {
                items(localBackups, key = { it.uri.toString() }) { backup ->
                    BackupListItem(
                        backup    = backup,
                        enabled   = !isBusy,
                        onRestore = { restoreTarget = backup }
                    )
                }
            }
        }
    }

    // ── Restore confirm — file picker ─────────────────────────────────────────
    pickerRestoreUri?.let { uri ->
        RestoreConfirmDialog(
            description = "the selected file",
            onConfirm = {
                val u = uri
                pickerRestoreUri = null
                manager.resetState()
                scope.launch { manager.restoreFromUri(u) }
            },
            onDismiss = { pickerRestoreUri = null }
        )
    }

    // ── Restore confirm — folder scan ─────────────────────────────────────────
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
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier              = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement   = Arrangement.spacedBy(4.dp)
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

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Backup, null,
                modifier = Modifier.size(24.dp),
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
                    "The app will restart automatically after restore."
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