package com.byd.tripstats.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.byd.tripstats.R
import com.byd.tripstats.ui.components.BrandSwitch
import com.byd.tripstats.data.preferences.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WebCompanionSection(context: Context, scope: CoroutineScope) {
    val preferencesManager = remember { PreferencesManager(context) }
    // DataStore is the authoritative state — the switch just writes here and
    // the LaunchedEffect reacts, so the toggle is always responsive.
    val enabled by preferencesManager.webServerEnabled.collectAsState(initial = true)
    val port    by preferencesManager.webServerPort.collectAsState(
        initial = PreferencesManager.DEFAULT_WEB_SERVER_PORT
    )
    val pin     by preferencesManager.webServerPin.collectAsState(initial = "")
    var portInput   by remember(port) { mutableStateOf(port.toString()) }
    var pinInput    by remember(pin)  { mutableStateOf(pin) }
    var pinVisible   by remember { mutableStateOf(false) }
    // Every address the companion answers on, not a guess at the "main" one — the car can be on
    // Wi-Fi and a tailnet at once, and which one the user needs depends on where they are.
    var accessUrls   by remember {
        mutableStateOf<List<com.byd.tripstats.server.WebServerManager.AccessUrl>>(emptyList())
    }
    var serverError  by remember { mutableStateOf<String?>(null) }
    val lockedCount  by com.byd.tripstats.server.WebServerManager.lockedOutCount.collectAsState()
    val clipManager = androidx.compose.ui.platform.LocalClipboardManager.current

    // Ensure a PIN exists as soon as the section is first composed
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { preferencesManager.getOrCreateWebServerPin() }
    }

    // Start/stop the server whenever enabled, port, or PIN changes.
    // Runs on IO so ServerSocket binding never touches the main thread.
    LaunchedEffect(enabled, port, pin) {
        com.byd.tripstats.server.WebServerManager.stop()
        accessUrls  = emptyList()
        serverError = null
        if (enabled && pin.isNotEmpty()) {
            val error = withContext(Dispatchers.IO) {
                com.byd.tripstats.server.WebServerManager.start(context, port, pin)
            }
            if (error == null) {
                // Interface enumeration is a filesystem read on Android; keep it off the main thread.
                accessUrls  = withContext(Dispatchers.IO) {
                    com.byd.tripstats.server.WebServerManager.listAccessUrls(context)
                }
                serverError = null
            } else {
                accessUrls  = emptyList()
                serverError = error
            }
        }
    }

    // The Tailscale card can add or remove the https:// address while this screen is open, and the
    // effect above only re-runs when the server itself is reconfigured — so without this the list
    // would keep showing plain http until Settings was left and reopened. Deliberately separate,
    // because folding it into the keys above would restart the server on every tailnet change.
    // Only a non-empty result is taken, so this can't race the server's own startup to empty.
    val tailscaleStatus by com.byd.tripstats.util.TailscaleManager.status.collectAsState()
    LaunchedEffect(enabled, tailscaleStatus.httpsUrl, tailscaleStatus.ip) {
        if (enabled && pin.isNotEmpty()) {
            val fresh = withContext(Dispatchers.IO) {
                com.byd.tripstats.server.WebServerManager.listAccessUrls(context)
            }
            if (fresh.isNotEmpty()) accessUrls = fresh
        }
    }

    SectionHeader(icon = Icons.Filled.Language, title = stringResource(R.string.web_companion_label))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.web_companion_label),
                        style      = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (enabled)
                            stringResource(R.string.web_companion_desc)
                        else
                            stringResource(R.string.web_companion_disabled_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BrandSwitch(
                    checked         = enabled,
                    onCheckedChange = { next ->
                        scope.launch { preferencesManager.saveWebServerEnabled(next) }
                    },
                )
            }

            if (enabled) {
                Text(
                    stringResource(R.string.web_companion_wifi_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.web_companion_files_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Port row — always visible so users can change it even while disabled
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.port_field_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val p = portInput.toIntOrNull()?.coerceIn(1024, 65535) ?: return@Button
                        scope.launch { preferencesManager.saveWebServerPort(p) }
                    },
                    enabled = portInput.toIntOrNull()?.let { it in 1024..65535 } == true &&
                              portInput.toIntOrNull() != port
                ) { Text(stringResource(R.string.apply)) }
            }
            Text(
                stringResource(R.string.port_hint_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))

            // ── PIN ──────────────────────────────────────────────────────────
            Text(
                stringResource(R.string.web_access_pin_label),
                style      = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                stringResource(R.string.web_pin_browser_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val strHidePin = stringResource(R.string.web_pin_hide_cd)
            val strShowPin = stringResource(R.string.web_pin_show_cd)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it.filter(Char::isDigit).take(10) },
                    label = { Text(stringResource(R.string.web_pin_field_label)) },
                    singleLine = true,
                    visualTransformation = if (pinVisible)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon = {
                        IconButton(onClick = { pinVisible = !pinVisible }) {
                            Icon(
                                if (pinVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (pinVisible) strHidePin else strShowPin
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                // Apply custom PIN
                Button(
                    onClick = {
                        if (pinInput.length >= 4) scope.launch {
                            preferencesManager.saveWebServerPin(pinInput)
                        }
                    },
                    enabled = pinInput.length >= 4 && pinInput != pin
                ) { Text(stringResource(R.string.web_pin_set)) }
                // Generate a new random PIN
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val newPin = (100_000..999_999).random().toString()
                            pinInput = newPin
                            preferencesManager.saveWebServerPin(newPin)
                        }
                    }
                ) { Text(stringResource(R.string.web_pin_regen)) }
            }
            Text(
                stringResource(R.string.web_pin_min_digits),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))

            // ── Lockout banner ───────────────────────────────────────────────
            if (lockedCount > 0) {
                val strLocked = if (lockedCount == 1)
                    stringResource(R.string.web_ip_locked_one)
                else
                    stringResource(R.string.web_ip_locked_many, lockedCount)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint     = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            strLocked,
                            style      = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color      = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            stringResource(R.string.web_too_many_attempts),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                        )
                    }
                    OutlinedButton(
                        onClick = { com.byd.tripstats.server.WebServerManager.clearLockouts() },
                        colors  = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text(stringResource(R.string.clear_lockout_action)) }
                }
            }

            if (enabled) {
                if (serverError != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint     = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            stringResource(R.string.web_server_failed, serverError ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else if (accessUrls.isEmpty()) {
                    Text(
                        stringResource(R.string.web_server_waiting),
                        style      = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                } else {
                    // One row per address. A car on Wi-Fi and a tailnet at the same time has two,
                    // and only the user knows which side of the front door they are on.
                    accessUrls.forEach { access ->
                        val label = when (access.kind) {
                            com.byd.tripstats.server.WebServerManager.AccessKind.LAN ->
                                stringResource(R.string.web_url_label_lan)
                            com.byd.tripstats.server.WebServerManager.AccessKind.TAILNET_HTTPS ->
                                stringResource(R.string.web_url_label_tailscale_https)
                            com.byd.tripstats.server.WebServerManager.AccessKind.TAILNET ->
                                stringResource(R.string.web_url_label_tailscale)
                            com.byd.tripstats.server.WebServerManager.AccessKind.VPN ->
                                stringResource(R.string.web_url_label_vpn)
                            // Only a publicly routable cellular address reaches this list at all —
                            // a carrier-NAT one is filtered out, since nothing can connect to it.
                            com.byd.tripstats.server.WebServerManager.AccessKind.MOBILE ->
                                stringResource(R.string.web_url_label_mobile)
                            com.byd.tripstats.server.WebServerManager.AccessKind.OTHER ->
                                stringResource(R.string.web_url_label_other)
                        }
                        Column(modifier = Modifier.padding(bottom = 6.dp)) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    access.url,
                                    style      = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    modifier   = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick  = { clipManager.setText(AnnotatedString(access.url)) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = stringResource(R.string.web_copy_url_cd),
                                        modifier = Modifier.size(18.dp),
                                        tint     = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    // The switch that encrypts these lives on the Tailscale card, but the question
                    // "why is this still http?" occurs here, looking at the addresses — so the
                    // pointer belongs here too, and only while there is something to encrypt.
                    val hasTailnet = accessUrls.any {
                        it.kind == com.byd.tripstats.server.WebServerManager.AccessKind.TAILNET
                    }
                    val hasHttps = accessUrls.any {
                        it.kind == com.byd.tripstats.server.WebServerManager.AccessKind.TAILNET_HTTPS
                    }
                    if (hasTailnet && !hasHttps) {
                        Text(
                            stringResource(R.string.web_https_available_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.web_server_open_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    RemoteAccessBlock(context = context)
                }
            }
        }
    }
}

/**
 * Remote access over a tailnet.
 *
 * Deliberately informational: the app ships no tunnel and no dependency on Tailscale — the user
 * installs the official client themselves, and the companion becomes reachable on it for free
 * because the server already binds 0.0.0.0. All this does is notice, and warn about the three
 * settings that would quietly break something.
 */
@Composable
private fun RemoteAccessBlock(context: Context) {
    // Cheap (one interface scan + one package lookup) and only on entering the screen.
    val tailnetAddress by produceState<String?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            com.byd.tripstats.server.WebServerManager.tailnetAddress(context)
        }
    }

    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))

    Text(
        stringResource(R.string.remote_access_label),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        tailnetAddress?.let { stringResource(R.string.remote_access_connected, it) }
            ?: stringResource(R.string.remote_access_not_detected),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.remote_access_tips),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Stated on DiLink-5 whether or not Tailscale is installed: it is the reason someone there
    // would otherwise think the setup had failed. The feature is not gated — while the car is on
    // it behaves exactly as it does on DiLink-3, which is enough to reach ADB away from home.
    if (com.byd.tripstats.sdk.DiLink5Platform.isDiLink5) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.remote_access_di5_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
