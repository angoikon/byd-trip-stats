package com.byd.tripstats.ui.components

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Settings → App → App Diagnostics → **Performance overlay**: a two-line strip over the app's own
 * screens while it is open, with what the drive logger (CHECK/drive_log.py) records from a laptop:
 * the app's CPU, the frame rate and what each frame costs, and the head unit's GPU and CPU clocks
 * and temperatures. A frame that costs more to render while the GPU clock drops or the chip heats
 * up is the head unit clocking down, not the app.
 *
 * Off by default. Nothing runs while it is off or the app is in the background, and it reads no
 * vehicle data. The clocks and temperatures come from sysfs, which the dev Seal's SELinux policy
 * lets every app read (checked 2026-10-30); a car whose chip or policy differs shows a dash for
 * what it can't read.
 */
object PerformanceOverlaySettings {
    private const val PREFS_NAME = "app_diagnostics"
    private const val KEY_ENABLED = "performance_overlay"

    private val _enabled = MutableStateFlow(false)
    private var initialized = false

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        _enabled.value = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
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
    }
}

private const val TAG = "PerformanceOverlay"

/** How often the strip refreshes, and how many clock readings it averages over that time. */
private const val WINDOW_MS = 2_000L
private const val CLOCK_SAMPLES = 4

/**
 * Drawn last in the activity's content, so it sits over every screen. It starts at the top
 * centre and can be dragged anywhere a car's longer name or a screen's controls aren't.
 */
@Composable
fun PerformanceOverlay(window: Window) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { PerformanceOverlaySettings.initialize(context) }
    val enabled by PerformanceOverlaySettings.enabled.collectAsState()
    if (enabled) {
        PerformanceStrip(window)
    }
}

@Composable
private fun PerformanceStrip(window: Window) {
    var lines by remember { mutableStateOf<Pair<String, String>?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(window, lifecycleOwner) {
        // Only while the app is on screen: the strip's state stays local, so each refresh
        // redraws the strip alone, not the screen under it.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val meter = FrameCostMeter()
            val thread = HandlerThread("frame-metrics").apply { start() }
            val attached = runCatching {
                window.addOnFrameMetricsAvailableListener(meter, Handler(thread.looper))
            }.isSuccess
            try {
                val sensors = withContext(Dispatchers.IO) { HeadUnitSensors.discover() }
                var cpuMs = Process.getElapsedCpuTime()
                var wallMs = SystemClock.elapsedRealtime()
                meter.take()
                while (true) {
                    val clocks = ClockAverager()
                    repeat(CLOCK_SAMPLES) {
                        delay(WINDOW_MS / CLOCK_SAMPLES)
                        withContext(Dispatchers.IO) { clocks.add(sensors.gpuMhz(), sensors.cpuMhz()) }
                    }
                    val (gpuTempC, cpuTempC) = withContext(Dispatchers.IO) { sensors.gpuTempC() to sensors.cpuTempC() }
                    val nowCpuMs = Process.getElapsedCpuTime()
                    val nowWallMs = SystemClock.elapsedRealtime()
                    val elapsedMs = (nowWallMs - wallMs).coerceAtLeast(1L)
                    val frames = meter.take()
                    lines = PerformanceSample(
                        appCpuPercent = 100.0 * (nowCpuMs - cpuMs) / elapsedMs,
                        fps = frames.count * 1000.0 / elapsedMs,
                        uiMs = frames.uiMs,
                        renderMs = frames.renderMs,
                        gpuMhz = clocks.gpuMhz(),
                        gpuTempC = gpuTempC,
                        cpuMhz = clocks.cpuMhz(),
                        cpuTempC = cpuTempC,
                    ).lines()
                    cpuMs = nowCpuMs
                    wallMs = nowWallMs
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A diagnostic strip must never take the app down with it.
                Log.w(TAG, "Performance overlay stopped: ${e.message}")
            } finally {
                if (attached) runCatching { window.removeOnFrameMetricsAvailableListener(meter) }
                thread.quitSafely()
            }
        }
    }
    // Dragged, the strip stays inside the screen. Where it sits it takes the taps; everywhere
    // else they reach the screen under it.
    var offset by remember { mutableStateOf(Offset.Zero) }
    var stripSize by remember { mutableStateOf(IntSize.Zero) }
    lines?.let { (frames, chip) ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            // Read live, so a refresh that changes the strip's width doesn't restart a drag, and a
            // window made narrower (split-screen) pulls the strip back in.
            val bounds by rememberUpdatedState(
                Offset(
                    ((constraints.maxWidth - stripSize.width) / 2f).coerceAtLeast(0f),
                    (constraints.maxHeight - stripSize.height).toFloat().coerceAtLeast(0f),
                )
            )
            fun Offset.clamped() = Offset(x.coerceIn(-bounds.x, bounds.x), y.coerceIn(0f, bounds.y))
            Column(
                modifier = Modifier
                    .offset { offset.clamped().let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
                    .onSizeChanged { stripSize = it }
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            offset = (offset.clamped() + drag).clamped()
                        }
                    }
                    .padding(top = 6.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(frames, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(chip, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** One refresh of the strip. Null or empty means the value couldn't be read. */
internal data class PerformanceSample(
    val appCpuPercent: Double,
    val fps: Double,
    val uiMs: Double?,
    val renderMs: Double?,
    val gpuMhz: Int?,
    val gpuTempC: Double?,
    val cpuMhz: List<Int>,
    val cpuTempC: Double?,
) {
    /**
     * "App 24% · 11.2 fps · UI 3.2 ms · render 9.1 ms" over "GPU 320 MHz 41°C · CPU 1401/614 MHz 43°C".
     * The app's CPU is a share of one core, as drive_log.py gives it; UI is the main thread's part
     * of a frame (input, animation, layout, draw) and render the RenderThread's (sync, issue, swap);
     * CPU clocks run from the biggest cluster down.
     */
    fun lines(): Pair<String, String> {
        val frames = buildString {
            // A decimal, because a screen at rest draws less than once a second.
            append("App ${appCpuPercent.roundToInt()}% · ${oneDecimal(fps)} fps")
            if (uiMs != null && renderMs != null) {
                append(" · UI ${oneDecimal(uiMs)} ms · render ${oneDecimal(renderMs)} ms")
            }
        }
        val chip = buildString {
            append("GPU ")
            append(gpuMhz?.let { "$it MHz" } ?: "—")
            gpuTempC?.let { append(" ${it.roundToInt()}°C") }
            append(" · CPU ")
            append(if (cpuMhz.isEmpty()) "—" else cpuMhz.joinToString("/") + " MHz")
            cpuTempC?.let { append(" ${it.roundToInt()}°C") }
        }
        return frames to chip
    }

    private fun oneDecimal(value: Double) = String.format(Locale.ROOT, "%.1f", value)
}

/**
 * Per-frame costs from the window's FrameMetrics, summed until [take] reads and clears them.
 * Called on its own thread; [take] runs on the main thread.
 */
internal class FrameCostMeter : Window.OnFrameMetricsAvailableListener {
    data class Frames(val count: Int, val uiMs: Double?, val renderMs: Double?)

    private var measured = 0
    private var dropped = 0
    private var uiNs = 0L
    private var renderNs = 0L

    override fun onFrameMetricsAvailable(window: Window, metrics: FrameMetrics, dropCountSinceLastInvocation: Int) {
        val first = metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L
        val ui = metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) +
            metrics.getMetric(FrameMetrics.ANIMATION_DURATION) +
            metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) +
            metrics.getMetric(FrameMetrics.DRAW_DURATION)
        val render = metrics.getMetric(FrameMetrics.SYNC_DURATION) +
            metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) +
            metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
        synchronized(this) {
            // Frames whose report was dropped still count toward the rate, just not the costs.
            dropped += dropCountSinceLastInvocation
            if (first) {
                dropped++
            } else {
                measured++
                uiNs += ui
                renderNs += render
            }
        }
    }

    @Synchronized
    fun take(): Frames {
        val frames = Frames(
            count = measured + dropped,
            uiMs = if (measured > 0) uiNs / 1e6 / measured else null,
            renderMs = if (measured > 0) renderNs / 1e6 / measured else null,
        )
        measured = 0
        dropped = 0
        uiNs = 0L
        renderNs = 0L
        return frames
    }
}

/** Averages the clocks read over one refresh; a clock that moves per frame reads noisily once. */
private class ClockAverager {
    private val gpu = mutableListOf<Int>()
    private val cpu = mutableListOf<List<Int>>()

    fun add(gpuMhz: Int?, cpuMhz: List<Int>) {
        gpuMhz?.let { gpu += it }
        if (cpuMhz.isNotEmpty()) cpu += cpuMhz
    }

    fun gpuMhz(): Int? = if (gpu.isEmpty()) null else gpu.average().roundToInt()

    /** Per cluster; a cluster dropped mid-refresh leaves the readings uneven, so only full ones count. */
    fun cpuMhz(): List<Int> {
        val clusters = cpu.lastOrNull()?.size ?: return emptyList()
        val full = cpu.filter { it.size == clusters }
        return (0 until clusters).map { i -> full.map { it[i] }.average().roundToInt() }
    }
}

internal data class ThermalZone(val type: String, val file: File)

/**
 * The head unit's clocks and temperatures, found once when the overlay starts. A file that
 * fails to read is dropped and not tried again until the overlay restarts, so a read the policy
 * denies isn't retried (and logged by SELinux) every refresh.
 */
internal class HeadUnitSensors(
    private var gpuClock: File?,
    cpuClocks: List<File>,
    private var gpuZone: File?,
    cpuZones: List<File>,
) {
    private val cpuClocks = cpuClocks.toMutableList()
    private val cpuZones = cpuZones.toMutableList()

    /** The GPU clock in MHz (the file gives Hz). */
    fun gpuMhz(): Int? {
        val file = gpuClock ?: return null
        val hz = readLong(file) ?: run { gpuClock = null; return null }
        return (hz / 1_000_000).toInt()
    }

    /** Each CPU cluster's clock in MHz (the files give kHz), biggest cluster first. */
    fun cpuMhz(): List<Int> {
        val mhz = mutableListOf<Int>()
        val iterator = cpuClocks.iterator()
        while (iterator.hasNext()) {
            val khz = readLong(iterator.next())
            if (khz == null) iterator.remove() else mhz += (khz / 1000).toInt()
        }
        return mhz
    }

    fun gpuTempC(): Double? {
        val file = gpuZone ?: return null
        return readTemperatureC(file) ?: run { gpuZone = null; null }
    }

    /** The hottest CPU sensor. */
    fun cpuTempC(): Double? {
        var hottest: Double? = null
        val iterator = cpuZones.iterator()
        while (iterator.hasNext()) {
            val celsius = readTemperatureC(iterator.next())
            if (celsius == null) iterator.remove() else if (hottest == null || celsius > hottest) hottest = celsius
        }
        return hottest
    }

    companion object {
        /** Adreno (Qualcomm): the dev Seal's SM6125 has the first; newer kernels the second. */
        private val GPU_CLOCKS = listOf("/sys/class/kgsl/kgsl-3d0/gpuclk", "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq")
        private val POLICY = Regex("policy(\\d+)")

        fun discover(): HeadUnitSensors {
            // A clock under 1 MHz is a stand-in, not a clock (the emulator's cpufreq files read 1–2 kHz).
            val gpuClock = GPU_CLOCKS.map(::File).firstOrNull { (readLong(it) ?: 0L) >= 1_000_000L }
            // One cpufreq policy per cluster; the highest-numbered one is the big cluster.
            val cpuClocks = File("/sys/devices/system/cpu/cpufreq").listFiles().orEmpty()
                .mapNotNull { dir -> POLICY.matchEntire(dir.name)?.let { it.groupValues[1].toInt() to dir } }
                .sortedByDescending { it.first }
                .map { File(it.second, "scaling_cur_freq") }
                .filter { (readLong(it) ?: 0L) >= 1_000L }
            val zones = File("/sys/class/thermal").listFiles().orEmpty()
                .filter { it.name.startsWith("thermal_zone") }
                .sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
                .mapNotNull { zone ->
                    runCatching { File(zone, "type").readText().trim() }.getOrNull()
                        ?.let { ThermalZone(it, File(zone, "temp")) }
                }
            return HeadUnitSensors(
                gpuClock = gpuClock,
                cpuClocks = cpuClocks,
                gpuZone = pickGpuZone(zones)?.file?.takeIf { readTemperatureC(it) != null },
                cpuZones = pickCpuZones(zones).map { it.file }.filter { readTemperatureC(it) != null },
            )
        }

        private fun readLong(file: File): Long? = runCatching { file.readText().trim().toLong() }.getOrNull()

        private fun readTemperatureC(file: File): Double? =
            runCatching { parseTemperatureC(file.readText()) }.getOrNull()
    }
}

/**
 * Qualcomm mirrors each sensor in "-step" and "-lowf" zones that drive throttling, and some of
 * those read a trip point rather than a temperature (the dev Seal's lmh-dcvs-00 reads 75 °C at
 * rest), so only sensor zones count, the "-usr" ones first.
 */
private fun ThermalZone.isSensor() = !type.endsWith("-step") && !type.endsWith("-lowf")

/** The dev Seal's is "gpu-usr"; newer Qualcomm chips name theirs "gpuss-0-usr". */
internal fun pickGpuZone(zones: List<ThermalZone>): ThermalZone? {
    val gpu = zones.filter { "gpu" in it.type && it.isSensor() }
    return gpu.firstOrNull { it.type.endsWith("-usr") } ?: gpu.firstOrNull()
}

/** The dev Seal's are "cpuss-0-usr" … and "cpu-1-0-usr" …; the overlay shows the hottest. */
internal fun pickCpuZones(zones: List<ThermalZone>): List<ThermalZone> {
    val cpu = zones.filter { it.type.startsWith("cpu") && it.isSensor() }
    return cpu.filter { it.type.endsWith("-usr") }.ifEmpty { cpu }
}

/** A zone's reading in °C: millidegrees on most kernels, whole degrees on some. */
internal fun parseTemperatureC(raw: String): Double? {
    val value = raw.trim().toLongOrNull() ?: return null
    val celsius = if (abs(value) >= 1000) value / 1000.0 else value.toDouble()
    return celsius.takeIf { it in -40.0..150.0 }
}
