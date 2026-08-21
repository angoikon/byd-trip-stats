package com.byd.tripstats.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.data.backup.TelegramManager
import com.byd.tripstats.sdk.VehicleCompatibilityProbe
import com.byd.tripstats.ui.theme.BydElectricAzure
import com.byd.tripstats.ui.theme.ToggleUncheckedTrack
import com.byd.tripstats.util.QrCodeGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
internal fun VehicleCompatibilitySection(context: Context, scope: CoroutineScope) {
    val isEnabled by VehicleCompatibilityProbe.isEnabled.collectAsState()
    val entryCount by VehicleCompatibilityProbe.entryCount.collectAsState()
    val lastCapture by VehicleCompatibilityProbe.lastCaptureAt.collectAsState()
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var privacyExpanded by rememberSaveable { mutableStateOf(false) }
    var qrUrl by remember { mutableStateOf<String?>(null) }
    var uploadInProgress by remember { mutableStateOf(false) }
    val telegram = remember { TelegramManager.getInstance(context) }
    val telegramConfig by telegram.config.collectAsState()
    val telegramState by telegram.state.collectAsState()

    SectionHeader(icon = Icons.Filled.BugReport, title = stringResource(R.string.vehicle_compat_label))

    Text(
        stringResource(R.string.compat_help_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Privacy banner
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { privacyExpanded = !privacyExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.Filled.PrivacyTip, null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        stringResource(R.string.privacy_notice_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Icon(
                    if (privacyExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null, modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
            AnimatedVisibility(visible = privacyExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                Text(
                    stringResource(R.string.compat_privacy_notice_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }

    // Enable toggle row
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.enable_compat_probe_label),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    val lastCaptureSuffix = lastCapture?.let { stringResource(R.string.last_capture_suffix, it.substringBefore('T')) } ?: ""
                    Text(
                        if (isEnabled)
                            stringResource(R.string.active_probe_status, entryCount, lastCaptureSuffix)
                        else
                            stringResource(R.string.probe_off_status),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { next ->
                        VehicleCompatibilityProbe.setEnabled(next)
                    },
                    thumbContent = if (!isEnabled) {
                        {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(ToggleUncheckedTrack, CircleShape)
                            )
                        }
                    } else null,
                    colors = SwitchDefaults.colors(
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = ToggleUncheckedTrack,
                        uncheckedBorderColor = ToggleUncheckedTrack
                    )
                )
            }

            // Action buttons
            val isSending = telegramState is TelegramManager.TelegramState.InProgress
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (telegramConfig == null) {
                            statusMessage = context.getString(R.string.compat_telegram_not_configured)
                            return@Button
                        }
                        scope.launch(Dispatchers.IO) {
                            try {
                                val file = VehicleCompatibilityProbe.exportReportFile()
                                telegram.sendFile(
                                    file,
                                    caption = "BYD Trip Stats — compatibility probe report\n" +
                                        "Entries: $entryCount · ${lastCapture?.substringBefore('T') ?: "no date"}"
                                )
                                launch(Dispatchers.Main) {
                                    statusMessage = context.getString(R.string.compat_sent_telegram)
                                }
                            } catch (e: Exception) {
                                launch(Dispatchers.Main) {
                                    statusMessage = context.getString(R.string.compat_telegram_failed, e.message ?: "")
                                }
                            }
                        }
                    },
                    enabled = entryCount > 0 && !isSending,
                    modifier = Modifier.weight(2f),
                    colors = ButtonDefaults.buttonColors(containerColor = BydElectricAzure)
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (isSending) stringResource(R.string.compat_sending_label) else "Telegram")
                }

                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            try {
                                VehicleCompatibilityProbe.exportReportFile(saveToDownloads = true)
                                launch(Dispatchers.Main) {
                                    statusMessage = context.getString(R.string.saved_download_msg)
                                }
                            } catch (e: Exception) {
                                launch(Dispatchers.Main) {
                                    statusMessage = context.getString(R.string.compat_save_failed, e.message ?: "")
                                }
                            }
                        }
                    },
                    enabled = entryCount > 0 && !isSending,
                    modifier = Modifier.weight(2f),
                    colors = ButtonDefaults.buttonColors(containerColor = BydElectricAzure)
                ) {
                    Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.save_local_action))
                }

                Button(
                    onClick = {
                        VehicleCompatibilityProbe.clear()
                        statusMessage = context.getString(R.string.compat_probe_cleared)
                    },
                    enabled = entryCount > 0 && !isSending,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                }
            }

            // Recommended path: upload the report and show a QR the user scans with
            // their phone to email the download link — no Telegram/adb/file transfer.
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        launch(Dispatchers.Main) { uploadInProgress = true; statusMessage = null }
                        try {
                            val url = VehicleCompatibilityProbe.uploadReport(retention = "24h")
                            launch(Dispatchers.Main) {
                                uploadInProgress = false
                                qrUrl = url
                            }
                        } catch (e: Exception) {
                            launch(Dispatchers.Main) {
                                uploadInProgress = false
                                statusMessage = context.getString(R.string.compat_upload_failed, e.message ?: "")
                            }
                        }
                    }
                },
                enabled = entryCount > 0 && !uploadInProgress && !isSending,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BydElectricAzure)
            ) {
                if (uploadInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Icon(Icons.Filled.QrCode2, null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (uploadInProgress) stringResource(R.string.compat_uploading_label) else stringResource(R.string.compat_email_qr_label))
            }

            if (telegramConfig == null) {
                Text(
                    stringResource(R.string.compat_telegram_not_configured),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Status line
            statusMessage?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (msg.startsWith("Telegram send failed") || msg.startsWith("Upload failed"))
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    qrUrl?.let { url ->
        ProbeEmailQrDialog(downloadUrl = url, onDismiss = { qrUrl = null })
    }
}

/**
 * Shows a QR code encoding a pre-filled `mailto:bydtripstats@gmail.com` whose body carries
 * the probe download link. The head unit has no mail app, so the user scans this with their
 * phone, picks an account and presses Send — the report link reaches support, and the link
 * expires in 24h. Falls back to showing the raw URL with a copy button.
 */
@Composable
internal fun ProbeEmailQrDialog(downloadUrl: String, onDismiss: () -> Unit) {
    val supportEmail = "bydtripstats@gmail.com"
    val clipboard = LocalClipboardManager.current
    val sizePx = with(LocalDensity.current) { 220.dp.roundToPx() }
    val qr = remember(downloadUrl) {
        val subject = "BYD Trip Stats — vehicle compatibility probe"
        val body = "Vehicle compatibility probe report.\n\n" +
            "Download (expires in 24h):\n$downloadUrl\n\n" +
            "Sent from BYD Trip Stats."
        val mailto = "mailto:$supportEmail?subject=" + Uri.encode(subject) +
            "&body=" + Uri.encode(body)
        QrCodeGenerator.generate(mailto, sizePx)?.asImageBitmap()
    }

    val strDone        = stringResource(R.string.done)
    val strCopyLink    = stringResource(R.string.compat_qr_copy_link)
    val strTitle       = stringResource(R.string.compat_qr_dialog_title)
    val strDesc        = stringResource(R.string.compat_qr_dialog_desc, supportEmail)
    val strNoRender    = stringResource(R.string.compat_qr_no_render, supportEmail)
    val strContentDesc = stringResource(R.string.compat_qr_content_desc)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(strDone) }
        },
        dismissButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(downloadUrl)) }) {
                Text(strCopyLink)
            }
        },
        title = { Text(strTitle) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    strDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (qr != null) {
                    Image(
                        bitmap = qr,
                        contentDescription = strContentDesc,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier
                            .background(Color.White, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                            .size(220.dp)
                    )
                } else {
                    Text(
                        strNoRender,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    downloadUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    )
}
