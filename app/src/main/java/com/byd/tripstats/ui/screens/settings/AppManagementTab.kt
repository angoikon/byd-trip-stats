package com.byd.tripstats.ui.screens.settings

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.byd.tripstats.R
import com.byd.tripstats.sdk.DiLink5Platform
import com.byd.tripstats.ui.theme.BydErrorRed
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun AppManagementTab(
    viewModel         : DashboardViewModel,
    context           : Context,
    onNavigateToBackup: () -> Unit,
    scope             : CoroutineScope
) {
    val lastBackup     = remember { viewModel.listDatabaseBackups().firstOrNull() }
    val lastBackupLabel = remember(lastBackup) {
        if (lastBackup == null) context.getString(R.string.app_mgmt_no_local_backup)
        else {
            val sdf = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault())
            context.getString(R.string.last_backup_label, sdf.format(java.util.Date(lastBackup.lastModified())))
        }
    }

    var showResetConfirm by remember { mutableStateOf(false) }

    Column(
        modifier            = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(icon = Icons.Filled.Backup, title = stringResource(R.string.backup_restore_title))

        // Two-column summary row
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BackupSummaryCard(
                modifier   = Modifier.weight(1f),
                icon       = Icons.Filled.Storage,
                title      = stringResource(R.string.app_mgmt_backup_local_label),
                body       = "Download/BydTripStats/",
                statusLine = lastBackupLabel,
                statusOk   = lastBackup != null
            )
            BackupSummaryCard(
                modifier   = Modifier.weight(1f),
                icon       = Icons.AutoMirrored.Filled.Send,
                title      = stringResource(R.string.app_mgmt_telegram_label),
                body       = stringResource(R.string.app_mgmt_private_bot_desc),
                statusLine = stringResource(R.string.app_mgmt_manual_scheduled),
                statusOk   = true
            )
        }

        Button(onClick = onNavigateToBackup, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Backup, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.open_backup_restore_action), fontSize = 16.sp)
        }

        HorizontalDivider()

        VehicleCompatibilitySection(context = context, scope = scope)

        HorizontalDivider()

        AppDiagnosticsCard()

        HorizontalDivider()

        WebCompanionSection(context = context, scope = scope)
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            icon  = { Icon(Icons.Filled.Warning, null,
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp)) },
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
                            viewModel.resetDatabase()
                            // DiLink-5: skip auto-relaunch — kill+relaunch races the SDK
                            // injection and boot-loops the head unit (2.13.0 incident). User reopens.
                            val launchIntent = context.packageManager
                                .getLaunchIntentForPackage(context.packageName)
                                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
                            if (launchIntent != null && !DiLink5Platform.isDiLink5) {
                                val pending = PendingIntent.getActivity(
                                    context, 1, launchIntent,
                                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                                )
                                val alarm = context.getSystemService(AlarmManager::class.java)
                                alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 800L, pending)
                            }
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BydErrorRed)
                ) { Text(stringResource(R.string.yes_reset_everything)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
