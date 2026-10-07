package com.byd.tripstats.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.ui.components.BrandSwitch
import com.byd.tripstats.data.backup.TelegramManager
import com.byd.tripstats.data.entitlement.EntitlementManager
import com.byd.tripstats.data.notify.TelegramNotifier
import com.byd.tripstats.sdk.DiLink5Platform
import com.byd.tripstats.ui.theme.RegenGreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Telegram as a *connection*: the bot is linked here, alongside ABRP, MQTT and the web companion,
 * and the event notifications that ride on it are configured directly underneath.
 *
 * Backup & Restore keeps only what is genuinely about backups — the schedule, "Send Backup Now"
 * and the restore list — and points here when no bot is linked yet. One bot, linked in one place,
 * used by several features.
 */
@Composable
internal fun TelegramConnectionSection(context: Context, scope: CoroutineScope) {
    val manager = remember { TelegramManager.getInstance(context) }
    val config by manager.config.collectAsState()
    val state by manager.state.collectAsState()
    val busy = state is TelegramManager.TelegramState.InProgress

    var tokenInput by remember { mutableStateOf("") }
    LaunchedEffect(config) { tokenInput = config?.token ?: "" }

    // The backup screen auto-dismisses its own banners; do the same here so a stale
    // "Connected" line doesn't sit on the tab for the rest of the session.
    LaunchedEffect(state) {
        if (state is TelegramManager.TelegramState.Success) {
            delay(4000)
            manager.resetState()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(22.dp))
                Text(
                    stringResource(R.string.telegram_title_label),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                stringResource(R.string.telegram_connection_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val s = state) {
                is TelegramManager.TelegramState.InProgress ->
                    TelegramBanner(s.message, MaterialTheme.colorScheme.primaryContainer, Icons.Filled.HourglassTop, loading = true)
                is TelegramManager.TelegramState.Success ->
                    TelegramBanner(s.message, RegenGreen.copy(alpha = 0.15f), Icons.Filled.CheckCircle, RegenGreen) { manager.resetState() }
                is TelegramManager.TelegramState.Error ->
                    TelegramBanner(s.message, MaterialTheme.colorScheme.errorContainer, Icons.Filled.Error, MaterialTheme.colorScheme.error) { manager.resetState() }
                else -> {}
            }

            val cfg = config
            if (cfg != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(20.dp), tint = RegenGreen)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "@${cfg.botName}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "Chat ID: ${cfg.chatId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    stringResource(R.string.telegram_connected_uses),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DisconnectButton(
                    text = stringResource(R.string.disconnect_bot_action),
                    confirmTitle = stringResource(R.string.telegram_disconnect_confirm_title),
                    confirmText = stringResource(R.string.telegram_disconnect_confirm_body),
                    enabled = !busy,
                    onClick = { manager.clearConfig() },
                )
            } else {
                Text(
                    stringResource(R.string.telegram_instructions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text(stringResource(R.string.bot_token_label)) },
                    placeholder = { Text("123456789:ABCdef…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
                Button(
                    onClick = { scope.launch { manager.validateAndSave(tokenInput) } },
                    enabled = tokenInput.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Filled.Check, null, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (busy) stringResource(R.string.uploading)
                        else stringResource(R.string.validate_save_action)
                    )
                }
            }
        }
    }

    TelegramNotificationsCard(botConnected = config != null, scope = scope)
}

/**
 * Event pushes to the linked bot. Sits directly under the connection it uses, so the switches and
 * the thing that carries them are never in two different places.
 */
@Composable
private fun TelegramNotificationsCard(botConnected: Boolean, scope: CoroutineScope) {
    val context = LocalContext.current
    val notifier = remember { TelegramNotifier.getInstance(context) }

    val enabled by notifier.enabled.collectAsState()
    val tripSummary by notifier.tripSummaryEnabled.collectAsState()
    val chargingFinished by notifier.chargingFinishedEnabled.collectAsState()
    val cellImbalance by notifier.cellImbalanceEnabled.collectAsState()
    // The cell-imbalance alert itself is Pro-only, so its Telegram copy is too — showing a
    // live switch to a free user would promise an alert that can never fire.
    val isPro by EntitlementManager.isPro.collectAsState()

    var testResult by remember { mutableStateOf<Boolean?>(null) }
    var testing by remember { mutableStateOf(false) }

    LaunchedEffect(testResult) {
        if (testResult != null) {
            delay(5000)
            testResult = null
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.NotificationsActive, null, modifier = Modifier.size(22.dp))
                Text(
                    stringResource(R.string.telegram_notify_label),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.telegram_notify_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // DiLink-5 can't deliver the events that matter most here, and the reason is
            // structural rather than a bug we are about to fix — so it is stated up front
            // instead of leaving the user to conclude the feature is broken.
            if (DiLink5Platform.isDiLink5) {
                Spacer(Modifier.height(8.dp))
                TelegramBanner(
                    text = stringResource(R.string.telegram_notify_di5_warning),
                    color = MaterialTheme.colorScheme.errorContainer,
                    icon = Icons.Filled.Warning,
                    iconTint = MaterialTheme.colorScheme.error,
                )
            }

            // if/else, not an early `return@Column`: returning out of Column's inline lambda here
            // unbalanced Compose's group stack on recomposition — beta32 crashed on opening this
            // page (ArrayIndexOutOfBounds in IntStack.peek2 via ComposerImpl.endRoot) on a
            // DiLink-5 car with no bot linked.
            if (!botConnected) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.telegram_notify_needs_bot),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(8.dp))
                NotifySwitchRow(
                    label = stringResource(R.string.telegram_notify_enable_label),
                    description = null,
                    checked = enabled,
                    onCheckedChange = { notifier.setEnabled(it) },
                )

                if (enabled) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                    NotifySwitchRow(
                        label = stringResource(R.string.telegram_notify_trip_label),
                        description = stringResource(R.string.telegram_notify_trip_desc),
                        checked = tripSummary,
                        onCheckedChange = { notifier.setTripSummaryEnabled(it) },
                    )

                    Spacer(Modifier.height(10.dp))
                    NotifySwitchRow(
                        label = stringResource(R.string.telegram_notify_charging_label),
                        description = stringResource(R.string.telegram_notify_charging_desc),
                        checked = chargingFinished,
                        onCheckedChange = { notifier.setChargingFinishedEnabled(it) },
                    )

                    Spacer(Modifier.height(10.dp))
                    if (isPro) {
                        NotifySwitchRow(
                            label = stringResource(R.string.telegram_notify_imbalance_label),
                            description = stringResource(R.string.telegram_notify_imbalance_desc),
                            checked = cellImbalance,
                            onCheckedChange = { notifier.setCellImbalanceEnabled(it) },
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.telegram_notify_imbalance_label),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    stringResource(R.string.telegram_notify_imbalance_pro),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = stringResource(R.string.unlock_pro_action),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                    Text(
                        stringResource(R.string.telegram_notify_parked_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            testing = true
                            scope.launch {
                                testResult = notifier.sendTest()
                                testing = false
                            }
                        },
                        enabled = !testing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (testing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.telegram_notify_test_action))
                    }

                    testResult?.let { ok ->
                        Spacer(Modifier.height(8.dp))
                        TelegramBanner(
                            text = stringResource(
                                if (ok) R.string.telegram_notify_test_ok
                                else R.string.telegram_notify_test_failed
                            ),
                            color = if (ok) RegenGreen.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.errorContainer,
                            icon = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                            iconTint = if (ok) RegenGreen else MaterialTheme.colorScheme.error,
                            onDismiss = { testResult = null },
                        )
                    }
                }
            }
        }
    }
}

/** Label (+ optional description) on the left, the app's donut-thumb switch on the right. */
@Composable
private fun NotifySwitchRow(
    label: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        // These descriptions are long enough to wrap, and without a gutter the last line
        // ends up touching the switch.
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        BrandSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun TelegramBanner(
    text: String,
    color: Color,
    icon: ImageVector,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    loading: Boolean = false,
    onDismiss: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
