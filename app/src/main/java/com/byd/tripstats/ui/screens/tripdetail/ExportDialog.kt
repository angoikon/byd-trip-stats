package com.byd.tripstats.ui.screens.tripdetail

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.data.backup.TelegramManager
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.preferences.SocSource
import com.byd.tripstats.data.preferences.UnitSystem
import com.byd.tripstats.util.QrCodeGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ExportDialog(
    trip: TripEntity,
    dataPoints: List<TripDataPointEntity>,
    onDismiss: () -> Unit,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    socSource: SocSource = SocSource.PANEL
) {
    val context          = LocalContext.current
    val stableTrip       = remember { trip }
    val stableDataPoints = remember { dataPoints.toList() }

    val telegram         = remember { TelegramManager.getInstance(context) }
    val telegramConfig   by telegram.config.collectAsState()
    val telegramState    by telegram.state.collectAsState()
    val telegramSending  = telegramState is TelegramManager.TelegramState.InProgress
    val scope            = rememberCoroutineScope()

    // Share-as-link (QR) state. While uploading we keep the export dialog open with a
    // spinner; on success we swap this whole composable to the QR dialog (below), so the
    // parent doesn't need to own any of it — dismissing the QR closes everything.
    var qrUrl            by remember { mutableStateOf<String?>(null) }
    var uploadInProgress by remember { mutableStateOf(false) }
    var uploadError      by remember { mutableStateOf<String?>(null) }

    val currentQrUrl = qrUrl
    if (currentQrUrl != null) {
        TripLinkQrDialog(
            trip = stableTrip,
            downloadUrl = currentQrUrl,
            onDismiss = { qrUrl = null; onDismiss() }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.export_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {

                // ── Clipboard ─────────────────────────────────────────────────
                OutlinedButton(
                    onClick = {
                        copyTripSummaryToClipboard(context, stableTrip, unitSystem, socSource)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.export_copy_summary))
                }

                // ── Share as link (QR) — recommended, no bot/USB/adb needed ────
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            launch(Dispatchers.Main) { uploadInProgress = true; uploadError = null }
                            try {
                                val url = uploadTripJson(stableTrip, stableDataPoints)
                                launch(Dispatchers.Main) {
                                    uploadInProgress = false
                                    qrUrl = url
                                }
                            } catch (e: Exception) {
                                launch(Dispatchers.Main) {
                                    uploadInProgress = false
                                    uploadError = context.getString(R.string.export_upload_failed, e.message ?: "")
                                }
                            }
                        }
                    },
                    enabled = !uploadInProgress && stableDataPoints.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uploadInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Filled.QrCode2, null, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (uploadInProgress) stringResource(R.string.export_uploading)
                        else stringResource(R.string.export_share_link)
                    )
                }
                uploadError?.let { err ->
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                HorizontalDivider()

                // ── Downloads (collapsible) ───────────────────────────────────
                var downloadsExpanded by remember { mutableStateOf(false) }
                ExpandableSectionHeader(
                    label = stringResource(R.string.export_save_downloads),
                    expanded = downloadsExpanded,
                    onToggle = { downloadsExpanded = !downloadsExpanded }
                )
                if (downloadsExpanded) {
                    OutlinedButton(
                        onClick = {
                            saveTripAsCSV(context, stableTrip, stableDataPoints)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.TableChart, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.export_save_csv))
                    }

                    OutlinedButton(
                        onClick = {
                            saveTripAsJSON(context, stableTrip, stableDataPoints)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.DataObject, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.export_save_json))
                    }

                    OutlinedButton(
                        onClick = {
                            saveTripAsHtml(context, stableTrip, stableDataPoints)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Public, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.export_save_html))
                    }
                }

                HorizontalDivider()

                // ── Telegram (collapsible) ────────────────────────────────────
                var telegramExpanded by remember { mutableStateOf(false) }
                ExpandableSectionHeader(
                    label = if (telegramConfig != null)
                        stringResource(R.string.export_send_telegram) + " (@${telegramConfig!!.botName})"
                    else
                        stringResource(R.string.export_telegram_not_configured),
                    expanded = telegramExpanded,
                    onToggle = { telegramExpanded = !telegramExpanded }
                )
                if (telegramExpanded) {
                    if (telegramConfig == null) {
                        Text(
                            stringResource(R.string.export_telegram_setup_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            sendTripExportToTelegram(
                                context, telegram, scope, stableTrip,
                                format = "csv",
                                content = buildTripCsv(stableDataPoints)
                            )
                            onDismiss()
                        },
                        enabled = telegramConfig != null && !telegramSending,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.export_send_csv))
                    }

                    OutlinedButton(
                        onClick = {
                            sendTripExportToTelegram(
                                context, telegram, scope, stableTrip,
                                format = "json",
                                content = buildTripJson(stableTrip, stableDataPoints)
                            )
                            onDismiss()
                        },
                        enabled = telegramConfig != null && !telegramSending,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.export_send_json))
                    }

                    OutlinedButton(
                        onClick = {
                            sendTripExportToTelegram(
                                context, telegram, scope, stableTrip,
                                format = "html",
                                content = buildTripEmbeddedHtml(context, stableTrip, stableDataPoints)
                            )
                            onDismiss()
                        },
                        enabled = telegramConfig != null && !telegramSending,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.export_send_html))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * QR dialog for the "Share as link" export path. Encodes a pre-filled
 * `mailto:bydtripstats@gmail.com` whose body carries the temporary download link to the
 * uploaded trip JSON. The head unit has no mail app, so the user scans this with their
 * phone, picks an account and presses Send — the trip reaches the developer, and the link
 * expires in 72h. Falls back to the raw URL + a copy button. Mirrors the compatibility
 * probe's ProbeEmailQrDialog.
 */
@Composable
private fun TripLinkQrDialog(
    trip: TripEntity,
    downloadUrl: String,
    onDismiss: () -> Unit,
) {
    val supportEmail = "bydtripstats@gmail.com"
    val clipboard = LocalClipboardManager.current
    val sizePx = with(LocalDensity.current) { 220.dp.roundToPx() }
    val qr = remember(downloadUrl) {
        val subject = "BYD Trip Stats — trip #${trip.id} export"
        val body = "Trip JSON export.\n\n" +
            "Download (expires in 72h):\n$downloadUrl\n\n" +
            "Sent from BYD Trip Stats."
        val mailto = "mailto:$supportEmail?subject=" + Uri.encode(subject) +
            "&body=" + Uri.encode(body)
        QrCodeGenerator.generate(mailto, sizePx)?.asImageBitmap()
    }

    val strDone        = stringResource(R.string.done)
    val strCopyLink    = stringResource(R.string.export_qr_copy_link)
    val strTitle       = stringResource(R.string.export_qr_dialog_title)
    val strDesc        = stringResource(R.string.export_qr_dialog_desc, supportEmail)
    val strNoRender    = stringResource(R.string.export_qr_no_render, supportEmail)
    val strContentDesc = stringResource(R.string.export_qr_content_desc)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(strDone) }
        },
        dismissButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(downloadUrl)) }) {
                Text(strCopyLink)
            }
        },
        title = { Text(strTitle, fontWeight = FontWeight.Bold) },
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

/**
 * Tappable section header for the export dialog — caret + label that flips state on click.
 */
@Composable
internal fun ExpandableSectionHeader(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowDown
                          else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
