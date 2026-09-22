package com.byd.tripstats.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.byd.tripstats.R
import com.byd.tripstats.ui.components.BrandSwitch
import com.byd.tripstats.sdk.DiLink5Platform
import com.byd.tripstats.server.WebServerManager
import com.byd.tripstats.ui.theme.RegenGreen
import com.byd.tripstats.util.QrCodeGenerator
import com.byd.tripstats.util.TailscaleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Remote access over Tailscale: paste an auth key, and the car joins the user's private network so
 * the web companion and ADB work from anywhere.
 *
 * The instructions are part of the screen rather than a link to a doc, because this is the one
 * feature whose setup happens somewhere else entirely — in Tailscale's admin console — and a user
 * standing at their car has no way to guess the four steps. Everything the console needs is listed,
 * including the two settings that quietly break it later.
 */
@Composable
internal fun TailscaleSection(context: Context, scope: CoroutineScope) {
    val status by TailscaleManager.status.collectAsState()
    val clip = LocalClipboardManager.current
    val supported = remember { TailscaleManager.isSupported(context) }

    var authKeyInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var showAlternatives by remember { mutableStateOf(false) }

    // Ask the daemon what it thinks on first composition — it outlives our process, so the app can
    // come back to a car that is already on the tailnet.
    LaunchedEffect(Unit) {
        if (supported) runCatching { TailscaleManager.refresh(context) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Hub, null, modifier = Modifier.size(22.dp))
                Text(
                    stringResource(R.string.tailscale_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                stringResource(R.string.tailscale_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!supported) {
                StatusLine(Icons.Filled.Info, stringResource(R.string.tailscale_unavailable))
                return@Column
            }

            when (status.state) {
                TailscaleManager.State.RUNNING -> {
                    StatusLine(
                        Icons.Filled.CheckCircle,
                        stringResource(R.string.tailscale_connected, status.hostname ?: "—"),
                        RegenGreen,
                    )
                    status.ip?.let { ip ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                ip,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { clip.setText(AnnotatedString(ip)) }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(R.string.web_copy_url_cd),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.tailscale_reachable_hint, ip),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TailscaleManager.State.NEEDS_ADB ->
                    StatusLine(Icons.Filled.Warning, stringResource(R.string.tailscale_needs_adb), MaterialTheme.colorScheme.error)
                TailscaleManager.State.STARTING ->
                    StatusLine(Icons.Filled.HourglassTop, stringResource(R.string.tailscale_starting))
                TailscaleManager.State.ERROR ->
                    StatusLine(
                        Icons.Filled.Error,
                        status.detail ?: stringResource(R.string.tailscale_error),
                        MaterialTheme.colorScheme.error,
                    )
                else ->
                    StatusLine(Icons.Filled.Info, stringResource(R.string.tailscale_not_connected))
            }

            // ── Setup, in the order the user has to do it ──────────────────────
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.tailscale_how_to))
            }
            if (expanded) {
                listOf(
                    R.string.tailscale_step_1,
                    R.string.tailscale_step_2,
                    R.string.tailscale_step_3,
                    R.string.tailscale_step_4,
                ).forEach { step ->
                    Text(
                        stringResource(step),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.tailscale_console_warnings),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (DiLink5Platform.isDiLink5) {
                    Text(
                        stringResource(R.string.tailscale_di5_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (status.state == TailscaleManager.State.RUNNING) {
                HttpsRow(context = context, scope = scope, status = status)
            }

            // ── Signing in ────────────────────────────────────────────────────
            // Three ways in, easiest first. The QR is the default because a head unit has no
            // keyboard worth the name and an auth key is 60+ characters: scanning it means the
            // user never types, and nothing long-lived exists to leak.
            if (status.state == TailscaleManager.State.AWAITING_LOGIN && status.authUrl != null) {
                SignInQr(url = status.authUrl!!)
                TextButton(
                    onClick = {
                        scope.launch {
                            try { TailscaleManager.cancelBrowserLogin(context) } finally { busy = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.tailscale_cancel_signin)) }
            }

            if (status.state == TailscaleManager.State.RUNNING) {
                TextButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            try { TailscaleManager.disconnect(context) } finally { busy = false }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.tailscale_disconnect),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else if (status.state != TailscaleManager.State.NEEDS_ADB &&
                status.state != TailscaleManager.State.AWAITING_LOGIN
            ) {
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val s = TailscaleManager.beginBrowserLogin(context)
                                if (s.state == TailscaleManager.State.AWAITING_LOGIN) {
                                    // Keep watching while the user is on their phone; the daemon
                                    // waits regardless, so this only drives the on-screen state.
                                    TailscaleManager.awaitBrowserLogin(context)
                                }
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Filled.QrCode2, null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tailscale_signin_phone))
                }

                TextButton(
                    onClick = { showAlternatives = !showAlternatives },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(
                        if (showAlternatives) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.tailscale_other_ways))
                }

                if (!showAlternatives) return@Column

                Text(
                    stringResource(R.string.tailscale_alt_adb_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.tailscale_alt_adb_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "adb shell input text \"tskey-auth-…\"",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )

                Text(
                    stringResource(R.string.tailscale_alt_key_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.tailscale_alt_key_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = authKeyInput,
                    onValueChange = { authKeyInput = it },
                    label = { Text(stringResource(R.string.tailscale_authkey_label)) },
                    placeholder = { Text("tskey-auth-…") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            // try/finally, not a bare sequence: anything thrown inside connect()
                            // used to skip `busy = false` and leave the button spinning forever,
                            // with no way back except leaving the screen. The spinner must always
                            // stop, whatever happened.
                            try {
                                TailscaleManager.connect(context, authKeyInput)
                                authKeyInput = ""
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = authKeyInput.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Filled.Link, null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tailscale_connect))
                }
            }
        }
    }
}

@Composable
private fun StatusLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * The sign-in URL as a QR code. The car has no keyboard worth using, so the whole point is that
 * the user scans this with the phone they are already holding and finishes in a real browser —
 * no auth key generated, copied, typed, or left lying around to leak.
 *
 * The URL is shown as text too: a head unit's screen can defeat a camera in direct sunlight, and
 * then reading twelve characters off the glass beats every other option.
 */
@Composable
private fun SignInQr(url: String) {
    val sizePx = with(LocalDensity.current) { 200.dp.roundToPx() }
    val qr = remember(url) { QrCodeGenerator.generate(url, sizePx)?.asImageBitmap() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.tailscale_scan_instructions),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (qr != null) {
            Image(
                bitmap = qr,
                contentDescription = stringResource(R.string.tailscale_scan_qr_cd),
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .padding(10.dp)
                    .size(200.dp),
            )
        }
        Text(
            url,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.tailscale_waiting_for_signin),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The HTTPS switch, shown only once the car is actually on the tailnet — there is nothing to
 * secure before that, and the daemon can't ask for a certificate until it has a name.
 *
 * Turning it on hands the companion to `tailscale serve`, which listens on 443 for this node,
 * terminates TLS with a real Let's Encrypt certificate for its MagicDNS name, and proxies to our
 * plain-HTTP server on loopback. The payoff beyond the padlock is that browsers only permit
 * notifications, service workers and clipboard writes on a secure origin.
 */
@Composable
private fun HttpsRow(
    context: android.content.Context,
    scope: CoroutineScope,
    status: TailscaleManager.Status,
) {
    val clip = LocalClipboardManager.current
    var busy by remember { mutableStateOf(false) }
    var showSteps by remember { mutableStateOf(false) }
    // From the manager, not from local state: this row is only shown while RUNNING, and the
    // operation it starts can briefly take the daemon out of that state — which discarded the
    // local value and re-read a preference the operation hadn't written yet, so the switch sprang
    // back and needed a second press.
    val enabled by TailscaleManager.httpsEnabled.collectAsState()
    LaunchedEffect(Unit) { TailscaleManager.isHttpsEnabled(context) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.tailscale_https_label),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.tailscale_https_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BrandSwitch(
                checked = enabled,
                enabled = !busy,
                onCheckedChange = { want ->
                    busy = true
                    scope.launch {
                        try {
                            // The daemon is the authority — a tailnet without HTTPS certificates
                            // refuses — so the switch follows what it reports, never the request.
                            if (want) TailscaleManager.enableHttps(context, WebServerManager.currentPort())
                            else TailscaleManager.disableHttps(context)
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }

        status.httpsUrl?.takeIf { enabled }?.let { url ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    url,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { clip.setText(AnnotatedString(url)) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.web_copy_url_cd),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // Right under the URL, because appending the companion's port out of habit is the
            // obvious mistake here and it fails as a TLS error, which reads like a broken
            // certificate rather than a wrong address.
            Text(
                stringResource(R.string.tailscale_https_no_port),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.tailscale_https_first_load),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            stringResource(R.string.tailscale_https_requirement),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The daemon's own refusal, which usually names the missing admin-console setting.
        status.detail?.takeIf { !enabled }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        // The two prerequisites live in a web console the user reaches from another device, so the
        // steps are spelled out here rather than linked — the same reason the sign-in steps are.
        TextButton(onClick = { showSteps = !showSteps }, contentPadding = PaddingValues(0.dp)) {
            Icon(
                if (showSteps) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.tailscale_https_how_to))
        }
        if (showSteps) {
            listOf(
                R.string.tailscale_https_step_1,
                R.string.tailscale_https_step_2,
                R.string.tailscale_https_step_3,
                R.string.tailscale_https_step_4,
                R.string.tailscale_https_step_5,
            ).forEach { step ->
                Text(
                    stringResource(step),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.tailscale_https_free_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
