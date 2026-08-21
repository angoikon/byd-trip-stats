package com.byd.tripstats.ui.screens.settings

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.byd.tripstats.R
import com.byd.tripstats.adb.AdbPermissionManager
import com.byd.tripstats.data.backup.TelegramManager
import com.byd.tripstats.ui.theme.BydElectricAzure
import com.byd.tripstats.ui.theme.ToggleUncheckedTrack
import com.byd.tripstats.ui.theme.AccelerationOrange
import com.byd.tripstats.ui.theme.BatteryBlue
import com.byd.tripstats.ui.theme.RegenGreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

internal data class AppDiagnosticsSnapshot(
    val processCpuPercent: Double = 0.0,
    val systemCpuPercent: Double? = null,
    val rssMb: Int = 0,
    val pssMb: Int = 0,
    val javaHeapMb: Int = 0,
    val nativeHeapMb: Int = 0,
    val systemMemoryUsedPercent: Double = 0.0,
    val systemMemoryUsedMb: Int = 0,
    val systemMemoryTotalMb: Int = 0,
    val threadCount: Int = 0,
    val uptimeMinutes: Int = 0,
    val frameP50Ms: Double? = null,
    val frameP95Ms: Double? = null,
    val jankPercent: Double? = null,
    val history: List<DiagnosticsHistorySample> = emptyList(),
)

internal data class DiagnosticsHistorySample(
    val timestampMs: Long,
    val appCpuPercent: Double,
    val systemCpuPercent: Double?,
    val appMemoryMb: Double,
    val systemMemoryPercent: Double
)

internal data class CpuStat(
    val total: Long,
    val idle: Long
)

internal object AppDiagnosticsMonitor {
    private const val PREFS_NAME = "app_diagnostics"
    private const val KEY_ENABLED = "details_enabled"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _snapshot = MutableStateFlow(AppDiagnosticsSnapshot())
    private val _enabled = MutableStateFlow(false)
    private var initialized = false
    private var samplingJob: Job? = null

    val snapshot: StateFlow<AppDiagnosticsSnapshot> = _snapshot.asStateFlow()
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val persisted = prefs.getBoolean(KEY_ENABLED, false)
        _enabled.value = persisted
        if (persisted) startSampling(context.applicationContext)
    }

    @Synchronized
    fun setEnabled(context: Context, value: Boolean) {
        initialize(context)
        if (_enabled.value == value) return
        _enabled.value = value
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, value)
            .apply()
        if (value) {
            startSampling(context.applicationContext)
        } else {
            stopSampling()
        }
    }

    @Synchronized
    private fun startSampling(context: Context) {
        if (samplingJob?.isActive == true) return
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        samplingJob = scope.launch {
            var lastCpuMs = Process.getElapsedCpuTime()
            var lastWallMs = SystemClock.elapsedRealtime()
            var lastSystemCpu = readCpuStat()
            val history = ArrayDeque(_snapshot.value.history)

            while (true) {
                val nowCpuMs = Process.getElapsedCpuTime()
                val nowWallMs = SystemClock.elapsedRealtime()
                val cpuDelta = max(0L, nowCpuMs - lastCpuMs)
                val wallDelta = max(1L, nowWallMs - lastWallMs)
                val cpuPercent = ((cpuDelta.toDouble() / wallDelta.toDouble()) * 100.0).coerceAtLeast(0.0)
                lastCpuMs = nowCpuMs
                lastWallMs = nowWallMs

                val currentSystemCpu = readCpuStat()
                val systemCpuPercent = calculateSystemCpuPercent(lastSystemCpu, currentSystemCpu)
                lastSystemCpu = currentSystemCpu

                val memInfo = activityManager.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
                val systemMemInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
                val runtime = Runtime.getRuntime()
                val javaHeapMb = (((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024))).toInt()
                val nativeHeapMb = (Debug.getNativeHeapAllocatedSize() / (1024 * 1024)).toInt()
                val pssMb = memInfo?.totalPss?.div(1024) ?: 0
                val rssMb = memInfo?.totalPrivateDirty?.div(1024) ?: 0
                val systemTotalMb = (systemMemInfo.totalMem / (1024 * 1024)).toInt()
                val systemUsedMb = ((systemMemInfo.totalMem - systemMemInfo.availMem) / (1024 * 1024)).toInt()
                val systemMemoryPercent = if (systemMemInfo.totalMem > 0L) {
                    ((systemMemInfo.totalMem - systemMemInfo.availMem).toDouble() / systemMemInfo.totalMem.toDouble()) * 100.0
                } else {
                    0.0
                }
                val uptimeMinutes = (SystemClock.elapsedRealtime() / 60_000L).toInt()
                val threadCount = Thread.getAllStackTraces().size
                val cutoff = nowWallMs - 60_000L

                history.addLast(
                    DiagnosticsHistorySample(
                        timestampMs = nowWallMs,
                        appCpuPercent = cpuPercent,
                        systemCpuPercent = systemCpuPercent,
                        appMemoryMb = pssMb.toDouble(),
                        systemMemoryPercent = systemMemoryPercent
                    )
                )
                while (history.isNotEmpty() && history.first().timestampMs < cutoff) {
                    history.removeFirst()
                }

                _snapshot.value = _snapshot.value.copy(
                    processCpuPercent = cpuPercent,
                    systemCpuPercent = systemCpuPercent,
                    rssMb = rssMb,
                    pssMb = pssMb,
                    javaHeapMb = javaHeapMb,
                    nativeHeapMb = nativeHeapMb,
                    systemMemoryUsedPercent = systemMemoryPercent,
                    systemMemoryUsedMb = systemUsedMb,
                    systemMemoryTotalMb = systemTotalMb,
                    threadCount = threadCount,
                    uptimeMinutes = uptimeMinutes,
                    history = history.toList()
                )

                delay(2000)
            }
        }
    }

    @Synchronized
    private fun stopSampling() {
        samplingJob?.cancel()
        samplingJob = null
    }
}

internal class FrameTimeTracker : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private val frameDurationsMs = ArrayDeque<Double>()
    private var started = false
    private var lastFrameNanos = 0L

    fun start() {
        if (started) return
        started = true
        lastFrameNanos = 0L
        choreographer.postFrameCallback(this)
    }

    fun stop() {
        if (!started) return
        started = false
        choreographer.removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!started) return
        if (lastFrameNanos != 0L) {
            val durationMs = (frameTimeNanos - lastFrameNanos) / 1_000_000.0
            if (durationMs.isFinite() && durationMs in 0.0..5000.0) {
                frameDurationsMs.addLast(durationMs)
                while (frameDurationsMs.size > 180) frameDurationsMs.removeFirst()
            }
        }
        lastFrameNanos = frameTimeNanos
        choreographer.postFrameCallback(this)
    }

    fun snapshot(): Triple<Double?, Double?, Double?> {
        if (frameDurationsMs.isEmpty()) return Triple(null, null, null)
        val sorted = frameDurationsMs.toList().sorted()
        fun percentile(p: Double): Double {
            val index = ((sorted.lastIndex) * p).toInt().coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }
        val p50 = percentile(0.50)
        val p95 = percentile(0.95)
        val janky = frameDurationsMs.count { it > 16.7 }
        val jankPercent = (janky.toDouble() / frameDurationsMs.size.toDouble()) * 100.0
        return Triple(p50, p95, jankPercent)
    }
}

private fun readCpuStat(): CpuStat? = runCatching {
    val parts = File("/proc/stat")
        .readLines()
        .firstOrNull { it.startsWith("cpu ") }
        ?.trim()
        ?.split(Regex("\\s+"))
        ?: return@runCatching null
    val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
    if (values.size < 5) return@runCatching null
    val idle = values.getOrElse(3) { 0L } + values.getOrElse(4) { 0L }
    CpuStat(total = values.sum(), idle = idle)
}.getOrNull()

// Android 10+ restricts /proc/stat reads. Fall back to /proc/loadavg (always
// readable on Linux) and approximate system load as load1 / cores × 100. Less precise
// than the stat-based delta but recognisable and updates over time.
private fun readLoadAvgCpuPercent(): Double? = runCatching {
    val line = File("/proc/loadavg").readText().trim()
    if (line.isEmpty()) return@runCatching null
    val load1 = line.split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull() ?: return@runCatching null
    if (!load1.isFinite() || load1 < 0.0) return@runCatching null
    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    ((load1 / cores) * 100.0).coerceIn(0.0, 100.0)
}.getOrNull()

private fun calculateSystemCpuPercent(previous: CpuStat?, current: CpuStat?): Double? {
    if (previous == null || current == null) return readLoadAvgCpuPercent()
    val totalDelta = current.total - previous.total
    val idleDelta = current.idle - previous.idle
    if (totalDelta <= 0L) return readLoadAvgCpuPercent()
    val result = ((totalDelta - idleDelta).toDouble() / totalDelta.toDouble() * 100.0).coerceIn(0.0, 100.0)
    // If the stat-based calculation produces an unrealistic value, fall back to loadavg
    return if (result.isFinite() && result >= 0.0) result else readLoadAvgCpuPercent()
}

private val diagnosticsTimeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
internal fun AppDiagnosticsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adbCommand by rememberSaveable { mutableStateOf("dumpsys package ${context.packageName}") }
    var adbOutput by rememberSaveable { mutableStateOf("") }
    var adbRunning by remember { mutableStateOf(false) }
    // Diagnostics-log export state (save / upload-for-QR routes; Telegram reports via toast).
    var diagQrUrl by remember { mutableStateOf<String?>(null) }
    var diagUploading by remember { mutableStateOf(false) }
    var diagStatus by remember { mutableStateOf<String?>(null) }
    val diagnostics by AppDiagnosticsMonitor.snapshot.collectAsState()
    val diagnosticsEnabled by AppDiagnosticsMonitor.enabled.collectAsState()

    LaunchedEffect(context) {
        AppDiagnosticsMonitor.initialize(context)
    }

    SectionHeader(icon = Icons.Filled.Memory, title = stringResource(R.string.app_diagnostics_label))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
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
                        stringResource(R.string.show_diagnostic_details_label),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (diagnosticsEnabled) {
                            stringResource(R.string.diagnostic_enabled_desc)
                        } else {
                            stringResource(R.string.diagnostic_disabled_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = diagnosticsEnabled,
                    onCheckedChange = { AppDiagnosticsMonitor.setEnabled(context, it) },
                    thumbContent = if (!diagnosticsEnabled) {
                        {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(ToggleUncheckedTrack, CircleShape)
                            )
                        }
                    } else null,
                    colors = SwitchDefaults.colors(
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = ToggleUncheckedTrack,
                        uncheckedBorderColor = ToggleUncheckedTrack
                    )
                )
            }

            // ── Share diagnostics log ──────────────────────────────────────────
            // Always available (even with live diagnostics off) so the persistent
            // diag.log — which records speed-stall and "telemetry refresh wedged"
            // events — can be sent from the head unit after parking, no PC needed.
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
            OutlinedButton(
                onClick = {
                    val telegram = TelegramManager.getInstance(context)
                    val f = java.io.File(context.getExternalFilesDir(null), "diag.log")
                    when {
                        telegram.config.value == null ->
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.no_telegram_configured),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        !f.exists() || f.length() == 0L ->
                            android.widget.Toast.makeText(
                                context,
                                "diag.log is empty (no diagnostic events recorded yet).",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        else -> scope.launch {
                            try {
                                telegram.sendFile(f, caption = "BYD Trip Stats — diagnostics log")
                                // Ship the supervisor-log snapshot alongside it when one exists. It is
                                // taken just before the app re-dispatches the background restarter
                                // (which truncates the original), so it is the only post-hoc record of
                                // whether that restarter was alive through a failed auto-start.
                                // Best-effort: a failure here must not lose the diagnostics send above.
                                val supd = java.io.File(
                                    context.getExternalFilesDir(null),
                                    com.byd.tripstats.util.RtDispatch.SNAPSHOT_FILE,
                                )
                                val supdSent = supd.exists() && supd.length() > 0L && runCatching {
                                    telegram.sendFile(
                                        supd,
                                        caption = "BYD Trip Stats — supervisor log snapshot",
                                    )
                                }.isSuccess
                                android.widget.Toast.makeText(
                                    context,
                                    if (supdSent) "Diagnostics + supervisor log sent via Telegram ✓"
                                    else "Diagnostics log sent via Telegram ✓",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(
                                    context, context.getString(R.string.telegram_send_failed, e.message),
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                }
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.send_diag_log_action))
            }

            // Two network-independent routes alongside Telegram, mirroring the compatibility probe.
            // Telegram alone strands a car with no working DNS — observed on a DiLink 5 head unit
            // (2026-08-19, "Unable to resolve api.telegram.org") at exactly the moment the log was
            // needed. Saving to Downloads needs no network at all (pull it off with a USB stick);
            // the QR route needs only the upload, not a configured bot.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val msg = try {
                                val dir = com.byd.tripstats.util.DiagLogExport.saveToDownloads(context)
                                context.getString(R.string.saved_download_msg) + " (${dir.name})"
                            } catch (e: Exception) {
                                context.getString(R.string.compat_save_failed, e.message ?: "")
                            }
                            launch(Dispatchers.Main) { diagStatus = msg }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.save_local_action))
                }

                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            launch(Dispatchers.Main) { diagUploading = true; diagStatus = null }
                            try {
                                val url = com.byd.tripstats.util.DiagLogExport.uploadTail(context)
                                launch(Dispatchers.Main) { diagUploading = false; diagQrUrl = url }
                            } catch (e: Exception) {
                                val msg = context.getString(R.string.compat_upload_failed, e.message ?: "")
                                launch(Dispatchers.Main) { diagUploading = false; diagStatus = msg }
                            }
                        }
                    },
                    enabled = !diagUploading,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.QrCode2, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (diagUploading) stringResource(R.string.compat_uploading_label)
                        else stringResource(R.string.compat_email_qr_label)
                    )
                }
            }

            diagStatus?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            diagQrUrl?.let { url ->
                ProbeEmailQrDialog(downloadUrl = url, onDismiss = { diagQrUrl = null })
            }
            Text(
                stringResource(R.string.send_diag_log_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!diagnosticsEnabled) {
                Text(
                    stringResource(R.string.enable_troubleshooting_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {

                // ── Background readiness ────────────────────────────────────────
                SettingsGroupLabel(stringResource(R.string.background_readiness_label))
                val hasWriteSecureSettings = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_SECURE_SETTINGS
                ) == PackageManager.PERMISSION_GRANTED
                val hasBackgroundLocation = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val hasReadLogs = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_LOGS
                ) == PackageManager.PERMISSION_GRANTED
                SettingsDetailRow(
                    stringResource(R.string.write_settings_perm_label),
                    if (hasWriteSecureSettings) stringResource(R.string.perm_granted) else stringResource(R.string.perm_not_granted_install)
                )
                SettingsDetailRow(
                    stringResource(R.string.bg_location_perm_label),
                    if (hasBackgroundLocation) stringResource(R.string.perm_granted) else stringResource(R.string.perm_not_granted_install)
                )
                SettingsDetailRow(
                    stringResource(R.string.read_logs_perm_label),
                    if (hasReadLogs) stringResource(R.string.perm_granted) else stringResource(R.string.perm_not_granted)
                )
                SettingsDetailRow(stringResource(R.string.startup_safeguards_label), stringResource(R.string.safeguards_auto_label))
                if (!hasWriteSecureSettings || !hasBackgroundLocation) {
                    androidx.compose.material3.Card(
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.perm_warning_msg),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                SettingsGroupLabel("Process")
                SettingsDetailRow("CPU", "%.1f%%".format(diagnostics.processCpuPercent))
                // System CPU hidden when unavailable (BYD DiLink restricts /proc/stat and /proc/loadavg)
                diagnostics.systemCpuPercent?.let {
                    SettingsDetailRow("System CPU", "%.1f%%".format(it))
                }
                SettingsDetailRow("PSS", "${diagnostics.pssMb} MB")
                SettingsDetailRow("Private dirty", "${diagnostics.rssMb} MB")
                SettingsDetailRow("Java heap", "${diagnostics.javaHeapMb} MB")
                SettingsDetailRow("Native heap", "${diagnostics.nativeHeapMb} MB")
                SettingsDetailRow(
                    "System memory",
                    "%.1f%% (%d / %d MB)".format(
                        diagnostics.systemMemoryUsedPercent,
                        diagnostics.systemMemoryUsedMb,
                        diagnostics.systemMemoryTotalMb
                    )
                )
                SettingsDetailRow(stringResource(R.string.threads_label), diagnostics.threadCount.toString())
                SettingsDetailRow("App uptime", "${diagnostics.uptimeMinutes} min")

                SettingsGroupLabel(stringResource(R.string.history_60s_label))
            DiagnosticsHistoryChart(
                title = stringResource(R.string.cpu_chart_title_label),
                leftLabel = "App",
                rightLabel = "System",
                leftColor = BydElectricAzure,
                rightColor = AccelerationOrange,
                timestampsMs = diagnostics.history.map { it.timestampMs },
                values = diagnostics.history.map { it.appCpuPercent },
                secondaryValues = diagnostics.history.map { it.systemCpuPercent ?: 0.0 },
                leftSuffix = "%",
                rightSuffix = "%"
            )
                DiagnosticsHistoryChart(
                    title = stringResource(R.string.memory_chart_title_label),
                leftLabel = "App PSS",
                rightLabel = "System",
                leftColor = BatteryBlue,
                rightColor = RegenGreen,
                timestampsMs = diagnostics.history.map { it.timestampMs },
                values = diagnostics.history.map { it.appMemoryMb },
                secondaryValues = diagnostics.history.map { it.systemMemoryPercent },
                leftSuffix = " MB",
                rightSuffix = "%",
                normalizeSeparately = true
                )

                SettingsGroupLabel(stringResource(R.string.adb_shell_label))
                Text(
                    stringResource(R.string.adb_shell_desc_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = adbCommand,
                    onValueChange = { adbCommand = it },
                    label = { Text(stringResource(R.string.shell_command_label)) },
                    singleLine = false,
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        adbRunning = true
                        adbOutput = "Running..."
                        scope.launch {
                            val result = AdbPermissionManager.runShellCommand(context, adbCommand)
                            adbOutput = "exit=${result.exitCode}\n${result.output.ifBlank { "(no output)" }}"
                            adbRunning = false
                        }
                    },
                    enabled = !adbRunning && adbCommand.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = BydElectricAzure)
                ) {
                    Icon(Icons.Filled.Terminal, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (adbRunning) "Running..." else stringResource(R.string.run_shell_cmd_action))
                }
                if (adbOutput.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    ) {
                        Text(
                            adbOutput.take(4000),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.samples_refresh_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsHistoryChart(
    title: String,
    leftLabel: String,
    rightLabel: String,
    leftColor: Color,
    rightColor: Color,
    timestampsMs: List<Long>,
    values: List<Double>,
    secondaryValues: List<Double>,
    leftSuffix: String,
    rightSuffix: String,
    normalizeSeparately: Boolean = false
) {
    val primaryLatest = values.lastOrNull()
    val secondaryLatest = secondaryValues.lastOrNull()
    val primaryMax = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val secondaryMax = (secondaryValues.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val sharedMax = primaryMax.coerceAtLeast(secondaryMax)
    val axisLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    var touchPos by remember { mutableStateOf<Offset?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.last_60s_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DiagnosticsLegendItem(
                    color = leftColor,
                    label = "$leftLabel ${primaryLatest?.let { "%.1f".format(it) + leftSuffix } ?: "n/a"}"
                )
                DiagnosticsLegendItem(
                    color = rightColor,
                    label = "$rightLabel ${secondaryLatest?.let { "%.1f".format(it) + rightSuffix } ?: "n/a"}"
                )
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .pointerInput(timestampsMs, values, secondaryValues) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            touchPos = down.position
                            drag(down.id) { change ->
                                touchPos = change.position
                            }
                            touchPos = null
                        }
                    }
            ) {
                val padL = 48f
                val padR = 48f
                val padY = 8f
                val chartW = size.width - padL - padR
                val chartH = size.height - padY * 2
                val gridColor = Color.White.copy(alpha = 0.16f)
                val nc = drawContext.canvas.nativeCanvas
                val labelPaint = android.graphics.Paint().apply {
                    color = axisLabelColor.toArgb()
                    textSize = 19f
                    isAntiAlias = true
                }
                repeat(4) { index ->
                    val y = padY + chartH * index / 3f
                    drawLine(gridColor, Offset(padL, y), Offset(size.width - padR, y), 1f)
                }
                repeat(4) { index ->
                    val ratio = 1.0 - index / 3.0
                    val y = padY + chartH * index / 3f
                    val leftValue = (if (normalizeSeparately) primaryMax else sharedMax) * ratio
                    val rightValue = (if (normalizeSeparately) secondaryMax else sharedMax) * ratio
                    labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
                    nc.drawText(
                        "%.0f".format(leftValue),
                        padL - 8f,
                        y + 7f,
                        labelPaint
                    )
                    labelPaint.textAlign = android.graphics.Paint.Align.LEFT
                    nc.drawText(
                        "%.0f".format(rightValue),
                        size.width - padR + 8f,
                        y + 7f,
                        labelPaint
                    )
                }

                fun drawSeries(series: List<Double>, color: Color, strokeWidth: Float, maxForSeries: Double) {
                    if (series.size < 2) return
                    val lastIndex = (series.size - 1).coerceAtLeast(1)
                    series.zipWithNext().forEachIndexed { index, (a, b) ->
                        val x1 = padL + chartW * index / lastIndex.toFloat()
                        val x2 = padL + chartW * (index + 1) / lastIndex.toFloat()
                        val y1 = padY + chartH - ((a / maxForSeries).coerceIn(0.0, 1.0).toFloat() * chartH)
                        val y2 = padY + chartH - ((b / maxForSeries).coerceIn(0.0, 1.0).toFloat() * chartH)
                        drawLine(
                            color = color,
                            start = Offset(x1, y1),
                            end = Offset(x2, y2),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                    }
                }

                drawRect(
                    color = Color.White.copy(alpha = 0.08f),
                    topLeft = Offset(padL, padY),
                    size = androidx.compose.ui.geometry.Size(chartW, chartH),
                    style = Stroke(width = 1f)
                )
                drawSeries(
                    secondaryValues,
                    rightColor.copy(alpha = 0.85f),
                    3f,
                    if (normalizeSeparately) secondaryMax else sharedMax
                )
                drawSeries(
                    values,
                    leftColor,
                    4f,
                    if (normalizeSeparately) primaryMax else sharedMax
                )

                touchPos?.let { tp ->
                    if (tp.x in padL..(size.width - padR) && values.isNotEmpty()) {
                        val idx = if (values.size == 1) {
                            0
                        } else {
                            ((tp.x - padL) / chartW * (values.size - 1)).roundToInt().coerceIn(0, values.lastIndex)
                        }
                        val x = if (values.size == 1) padL + chartW / 2f else padL + chartW * idx / values.lastIndex.toFloat()
                        val y = padY + chartH - (
                            (values[idx] / if (normalizeSeparately) primaryMax else sharedMax)
                                .coerceIn(0.0, 1.0)
                                .toFloat() * chartH
                        )
                        val timeLabel = timestampsMs.getOrNull(idx)?.let { diagnosticsTimeFmt.format(Date(it)) } ?: "n/a"
                        drawDiagnosticsCrosshair(
                            cx = x,
                            cy = y,
                            w = size.width,
                            padL = padL,
                            padR = padR,
                            padT = padY,
                            chartH = chartH,
                            primaryLine = "$leftLabel ${"%.1f".format(values[idx])}$leftSuffix",
                            secondaryLine = "$rightLabel ${"%.1f".format(secondaryValues.getOrElse(idx) { 0.0 })}$rightSuffix",
                            timeLine = timeLabel,
                            accentColor = leftColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsLegendItem(
    color: Color,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Canvas(modifier = Modifier.size(width = 22.dp, height = 4.dp)) {
            drawLine(
                color = color,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = size.height,
                cap = StrokeCap.Round
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun DrawScope.drawDiagnosticsCrosshair(
    cx: Float,
    cy: Float,
    w: Float,
    padL: Float,
    padR: Float,
    padT: Float,
    chartH: Float,
    primaryLine: String,
    secondaryLine: String,
    timeLine: String,
    accentColor: Color
) {
    val nc = drawContext.canvas.nativeCanvas
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f))

    drawLine(
        color = accentColor.copy(alpha = 0.7f),
        start = Offset(cx, padT),
        end = Offset(cx, padT + chartH),
        strokeWidth = 1.5f,
        pathEffect = dashEffect
    )
    drawLine(
        color = accentColor.copy(alpha = 0.5f),
        start = Offset(padL, cy),
        end = Offset(w - padR, cy),
        strokeWidth = 1.2f,
        pathEffect = dashEffect
    )

    drawCircle(accentColor.copy(alpha = 0.25f), 10f, Offset(cx, cy))
    drawCircle(accentColor, 5f, Offset(cx, cy))
    drawCircle(Color.White, 2.5f, Offset(cx, cy))

    val titlePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = Color.White.toArgb()
        textSize = 17f
        isFakeBoldText = true
    }
    val bodyPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = Color.White.copy(alpha = 0.92f).toArgb()
        textSize = 15f
    }
    val metaPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = Color.White.copy(alpha = 0.78f).toArgb()
        textSize = 13f
    }

    val paddingH = 10f
    val paddingV = 8f
    val lineGap = 4f
    val titleH = titlePaint.descent() - titlePaint.ascent()
    val bodyH = bodyPaint.descent() - bodyPaint.ascent()
    val metaH = metaPaint.descent() - metaPaint.ascent()
    val boxW = maxOf(
        titlePaint.measureText(primaryLine),
        bodyPaint.measureText(secondaryLine),
        metaPaint.measureText(timeLine)
    ) + paddingH * 2
    val boxH = titleH + bodyH + metaH + lineGap * 2 + paddingV * 2

    val desiredX = if (cx + 12f + boxW < w - padR) cx + 12f else cx - 12f - boxW
    val tooltipX = desiredX.coerceIn(padL, (w - padR - boxW).coerceAtLeast(padL))
    val tooltipY = (padT + 6f).coerceAtLeast(0f)

    val bgPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = accentColor.copy(alpha = 0.94f).toArgb()
        style = android.graphics.Paint.Style.FILL
    }
    nc.drawRoundRect(
        tooltipX,
        tooltipY,
        tooltipX + boxW,
        tooltipY + boxH,
        10f,
        10f,
        bgPaint
    )

    var textY = tooltipY + paddingV - titlePaint.ascent()
    nc.drawText(primaryLine, tooltipX + paddingH, textY, titlePaint)
    textY += titleH + lineGap
    nc.drawText(secondaryLine, tooltipX + paddingH, textY, bodyPaint)
    textY += bodyH + lineGap
    nc.drawText(timeLine, tooltipX + paddingH, textY, metaPaint)
}
