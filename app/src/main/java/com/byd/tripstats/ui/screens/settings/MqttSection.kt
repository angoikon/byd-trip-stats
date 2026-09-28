package com.byd.tripstats.ui.screens.settings

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.ui.components.BrandSwitch
import com.byd.tripstats.connections.MqttConnectionManager
import com.byd.tripstats.connections.MqttConnectionStore
import com.byd.tripstats.ui.theme.BydElectricAzure
import com.byd.tripstats.ui.theme.RegenGreen
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The MQTT connection, on its own page under Settings → Connections.
 *
 * State lives here rather than in [ConnectionsTab] so that leaving the page discards the draft.
 */
@Composable
internal fun MqttConnectionSection(context: Context, scope: CoroutineScope) {
    val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<DashboardViewModel>()
    val selectedCarConfig by viewModel.selectedCarConfig.collectAsState()
    val liveSnapshot by viewModel.vehicleSnapshot.collectAsState()
    val currentTelemetry by viewModel.currentTelemetry.collectAsState()
    val liveTelemetry = remember(liveSnapshot, currentTelemetry, selectedCarConfig) {
        liveSnapshot?.toTelemetry(selectedCarConfig) ?: currentTelemetry
    }
    val manager = remember { MqttConnectionManager(context) }

    var settings by remember { mutableStateOf(MqttConnectionStore.load(context)) }
    var brokerInput by rememberSaveable { mutableStateOf(settings.brokerUrl) }
    var portInput by rememberSaveable { mutableStateOf(settings.brokerPort.toString()) }
    var usernameInput by rememberSaveable { mutableStateOf(settings.username) }
    var passwordInput by rememberSaveable { mutableStateOf(settings.password) }
    var friendlyNameInput by rememberSaveable { mutableStateOf(settings.friendlyName) }
    var enabled by rememberSaveable { mutableStateOf(settings.enabled) }
    var useTls by rememberSaveable { mutableStateOf(settings.useTls) }
    var useWebSocket by rememberSaveable { mutableStateOf(settings.useWebSocket) }
    var wsPathInput by rememberSaveable { mutableStateOf(settings.webSocketPath) }
    var intervalInput by rememberSaveable { mutableStateOf(settings.publishIntervalSeconds.toString()) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    fun reloadDraft() {
        settings = MqttConnectionStore.load(context)
        brokerInput = settings.brokerUrl
        portInput = settings.brokerPort.toString()
        usernameInput = settings.username
        passwordInput = settings.password
        friendlyNameInput = settings.friendlyName
        enabled = settings.enabled
        useTls = settings.useTls
        useWebSocket = settings.useWebSocket
        wsPathInput = settings.webSocketPath
        intervalInput = settings.publishIntervalSeconds.toString()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.mqtt_title_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.mqtt_card_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Stated up front rather than in a FAQ: a parked car that stops reporting looks exactly
            // like a broken broker, and someone would reasonably spend an evening on TLS and topics
            // before suspecting the head unit simply powered off. Not a gate — while the car is on
            // MQTT behaves as it does on DiLink-3.
            if (com.byd.tripstats.sdk.DiLink5Platform.isDiLink5) {
                Text(
                    stringResource(R.string.mqtt_di5_note),
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
                    Text(stringResource(R.string.enable_mqtt_label), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        if (enabled) stringResource(R.string.publishes_every, intervalInput)
                        else stringResource(R.string.mqtt_disabled_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BrandSwitch(
                    checked = enabled,
                    onCheckedChange = { next ->
                        enabled = next
                        if (!next) {
                            val interval = intervalInput.toIntOrNull()?.coerceIn(1, 120) ?: 1
                            val port = portInput.toIntOrNull()?.coerceIn(1, 65535) ?: 1883
                            settings = settings.copy(
                                enabled = false,
                                brokerUrl = brokerInput,
                                brokerPort = port,
                                username = usernameInput,
                                password = passwordInput,
                                friendlyName = friendlyNameInput,
                                publishIntervalSeconds = interval
                            )
                            MqttConnectionStore.save(context, settings)
                            result = context.getString(R.string.mqtt_disabled_saved_msg)
                            resultOk = true
                        }
                    },
                )
            }
            AnimatedVisibility(visible = enabled, enter = expandVertically(), exit = shrinkVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsGroupLabel(stringResource(R.string.connection_group_label))
                    OutlinedTextField(
                        value = brokerInput,
                        onValueChange = { brokerInput = it.trim() },
                        label = { Text(stringResource(R.string.broker_url_label)) },
                        placeholder = { Text("example.hivemq.cloud") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.port_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.use_tls_label),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                stringResource(R.string.tls_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (portInput == "8883" && !useTls) {
                                Text(
                                    stringResource(R.string.port_8883_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        BrandSwitch(
                            checked = useTls,
                            onCheckedChange = { useTls = it },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.use_websocket_label),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                stringResource(R.string.websocket_desc_text),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        BrandSwitch(
                            checked = useWebSocket,
                            onCheckedChange = { useWebSocket = it },
                        )
                    }
                    AnimatedVisibility(visible = useWebSocket, enter = expandVertically(), exit = shrinkVertically()) {
                        OutlinedTextField(
                            value = wsPathInput,
                            onValueChange = { wsPathInput = it.trim() },
                            label = { Text(stringResource(R.string.websocket_path_label)) },
                            placeholder = { Text("/mqtt") },
                            singleLine = true,
                            supportingText = {
                                val scheme = if (useTls) "wss" else "ws"
                                val host = brokerInput.ifBlank { "broker" }
                                val portTxt = portInput.ifBlank { "<port>" }
                                val path = wsPathInput.ifBlank { "/mqtt" }.let { if (it.startsWith("/")) it else "/$it" }
                                Text("$scheme://$host:$portTxt$path", style = MaterialTheme.typography.bodySmall)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedTextField(
                        value = friendlyNameInput,
                        onValueChange = { friendlyNameInput = it.trim() },
                        label = { Text(stringResource(R.string.mqtt_device_name_label)) },
                        placeholder = { Text("my-byd-seal") },
                        singleLine = true,
                        supportingText = {
                            val preview = friendlyNameInput.replace("[^a-zA-Z0-9_-]".toRegex(), "_").ifBlank { "<android-id>" }
                            Text("Topic: byd-trip-stats/$preview/state", style = MaterialTheme.typography.bodySmall)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = usernameInput,
                        onValueChange = { usernameInput = it },
                        label = { Text(stringResource(R.string.username_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text(stringResource(R.string.password_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = intervalInput,
                        onValueChange = { intervalInput = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.publish_interval_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { Text(stringResource(R.string.publish_interval_desc, intervalInput.ifBlank { "1" }), style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        stringResource(R.string.publish_parked_info),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                val interval = intervalInput.toIntOrNull()?.coerceIn(1, 120) ?: 1
                                val port = portInput.toIntOrNull()?.coerceIn(1, 65535) ?: 1883
                                settings = settings.copy(
                                    enabled = enabled,
                                    brokerUrl = brokerInput,
                                    brokerPort = port,
                                    username = usernameInput,
                                    password = passwordInput,
                                    friendlyName = friendlyNameInput,
                                    publishIntervalSeconds = interval,
                                    useTls = useTls,
                                    useWebSocket = useWebSocket,
                                    webSocketPath = wsPathInput
                                )
                                MqttConnectionStore.save(context, settings)
                                result = context.getString(R.string.mqtt_settings_saved_msg)
                                resultOk = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BydElectricAzure)
                        ) {
                            Text(stringResource(R.string.save))
                        }
                        Button(
                            enabled = !testing,
                            onClick = {
                                val telemetry = liveTelemetry
                                if (telemetry == null) {
                                    result = context.getString(R.string.no_live_telemetry_yet)
                                    resultOk = false
                                    return@Button
                                }
                                val interval = intervalInput.toIntOrNull()?.coerceIn(1, 120) ?: 1
                                val port = portInput.toIntOrNull()?.coerceIn(1, 65535) ?: 1883
                                val current = MqttConnectionStore.load(context).copy(
                                    enabled = enabled,
                                    brokerUrl = brokerInput,
                                    brokerPort = port,
                                    username = usernameInput,
                                    password = passwordInput,
                                    friendlyName = friendlyNameInput,
                                    publishIntervalSeconds = interval,
                                    useTls = useTls,
                                    useWebSocket = useWebSocket,
                                    webSocketPath = wsPathInput
                                )
                                MqttConnectionStore.save(context, current)
                                // HOME_ASSISTANT.md tells people to press this when the broker has
                                // lost its retained configs, so it has to mean "send them again"
                                // rather than "send them if we think you need them".
                                manager.requestDiscoveryRepublish()
                                testing = true
                                result = null
                                resultOk = false
                                scope.launch {
                                    try {
                                        val (ok, status) = withContext(Dispatchers.IO) {
                                            manager.testPublish(telemetry)
                                        }
                                        result = if (ok) context.getString(R.string.mqtt_test_success) else status
                                        resultOk = ok
                                        settings = MqttConnectionStore.load(context)
                                    } catch (e: Exception) {
                                        result = context.getString(R.string.mqtt_test_failed_msg, e.message)
                                        resultOk = false
                                    } finally {
                                        testing = false
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BydElectricAzure)
                        ) {
                            Text(if (testing) stringResource(R.string.running) else stringResource(R.string.test_and_save_action))
                        }
                        OutlinedButton(onClick = { reloadDraft() }) {
                            Text(stringResource(R.string.reload_action))
                        }
                    }
                }
            }
        }
        ConnectionStatusCard(
            title = stringResource(R.string.mqtt_status_label),
            content = {
                SettingsDetailRow(stringResource(R.string.label_configured), if (settings.brokerUrl.isBlank()) stringResource(R.string.value_no) else stringResource(R.string.value_yes))
                SettingsDetailRow(stringResource(R.string.label_enabled), if (settings.enabled) stringResource(R.string.value_yes) else stringResource(R.string.value_no))
                SettingsDetailRow(stringResource(R.string.label_last_publish), if (settings.lastPublishAtMs > 0L) {
                    formatFriendlyTimestamp(settings.lastPublishAtMs)
                } else {
                    stringResource(R.string.value_na)
                })
                SettingsDetailRow(stringResource(R.string.label_last_status), settings.lastStatus)
                if (!result.isNullOrBlank()) {
                    Text(
                        result!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (resultOk) RegenGreen else MaterialTheme.colorScheme.error
                    )
                }
            }
        )
    }
}
