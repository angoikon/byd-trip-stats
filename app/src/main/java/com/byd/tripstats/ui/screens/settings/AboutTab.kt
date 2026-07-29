package com.byd.tripstats.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.byd.tripstats.R
import com.byd.tripstats.ui.theme.AccelerationOrange
import com.byd.tripstats.ui.theme.BydElectricAzure
import com.byd.tripstats.ui.theme.RegenGreen
import com.byd.tripstats.ui.viewmodel.DashboardViewModel

@Composable
internal fun AboutTab(viewModel: DashboardViewModel) {
    val updateInfo        by viewModel.updateInfo.collectAsState()
    val downloadProgress  by viewModel.downloadProgress.collectAsState()
    val downloadedApk     by viewModel.downloadedApk.collectAsState()
    val canInstallNow     by viewModel.canInstallNow.collectAsState()
    val isCheckingUpdate  by viewModel.isCheckingUpdate.collectAsState()

    var easterEggClicks by remember { mutableStateOf(0) }
    var licenseClicks by remember { mutableStateOf(0) }
    var showChangelogDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (showChangelogDialog) {
        val changelogText = remember {
            runCatching {
                context.assets.open("changelog.md").bufferedReader().readText()
            }.getOrDefault("Changelog not available.")
        }
        AlertDialog(
            onDismissRequest = { showChangelogDialog = false },
            containerColor   = MaterialTheme.colorScheme.surfaceVariant,
            title = {
                Text(stringResource(R.string.about_changelog_label), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text  = changelogText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showChangelogDialog = false }) { Text("Close") }
            }
        )
    }

    val hasBackgroundLocation = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    Column(
        modifier            = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(icon = Icons.Filled.Info, title = stringResource(R.string.settings_tab_about))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingsDetailRow("App",       "BYD Trip Stats")
                SettingsDetailRow(stringResource(R.string.about_version_label), com.byd.tripstats.BuildConfig.VERSION_NAME)
                SettingsDetailRow(stringResource(R.string.about_changelog_label), stringResource(R.string.about_whats_new_label), onClick = { showChangelogDialog = true }, showClickIndicator = true)
                SettingsDetailRow(stringResource(R.string.about_author_label), "Angelos Oikonomou (angoikon)")
                SettingsDetailRow(
                    label = stringResource(R.string.about_platform_label),
                    value = "Android 10 · API 29",
                    onClick = {
                        easterEggClicks++
                        if (easterEggClicks >= 5) {
                            easterEggClicks = 0
                            try {
                                val intent = Intent().apply {
                                    setClassName("com.byd.countrycodetool", "com.byd.countrycodetool.MainActivity")
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    Runtime.getRuntime().exec(arrayOf("am", "start", "-n", "com.byd.countrycodetool/com.byd.countrycodetool.MainActivity"))
                                } catch (e2: Exception) {
                                    android.widget.Toast.makeText(context, "Could not open tool", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                )
                SettingsDetailRow(
                    label = stringResource(R.string.about_license_label),
                    value = stringResource(R.string.about_license_value),
                    onClick = {
                        licenseClicks++
                        if (licenseClicks >= 5) {
                            licenseClicks = 0
                            try {
                                val intent = Intent().apply {
                                    setClassName("com.byd.byddevelopmenttools", "com.byd.byddevelopmenttools.VerificationActivity")
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    Runtime.getRuntime().exec(arrayOf("am", "start", "-n", "com.byd.byddevelopmenttools/com.byd.byddevelopmenttools.VerificationActivity"))
                                } catch (e2: Exception) {
                                    android.widget.Toast.makeText(context, "Could not open tool", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                )
                SettingsDetailRow(stringResource(R.string.about_site_label), "https://byd-trip-stats.angoikon.workers.dev",
                    url = "https://byd-trip-stats.angoikon.workers.dev/")
                SettingsDetailRow(stringResource(R.string.about_github_label), "github.com/angoikon/byd-trip-stats",
                    url = "https://github.com/angoikon/byd-trip-stats")
                SettingsDetailRow(stringResource(R.string.about_discord_label), "Join now", url = "https://discord.gg/pf8TjjTce9")
                if (!hasBackgroundLocation) {
                    SettingsDetailRow(
                        label = stringResource(R.string.about_background_op_label),
                        value = stringResource(R.string.about_adb_setup_warning)
                    )
                }
            }
        }

        UpdateCard(
            updateInfo       = updateInfo,
            downloadProgress = downloadProgress,
            downloadedApk    = downloadedApk,
            canInstallNow    = canInstallNow,
            isChecking       = isCheckingUpdate,
            isDiLink5        = com.byd.tripstats.sdk.DiLink5Platform.isDiLink5,
            onDownload       = { viewModel.downloadUpdate() },
            onInstall        = { viewModel.installUpdate() },
            onCancel         = { viewModel.cancelDownload() },
            onCheckNow       = { viewModel.checkForUpdateManually() }
        )

        HorizontalDivider()
        SectionHeader(icon = Icons.AutoMirrored.Filled.Help, title = stringResource(R.string.faq_section_title))

        buildFaqList(context).forEach { (q, a, u) -> FaqItem(question = q, answer = a, url = u) }

        Spacer(Modifier.height(8.dp))
    }
}

// ── Update card ───────────────────────────────────────────────────────────────

@Composable
private fun UpdateCard(
    updateInfo      : com.byd.tripstats.data.repository.UpdateRepository.UpdateInfo?,
    downloadProgress: Int?,
    downloadedApk   : java.io.File?,
    canInstallNow   : Boolean,
    isChecking      : Boolean = false,
    isDiLink5       : Boolean = false,
    onDownload      : () -> Unit,
    onInstall       : () -> Unit,
    onCancel        : () -> Unit,
    onCheckNow      : () -> Unit = {}
) {
    val isDownloading = downloadProgress != null && downloadProgress in 0..99
    val isReady       = downloadedApk != null && downloadProgress == 100

    if (updateInfo == null && !isDownloading && !isReady) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(
                modifier              = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.CheckCircle, null, tint = RegenGreen, modifier = Modifier.size(20.dp))
                    }
                    Text(
                        if (isChecking) stringResource(R.string.about_checking_updates) else stringResource(R.string.about_up_to_date),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (!isChecking) {
                    TextButton(onClick = onCheckNow, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(stringResource(R.string.about_check_now_action), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        return
    }

    // DiLink-5: the in-app download + silent PackageInstaller can't complete on the head unit
    // (unprivileged, no installer UI), so surface the update as available with manual `adb install -r`
    // instructions instead of the Download/Install flow. The badge on the About tab still appears
    // (it keys off updateInfo). DiLink-3 keeps the full auto-update card below.
    if (isDiLink5 && updateInfo != null) {
        Di5ManualUpdateCard(updateInfo)
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = when {
                isReady            -> RegenGreen.copy(alpha = 0.15f)
                updateInfo != null -> AccelerationOrange.copy(alpha = 0.12f)
                else               -> MaterialTheme.colorScheme.primaryContainer
            }
        ),
        border = BorderStroke(
            1.dp,
            when {
                isReady            -> RegenGreen.copy(alpha = 0.5f)
                updateInfo != null -> AccelerationOrange.copy(alpha = 0.4f)
                else               -> MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when {
                        isReady       -> Icons.Filled.SystemUpdate
                        isDownloading -> Icons.Filled.Downloading
                        else          -> Icons.Filled.NewReleases
                    },
                    contentDescription = null,
                    tint     = when {
                        isReady       -> RegenGreen
                        isDownloading -> BydElectricAzure
                        else          -> AccelerationOrange
                    },
                    modifier = Modifier.size(22.dp)
                )
                Column {
                    Text(
                        when {
                            isReady       -> stringResource(R.string.about_update_ready, updateInfo?.latestVersion ?: "")
                            isDownloading -> stringResource(R.string.about_downloading, updateInfo?.latestVersion ?: "")
                            else          -> stringResource(R.string.about_update_available, updateInfo?.latestVersion ?: "")
                        },
                        style      = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (updateInfo != null && !isDownloading && !isReady) {
                        Text(
                            stringResource(R.string.about_current_version, updateInfo.currentVersion),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (isDownloading) {
                val animatedProgress by animateFloatAsState(
                    targetValue   = (downloadProgress ?: 0) / 100f,
                    animationSpec = tween(300),
                    label         = "updateProgress"
                )
                LinearProgressIndicator(
                    progress   = { animatedProgress },
                    modifier   = Modifier.fillMaxWidth(),
                    color      = BydElectricAzure,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                Text(
                    stringResource(R.string.about_download_progress, downloadProgress ?: 0, updateInfo?.apkName ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (updateInfo != null && !isDownloading && !isReady && updateInfo.releaseNotes.isNotBlank()) {
                Text(
                    updateInfo.releaseNotes,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isReady && !canInstallNow) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Info, null,
                        tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp))
                    Text(
                        stringResource(R.string.about_install_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    isReady -> {
                        Button(
                            onClick  = onInstall,
                            enabled  = canInstallNow,
                            colors   = ButtonDefaults.buttonColors(containerColor = RegenGreen),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.InstallMobile, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.about_install_now))
                        }
                    }
                    isDownloading -> {
                        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                    else -> {
                        Button(
                            onClick  = onDownload,
                            colors   = ButtonDefaults.buttonColors(containerColor = BydElectricAzure),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.about_download_update))
                        }
                    }
                }
            }
        }
    }
}

// DiLink-5 manual-update card: update detected, but installed by the user via `adb install -r`.
@Composable
private fun Di5ManualUpdateCard(
    updateInfo: com.byd.tripstats.data.repository.UpdateRepository.UpdateInfo
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = AccelerationOrange.copy(alpha = 0.12f)),
        border   = BorderStroke(1.dp, AccelerationOrange.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.NewReleases, null, tint = AccelerationOrange, modifier = Modifier.size(22.dp))
                Column {
                    Text(
                        stringResource(R.string.about_update_available, updateInfo.latestVersion),
                        style      = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.about_current_version, updateInfo.currentVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (updateInfo.releaseNotes.isNotBlank()) {
                Text(
                    updateInfo.releaseNotes,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Info, null,
                    tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp))
                Text(
                    stringResource(R.string.about_update_manual_di5_instructions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // The exact command, monospace so it copies accurately.
            Surface(
                color    = MaterialTheme.colorScheme.surfaceVariant,
                shape    = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.about_update_manual_di5_command, updateInfo.apkName),
                    style      = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier   = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }

            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/angoikon/byd-trip-stats/releases/latest"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.about_update_open_releases))
            }
        }
    }
}

@Composable
private fun FaqItem(question: String, answer: String, url: String? = null) {
    val context  = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                MaterialTheme.shapes.medium
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    question,
                    style      = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier   = Modifier.weight(1f).padding(end = 8.dp)
                )
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    Text(answer, style = MaterialTheme.typography.bodyMedium)
                    if (url != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .clickable {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    )
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector        = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Open link",
                                tint               = MaterialTheme.colorScheme.primary,
                                modifier           = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                url.removePrefix("https://").removePrefix("http://"),
                                style      = MaterialTheme.typography.bodySmall,
                                color      = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class FaqEntry(val question: String, val answer: String, val url: String? = null)

private fun buildFaqList(context: android.content.Context): List<FaqEntry> = listOf(

    FaqEntry(
        context.getString(R.string.faq_q_no_electro),
        context.getString(R.string.faq_a_no_electro)
    ),

    FaqEntry(
        context.getString(R.string.faq_q_internet),
        context.getString(R.string.faq_a_internet)
    ),

    FaqEntry(
        context.getString(R.string.faq_q_pro),
        context.getString(R.string.faq_a_pro)
    ),

    FaqEntry(
        context.getString(R.string.faq_q_drive_modes),
        context.getString(R.string.faq_a_drive_modes)
    ),

    FaqEntry(
        context.getString(R.string.faq_q_offline_trip),
        context.getString(R.string.faq_a_offline_trip)
    ),

    FaqEntry(
        context.getString(R.string.faq_q_stops_restart),
        context.getString(R.string.faq_a_stops_restart)
    ),

    FaqEntry(
        context.getString(R.string.faq_q_backup),
        context.getString(R.string.faq_a_backup)
    ),

    FaqEntry(
        context.getString(R.string.faq_q_recover),
        context.getString(R.string.faq_a_recover)
    ),

    FaqEntry(
        context.getString(R.string.faq_q_auto_backup),
        context.getString(R.string.faq_a_auto_backup)
    ),

    FaqEntry(
        context.getString(R.string.faq_q_adb_restore),
        context.getString(R.string.faq_a_adb_restore)
    ),

    FaqEntry(
        context.getString(R.string.faq_q_12v_drain),
        context.getString(R.string.faq_a_12v_drain)
    ),

    FaqEntry(
        context.getString(R.string.faq_q_excel),
        context.getString(R.string.faq_a_excel)
    ),

    FaqEntry(
        context.getString(R.string.faq_q_privacy),
        context.getString(R.string.faq_a_privacy)
    ),

    FaqEntry(
        context.getString(R.string.faq_q_help),
        context.getString(R.string.faq_a_help),
        url = "https://discord.gg/pf8TjjTce9"
    )
)
