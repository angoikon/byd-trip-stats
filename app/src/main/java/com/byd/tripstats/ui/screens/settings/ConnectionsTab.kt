package com.byd.tripstats.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.data.backup.TelegramManager
import com.byd.tripstats.server.WebServerManager
import com.byd.tripstats.util.TailscaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → Connections.
 *
 * An overview of the external connections, each of which opens **on its own page**. They used
 * to share one long details view behind an "Open Connections" button, which meant scrolling past
 * ABRP and MQTT to reach Telegram — so the cards are the navigation now, and a page shows one
 * connection and nothing else.
 *
 * Ordered so the pairs on the two-across grid belong together: the two telemetry feeds (ABRP,
 * MQTT), then the two ways into the car (Web Companion, and Tailscale, which carries it off the
 * home network), then Telegram.
 */
private object ConnectionPage {
    const val ABRP = "abrp"
    const val MQTT = "mqtt"
    const val WEB_COMPANION = "web_companion"
    const val TAILSCALE = "tailscale"
    const val TELEGRAM = "telegram"
}

@Composable
internal fun ConnectionsTab() {
    val context = LocalContext.current
    val screenScope = rememberCoroutineScope()

    SettingsHub(
        icon = Icons.Filled.Link,
        title = stringResource(R.string.settings_tab_connections),
        description = stringResource(R.string.connections_overview_desc),
        cards = {
            // Re-read on every return to the overview, so a change made on a page shows at once.
            val abrpSettings = remember { com.byd.tripstats.connections.AbrpConnectionStore.load(context) }
            val mqttSettings = remember { com.byd.tripstats.connections.MqttConnectionStore.load(context) }
            val telegramConfig by TelegramManager.getInstance(context).config.collectAsState()
            val tailscaleStatus by TailscaleManager.status.collectAsState()
            // The companion's first address — LAN first, as its own page lists them. Interface
            // enumeration is a filesystem read, so it stays off the main thread.
            val companionUrl by produceState<String?>(initialValue = null, tailscaleStatus.ip) {
                value = withContext(Dispatchers.IO) { WebServerManager.getUrl(context) }
            }
            val enabledLabel = stringResource(R.string.status_enabled_label)
            val disabledLabel = stringResource(R.string.status_disabled_label)

            listOf(
                SettingsHubCard(
                    key = ConnectionPage.ABRP,
                    icon = Icons.Filled.Route,
                    title = stringResource(R.string.abrp_title_label),
                    body = stringResource(R.string.abrp_card_desc),
                    statusLine = "${if (abrpSettings.enabled) enabledLabel else disabledLabel} • ${abrpSettings.lastStatus}"
                ),
                SettingsHubCard(
                    key = ConnectionPage.MQTT,
                    icon = Icons.Filled.Cloud,
                    title = stringResource(R.string.mqtt_title_label),
                    body = stringResource(R.string.mqtt_card_desc),
                    statusLine = "${if (mqttSettings.enabled) enabledLabel else disabledLabel} • ${mqttSettings.lastStatus}"
                ),
                SettingsHubCard(
                    key = ConnectionPage.WEB_COMPANION,
                    icon = Icons.Filled.Language,
                    title = stringResource(R.string.web_companion_label),
                    body = stringResource(R.string.web_companion_card_desc),
                    // Whether it is actually serving, not what the switch says: a port clash
                    // leaves the switch on and nothing listening, and "Enabled" would hide that.
                    // The running flag covers the moment before the address lookup returns.
                    statusLine = when {
                        companionUrl != null        -> "$enabledLabel • $companionUrl"
                        WebServerManager.isRunning  -> enabledLabel
                        else                        -> disabledLabel
                    }
                ),
                SettingsHubCard(
                    key = ConnectionPage.TAILSCALE,
                    icon = Icons.Filled.Hub,
                    title = stringResource(R.string.tailscale_title_short),
                    body = stringResource(R.string.tailscale_card_desc),
                    statusLine = tailscaleStatus.ip
                        ?: stringResource(R.string.status_not_connected_label)
                ),
                SettingsHubCard(
                    key = ConnectionPage.TELEGRAM,
                    icon = Icons.AutoMirrored.Filled.Send,
                    title = stringResource(R.string.telegram_title_label),
                    body = stringResource(R.string.telegram_card_desc),
                    statusLine = telegramConfig?.let { "@${it.botName}" }
                        ?: stringResource(R.string.status_not_connected_label)
                )
            )
        }
    ) { page ->
        when (page) {
            ConnectionPage.ABRP          -> AbrpConnectionSection(context = context, scope = screenScope)
            ConnectionPage.MQTT          -> MqttConnectionSection(context = context, scope = screenScope)
            ConnectionPage.WEB_COMPANION -> WebCompanionSection(context = context, scope = screenScope)
            ConnectionPage.TAILSCALE     -> TailscaleSection(context = context, scope = screenScope)
            ConnectionPage.TELEGRAM      -> TelegramConnectionSection(context = context, scope = screenScope)
        }
    }
}

@Composable
internal fun ConnectionStatusCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}
