package com.byd.tripstats.ui.screens.settings

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.ui.components.BrandSwitch
import com.byd.tripstats.connections.AbrpConnectionManager
import com.byd.tripstats.connections.AbrpConnectionStore
import com.byd.tripstats.ui.theme.BydElectricAzure
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The ABRP connection, on its own page under Settings → Connections.
 *
 * State lives here rather than in [ConnectionsTab] so that leaving the page discards the draft —
 * which is what the old explicit "reload drafts" call did by hand when the details view opened
 * and closed.
 */
@Composable
internal fun AbrpConnectionSection(context: Context, scope: CoroutineScope) {
    val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<DashboardViewModel>()
    val selectedCarConfig by viewModel.selectedCarConfig.collectAsState()
    val liveSnapshot by viewModel.vehicleSnapshot.collectAsState()
    val currentTelemetry by viewModel.currentTelemetry.collectAsState()
    val liveTelemetry = remember(liveSnapshot, currentTelemetry, selectedCarConfig) {
        liveSnapshot?.toTelemetry(selectedCarConfig) ?: currentTelemetry
    }
    val manager = remember { AbrpConnectionManager(context) }

    var settings by remember { mutableStateOf(AbrpConnectionStore.load(context)) }
    var tokenInput by rememberSaveable { mutableStateOf(settings.userToken) }
    var enabled by rememberSaveable { mutableStateOf(settings.enabled) }
    var intervalInput by rememberSaveable { mutableStateOf(settings.uploadIntervalSeconds.toString()) }
    var lastSavedAt by remember { mutableStateOf(settings.lastUploadAtMs) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var showToken by rememberSaveable { mutableStateOf(false) }

    fun reloadDraft() {
        settings = AbrpConnectionStore.load(context)
        tokenInput = settings.userToken
        enabled = settings.enabled
        intervalInput = settings.uploadIntervalSeconds.toString()
        lastSavedAt = settings.lastUploadAtMs
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.abrp_title_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.abrp_link_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.abrp_instructions_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Parked charging is the case people actually notice missing here, so say it before
            // they conclude the token is wrong. Not a gate — while the car is on it works normally.
            if (com.byd.tripstats.sdk.DiLink5Platform.isDiLink5) {
                Text(
                    stringResource(R.string.abrp_di5_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.enable_abrp_label),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (enabled) stringResource(R.string.abrp_uploads_every, intervalInput)
                        else stringResource(R.string.abrp_disabled_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BrandSwitch(
                    checked = enabled,
                    onCheckedChange = { next ->
                        enabled = next
                        if (!next) {
                            val interval = intervalInput.toIntOrNull()?.coerceIn(5, 120) ?: 5
                            settings = settings.copy(
                                enabled = false,
                                userToken = tokenInput,
                                apiKey = settings.apiKey.ifBlank { AbrpConnectionStore.DEFAULT_PUBLIC_API_KEY },
                                uploadIntervalSeconds = interval
                            )
                            AbrpConnectionStore.save(context, settings)
                            lastSavedAt = System.currentTimeMillis()
                        }
                    },
                )
            }
            AnimatedVisibility(visible = enabled, enter = expandVertically(), exit = shrinkVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsGroupLabel(stringResource(R.string.connection_group_label))
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it.trim() },
                        label = { Text(stringResource(R.string.abrp_token_label)) },
                        placeholder = { Text(stringResource(R.string.paste_token_hint)) },
                        singleLine = true,
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showToken = !showToken }) {
                                Icon(
                                    imageVector = if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showToken) stringResource(R.string.hide_token_cd) else stringResource(R.string.show_token_cd)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = intervalInput,
                        onValueChange = { intervalInput = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.upload_interval_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                val interval = intervalInput.toIntOrNull()?.coerceIn(5, 120) ?: 5
                                settings = settings.copy(
                                    enabled = enabled,
                                    userToken = tokenInput,
                                    apiKey = settings.apiKey.ifBlank { AbrpConnectionStore.DEFAULT_PUBLIC_API_KEY },
                                    uploadIntervalSeconds = interval
                                )
                                AbrpConnectionStore.save(context, settings)
                                lastSavedAt = System.currentTimeMillis()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BydElectricAzure)
                        ) {
                            Text(stringResource(R.string.save))
                        }
                        Button(
                            onClick = {
                                val telemetry = liveTelemetry
                                if (telemetry == null) {
                                    testResult = if (viewModel.serviceConnected.value) {
                                        context.getString(R.string.telemetry_not_ready_msg)
                                    } else {
                                        context.getString(R.string.service_not_connected_msg)
                                    }
                                    testOk = false
                                    return@Button
                                }
                                val current = AbrpConnectionStore.load(context).copy(
                                    enabled = enabled,
                                    userToken = tokenInput,
                                    apiKey = settings.apiKey.ifBlank { AbrpConnectionStore.DEFAULT_PUBLIC_API_KEY },
                                    uploadIntervalSeconds = intervalInput.toIntOrNull()?.coerceIn(5, 120) ?: 5
                                )
                                AbrpConnectionStore.save(context, current)
                                scope.launch(Dispatchers.IO) {
                                    val (ok, status) = manager.testUpload(telemetry, selectedCarConfig, current)
                                    launch(Dispatchers.Main) {
                                        testResult = if (ok) context.getString(R.string.test_upload_success) else status
                                        testOk = ok
                                        settings = AbrpConnectionStore.load(context)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BydElectricAzure)
                        ) {
                            Text(stringResource(R.string.test_and_save_action))
                        }
                        OutlinedButton(onClick = { reloadDraft() }) {
                            Text(stringResource(R.string.reload_action))
                        }
                    }
                }
            }
        }
        ConnectionStatusCard(
            title = stringResource(R.string.abrp_status_label),
            content = {
                SettingsDetailRow(stringResource(R.string.label_configured), if (settings.userToken.isBlank()) stringResource(R.string.value_no) else stringResource(R.string.value_yes))
                SettingsDetailRow(stringResource(R.string.label_enabled), if (settings.enabled) stringResource(R.string.value_yes) else stringResource(R.string.value_no))
                SettingsDetailRow(stringResource(R.string.label_last_upload), if (settings.lastUploadAtMs > 0L) {
                    formatFriendlyTimestamp(settings.lastUploadAtMs)
                } else {
                    stringResource(R.string.value_na)
                })
                SettingsDetailRow(stringResource(R.string.label_last_status), settings.lastStatus)
                if (!testResult.isNullOrBlank()) {
                    Text(
                        testResult!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testOk) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            }
        )
    }
}
