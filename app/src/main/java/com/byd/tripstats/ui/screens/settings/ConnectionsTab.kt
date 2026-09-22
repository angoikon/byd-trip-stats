package com.byd.tripstats.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.data.backup.TelegramManager
import com.byd.tripstats.util.TailscaleManager

/**
 * Settings → Connections.
 *
 * An overview of the four external connections, each of which opens **on its own page**. They used
 * to share one long details view behind an "Open Connections" button, which meant scrolling past
 * ABRP and MQTT to reach Telegram — so the cards are the navigation now, and a page shows one
 * connection and nothing else.
 */
private enum class ConnectionPage { ABRP, MQTT, TELEGRAM, TAILSCALE }

/** One overview card, kept as data so the wide and narrow layouts can't drift apart. */
private data class ConnectionCard(
    val page: ConnectionPage,
    val icon: ImageVector,
    val title: String,
    val body: String,
    val statusLine: String
)

/**
 * Below this width the cards stack one per row.
 *
 * It is the Material compact-width boundary, which is what the two cases the layout has to survive
 * both land under: the head unit rotated to portrait, and a split/multi-window layout that halves
 * the usable width. Two cards across either of those would squeeze each description into a sliver.
 */
private val STACK_BELOW_WIDTH = 600.dp

@Composable
internal fun ConnectionsTab() {
    val context = LocalContext.current
    val screenScope = rememberCoroutineScope()

    // Saved as a name rather than the enum so it survives configuration changes without a parceler.
    var openPageName by rememberSaveable { mutableStateOf<String?>(null) }
    val openPage = openPageName?.let { name -> ConnectionPage.entries.firstOrNull { it.name == name } }

    val abrpSettings = remember(openPage) { com.byd.tripstats.connections.AbrpConnectionStore.load(context) }
    val mqttSettings = remember(openPage) { com.byd.tripstats.connections.MqttConnectionStore.load(context) }
    val telegramConfig by TelegramManager.getInstance(context).config.collectAsState()
    val tailscaleStatus by TailscaleManager.status.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(icon = Icons.Filled.Link, title = stringResource(R.string.settings_tab_connections))

        if (openPage == null) {
            Text(
                stringResource(R.string.connections_overview_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val cards = listOf(
                ConnectionCard(
                    page = ConnectionPage.ABRP,
                    icon = Icons.Filled.Route,
                    title = stringResource(R.string.abrp_title_label),
                    body = stringResource(R.string.abrp_card_desc),
                    statusLine = "${if (abrpSettings.enabled) stringResource(R.string.status_enabled_label) else stringResource(R.string.status_disabled_label)} • ${abrpSettings.lastStatus}"
                ),
                ConnectionCard(
                    page = ConnectionPage.MQTT,
                    icon = Icons.Filled.Cloud,
                    title = stringResource(R.string.mqtt_title_label),
                    body = stringResource(R.string.mqtt_card_desc),
                    statusLine = "${if (mqttSettings.enabled) stringResource(R.string.status_enabled_label) else stringResource(R.string.status_disabled_label)} • ${mqttSettings.lastStatus}"
                ),
                ConnectionCard(
                    page = ConnectionPage.TELEGRAM,
                    icon = Icons.AutoMirrored.Filled.Send,
                    title = stringResource(R.string.telegram_title_label),
                    body = stringResource(R.string.telegram_card_desc),
                    statusLine = telegramConfig?.let { "@${it.botName}" }
                        ?: stringResource(R.string.status_not_connected_label)
                ),
                ConnectionCard(
                    page = ConnectionPage.TAILSCALE,
                    icon = Icons.Filled.Hub,
                    title = stringResource(R.string.tailscale_title_short),
                    body = stringResource(R.string.tailscale_card_desc),
                    statusLine = tailscaleStatus.ip
                        ?: stringResource(R.string.status_not_connected_label)
                )
            )

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val stacked = maxWidth < STACK_BELOW_WIDTH
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (stacked) {
                        cards.forEach { card ->
                            ConnectionSummaryCard(
                                modifier = Modifier.fillMaxWidth(),
                                card = card,
                                compact = true,
                                onClick = { openPageName = card.page.name }
                            )
                        }
                    } else {
                        cards.chunked(2).forEach { pair ->
                            Row(
                                // IntrinsicSize.Max makes the row as tall as its taller card and
                                // both fill it, so a longer description (MQTT's two lines) can't
                                // leave the card beside it looking stunted.
                                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                pair.forEach { card ->
                                    ConnectionSummaryCard(
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                        card = card,
                                        compact = false,
                                        onClick = { openPageName = card.page.name }
                                    )
                                }
                                // Keeps a final odd card half-width rather than letting it
                                // stretch across the row, if one is ever added or removed.
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        } else {
            TextButton(onClick = { openPageName = null }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.back_to_overview_action))
            }

            when (openPage) {
                ConnectionPage.ABRP      -> AbrpConnectionSection(context = context, scope = screenScope)
                ConnectionPage.MQTT      -> MqttConnectionSection(context = context, scope = screenScope)
                ConnectionPage.TELEGRAM  -> TelegramConnectionSection(context = context, scope = screenScope)
                ConnectionPage.TAILSCALE -> TailscaleSection(context = context, scope = screenScope)
            }
        }
    }
}

/**
 * One connection in the overview.
 *
 * [compact] switches it from the tile used in the two-across grid to a list row — icon, text,
 * chevron on one line — because four full-width tiles stacked vertically would push the last of
 * them off the screen on exactly the narrow layouts that force the stack in the first place.
 */
@Composable
private fun ConnectionSummaryCard(
    modifier: Modifier,
    card: ConnectionCard,
    compact: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        // The card is the only way in now that the Open Connections button is gone, so it has to
        // read as tappable rather than as a status tile.
        val chevron = @Composable {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }

        if (compact) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(card.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(card.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        card.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        card.statusLine,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                chevron()
            }
        } else {
            Column(
                modifier = Modifier.fillMaxHeight().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(card.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(card.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    card.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Absorbs the height difference between a one-line and a two-line description, so
                // the status lines sit on a common baseline across the pair.
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        card.statusLine,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    chevron()
                }
            }
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
