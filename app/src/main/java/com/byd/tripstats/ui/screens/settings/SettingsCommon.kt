package com.byd.tripstats.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SectionHeader(
    icon : ImageVector,
    title: String,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier              = Modifier.padding(start = 4.dp, bottom = 2.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Text(title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color)
    }
}

@Composable
internal fun SettingsGroupLabel(
    title: String
) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
    )
}

@Composable
internal fun SettingsDetailRow(
    label: String,
    value: String,
    url: String? = null,
    onClick: (() -> Unit)? = null,
    showClickIndicator: Boolean = false
) {
    val context = LocalContext.current
    val hiddenClickInteractionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(
                when {
                    url != null -> Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    onClick != null && showClickIndicator -> Modifier.clickable { onClick() }
                    onClick != null -> Modifier.clickable(
                        interactionSource = hiddenClickInteractionSource,
                        indication = null
                    ) { onClick() }
                    else -> Modifier
                }
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                style      = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color      = if (url != null || showClickIndicator) MaterialTheme.colorScheme.primary
                             else MaterialTheme.colorScheme.onSurfaceVariant
            )
            when {
                url != null -> {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Open link",
                        tint               = MaterialTheme.colorScheme.primary,
                        modifier           = Modifier.size(14.dp)
                    )
                }
                showClickIndicator -> {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint               = MaterialTheme.colorScheme.primary,
                        modifier           = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * One card in a settings tab's overview, kept as data so the wide and narrow layouts can't drift
 * apart.
 *
 * [key] names the page the card opens inside the tab. [onOpen] is for the few cards that leave
 * Settings for a screen of their own instead (Backup & Restore, Goals) — the card looks the same
 * either way, because to the user both are "tap to go there".
 */
internal class SettingsHubCard(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val body: String,
    val statusLine: String,
    val onOpen: (() -> Unit)? = null
)

/**
 * Below this width the cards stack one per row.
 *
 * It is the Material compact-width boundary, which is what the two cases the layout has to survive
 * both land under: the head unit rotated to portrait, and a split/multi-window layout that halves
 * the usable width. Two cards across either of those would squeeze each description into a sliver.
 */
private val STACK_BELOW_WIDTH = 600.dp

/**
 * A settings tab laid out as an overview of cards, each opening **on its own page** — the shape
 * Connections, App and Preferences all share, so a page shows one topic and nothing else.
 *
 * [cards] is only called while the overview is showing, so status lines are computed fresh each
 * time the user comes back to it and never while a page is open. [page] receives the open card's
 * key and draws its own heading, if it needs one — the tab's heading is left off an open page,
 * since the tab row above already names the tab. The system back gesture returns to the overview
 * first, rather than leaving Settings from the middle of a page.
 */
@Composable
internal fun SettingsHub(
    icon: ImageVector,
    title: String,
    description: String,
    cards: @Composable () -> List<SettingsHubCard>,
    page: @Composable ColumnScope.(key: String) -> Unit
) {
    // Saved as a string key so it survives configuration changes without a parceler.
    var openPage by rememberSaveable { mutableStateOf<String?>(null) }
    // Keyed on the page so opening one starts at its top, and going back starts at the overview's,
    // instead of carrying over however far down the previous view was scrolled.
    val scrollState = rememberSaveable(openPage, saver = ScrollState.Saver) { ScrollState(0) }

    BackHandler(enabled = openPage != null) { openPage = null }

    val current = openPage
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        // Tighter on the overview, so its cards fit without scrolling; pages keep the roomier gap.
        verticalArrangement = Arrangement.spacedBy(if (current == null) 12.dp else 16.dp)
    ) {
        if (current == null) {
            SectionHeader(icon = icon, title = title)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingsHubGrid(
                cards = cards(),
                onOpen = { card -> card.onOpen?.invoke() ?: run { openPage = card.key } }
            )
        } else {
            TextButton(onClick = { openPage = null }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.back_to_overview_action))
            }
            page(current)
        }
    }
}

@Composable
private fun SettingsHubGrid(cards: List<SettingsHubCard>, onOpen: (SettingsHubCard) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stacked = maxWidth < STACK_BELOW_WIDTH
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (stacked) {
                cards.forEach { card ->
                    SettingsHubCardView(
                        modifier = Modifier.fillMaxWidth(),
                        card = card,
                        compact = true,
                        onClick = { onOpen(card) }
                    )
                }
            } else {
                cards.chunked(2).forEach { pair ->
                    Row(
                        // IntrinsicSize.Max makes the row as tall as its taller card and both fill
                        // it, so a longer description can't leave the card beside it looking stunted.
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        pair.forEach { card ->
                            SettingsHubCardView(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                card = card,
                                compact = false,
                                onClick = { onOpen(card) }
                            )
                        }
                        // Keeps a final odd card half-width rather than letting it stretch across
                        // the row.
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * One card in the overview.
 *
 * [compact] switches it from the tile used in the two-across grid to a list row — icon, text,
 * chevron on one line — because full-width tiles stacked vertically would push the last of them
 * off the screen on exactly the narrow layouts that force the stack in the first place.
 */
@Composable
private fun SettingsHubCardView(
    modifier: Modifier,
    card: SettingsHubCard,
    compact: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        // The card is the only way in, so it has to read as tappable rather than as a status tile.
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
            // Icon beside the title rather than above it, and tight spacing: three rows of these
            // (Preferences has six cards, Connections five) must fit the head unit's landscape
            // height without scrolling.
            Column(
                modifier = Modifier.fillMaxHeight().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(card.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Text(card.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
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

/**
 * The full-width, error-coloured outlined button a connection page uses to unlink itself
 * (Telegram's bot, the car's Tailscale sign-in). A real button rather than a text link: it
 * undoes something the features on that page depend on, so it should read as an action.
 */
@Composable
internal fun DisconnectButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 1f else 0.38f)),
    ) {
        Icon(Icons.Filled.LinkOff, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

internal fun formatFriendlyTimestamp(epochMs: Long): String {
    val formatter = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    return formatter.format(Date(epochMs))
}
