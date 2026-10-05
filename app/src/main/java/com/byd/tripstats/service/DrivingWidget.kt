package com.byd.tripstats.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.byd.tripstats.R
import com.byd.tripstats.adb.AdbPermissionManager
import com.byd.tripstats.data.config.CarConfig
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.UnitSystem
import com.byd.tripstats.data.preferences.convertDistance
import com.byd.tripstats.data.preferences.convertSpeed
import com.byd.tripstats.data.preferences.distanceUnit
import com.byd.tripstats.data.preferences.speedUnit
import com.byd.tripstats.sdk.AaosPlatform
import com.byd.tripstats.sdk.VehicleTelemetrySnapshot
import com.byd.tripstats.util.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The tiles the driving widget can show — one small floating card each. The user picks which ones
 * (and so how many), and places each one where they want it.
 *
 * TODO(dilink100): trip tiles (distance, time, consumption) once the dashboard's live trip figures
 *  have a process-scoped source — see MD/DILINK100_FOLLOWUPS.md.
 */
enum class DrivingWidgetTile(val id: String, @StringRes val labelRes: Int) {
    SPEED("speed", R.string.driving_widget_tile_speed),
    POWER("power", R.string.driving_widget_tile_power),
    BATTERY("battery", R.string.driving_widget_tile_battery),
    RANGE("range", R.string.driving_widget_tile_range),
    OUTSIDE_TEMP("outside_temp", R.string.driving_widget_tile_outside_temp);

    companion object {
        fun fromId(id: String): DrivingWidgetTile? = entries.firstOrNull { it.id == id }
        val DEFAULT: List<DrivingWidgetTile> = listOf(SPEED, POWER, BATTERY, RANGE)
    }
}

/**
 * The driving widget's settings: off until the user agrees, asked once; which tiles; where each one
 * sits. Its own preferences file, like the DiLink-5 vehicle-access consent.
 */
object DrivingWidgetPrefs {
    private const val PREFS = "driving_widget"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROMPTED = "prompted"
    private const val KEY_TILES = "tiles"
    private const val KEY_POS_X = "pos_x_"
    private const val KEY_POS_Y = "pos_y_"
    private const val ARRANGE_MS = 3 * 60_000L

    /** Only where Android hides the app's screen out of P: Android Automotive head units (DiLink 100). */
    fun isAvailable(context: Context): Boolean = AaosPlatform.isCarPropertyCapable(context)

    fun isEnabled(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_ENABLED, false) }.getOrDefault(false)

    fun wasPrompted(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_PROMPTED, false) }.getOrDefault(false)

    /** Records the choice; either answer counts as having been asked. */
    fun setEnabled(context: Context, enabled: Boolean) {
        runCatching {
            prefs(context).edit().putBoolean(KEY_ENABLED, enabled).putBoolean(KEY_PROMPTED, true).apply()
        }
    }

    /** The chosen tiles, in catalogue order; the four the widget started with until the user chooses. */
    fun selectedTiles(context: Context): List<DrivingWidgetTile> {
        val stored = runCatching { prefs(context).getString(KEY_TILES, null) }.getOrNull()
            ?: return DrivingWidgetTile.DEFAULT
        val ids = stored.split(',').filter { it.isNotBlank() }.toSet()
        return DrivingWidgetTile.entries.filter { it.id in ids }
    }

    fun setTileSelected(context: Context, tile: DrivingWidgetTile, selected: Boolean) {
        val current = selectedTiles(context).toMutableSet()
        if (selected) current += tile else current -= tile
        runCatching {
            prefs(context).edit()
                .putString(KEY_TILES, DrivingWidgetTile.entries.filter { it in current }.joinToString(",") { it.id })
                .apply()
        }
    }

    /** Saved top-left corner of a tile, in pixels; null until the user has moved it. */
    fun position(context: Context, tile: DrivingWidgetTile): Pair<Int, Int>? = runCatching {
        val p = prefs(context)
        if (!p.contains(KEY_POS_X + tile.id)) null
        else p.getInt(KEY_POS_X + tile.id, 0) to p.getInt(KEY_POS_Y + tile.id, 0)
    }.getOrNull()

    fun savePosition(context: Context, tile: DrivingWidgetTile, x: Int, y: Int) {
        runCatching { prefs(context).edit().putInt(KEY_POS_X + tile.id, x).putInt(KEY_POS_Y + tile.id, y).apply() }
    }

    fun resetPositions(context: Context) {
        runCatching {
            val editor = prefs(context).edit()
            DrivingWidgetTile.entries.forEach { editor.remove(KEY_POS_X + it.id).remove(KEY_POS_Y + it.id) }
            editor.apply()
        }
        positionsResetAt = SystemClock.elapsedRealtime()
    }

    /** Bumped by [resetPositions] so tiles already on screen move back to the default layout. */
    @Volatile var positionsResetAt: Long = 0L
        private set

    /**
     * Arrange mode: Settings shows the tiles while parked, movable, so they can be placed before
     * driving. In memory only, and it ends by itself after a few minutes.
     */
    @Volatile private var arrangeUntilElapsedMs = 0L
    fun startArranging() { arrangeUntilElapsedMs = SystemClock.elapsedRealtime() + ARRANGE_MS }
    fun stopArranging() { arrangeUntilElapsedMs = 0L }
    fun isArranging(): Boolean = SystemClock.elapsedRealtime() < arrangeUntilElapsedMs

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Small cards over other apps — speed, power, battery, range, outside temperature, whichever the
 * user picked — shown while the car is in D or N on Android Automotive head units (DiLink 100 /
 * Atto 3 EVO). Never in R, where the reversing camera fills the screen.
 *
 * Why: Android Automotive covers the screen of any app that isn't certified distraction-optimised
 * with "use this app only in P" whenever the car leaves P, and only BYD's system allowlist or a
 * Google-Play-installed app can be exempted. An overlay window isn't an app screen, so it isn't
 * covered — which is how other car apps' widgets keep showing on the same car (Overdrive's,
 * `SYSTEM_ALERT_WINDOW: allow`, 2026-10-05). Each card is its own window, so each can be placed on
 * its own, like those. Nothing on them can be tapped: a card can only be dragged, and only while the
 * car is stopped or while the user arranges them from Settings — once the car moves they let every
 * touch through to whatever is underneath.
 *
 * Off unless the user agreed to it (MainActivity's one-time prompt, or Settings). The "display
 * over other apps" permission it needs is granted over the adb channel, to the driver's user.
 * Hosted by the telemetry service, so it shows whether or not the app's own screen is open.
 * Every window operation is guarded: a refused or revoked permission hides it, never the service.
 */
class DrivingWidget(
    context: Context,
    private val scope: CoroutineScope,
    private val snapshot: StateFlow<VehicleTelemetrySnapshot>,
    private val carConfig: () -> CarConfig?,
) {
    private val appContext = context.applicationContext
    private val prefs = PreferencesManager(appContext)

    private class TileWindow(
        val tile: DrivingWidgetTile,
        val view: View,
        val value: TextView,
        val caption: TextView,
        val params: WindowManager.LayoutParams,
        var touchable: Boolean,
    )

    private var job: Job? = null
    private var windowContext: Context? = null
    private var windowManager: WindowManager? = null
    private val windows = LinkedHashMap<DrivingWidgetTile, TileWindow>()
    private var appliedResetAt = 0L

    private var lastDrivingMs = 0L
    private var shownLogged = false
    private var failureLogged = false
    private var grantAttempts = 0
    private var lastGrantMs = 0L

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.Main) {
            while (isActive) {
                runCatching { tick() }.onFailure { logFailureOnce("tick", it) }
                delay(TICK_MS)
            }
        }
    }

    /** Removes every card. Call before the service's scope is cancelled. */
    fun stop() {
        job?.cancel()
        job = null
        hideAll()
    }

    private fun tick() {
        if (!DrivingWidgetPrefs.isEnabled(appContext)) { hideAll(); return }
        if (!Settings.canDrawOverlays(appContext)) { hideAll(); maybeGrant(); return }

        val telemetry = snapshot.value.toTelemetry(carConfig())
        val arranging = DrivingWidgetPrefs.isArranging()
        val now = SystemClock.elapsedRealtime()
        if (!arranging) {
            // Never in R: the head unit shows the reversing camera full screen, and nothing of ours
            // may sit on top of it. Gone at once, no hold.
            if (telemetry.gear == "R") { lastDrivingMs = 0L; hideAll(); return }
            // D or N with the car on: Android covers the app then. A short hold before hiding, so
            // passing through P (or one stale reading) doesn't make the cards blink.
            val driving = (telemetry.gear == "D" || telemetry.gear == "N") && telemetry.isCarOn
            if (driving) lastDrivingMs = now
            val wanted = driving || (windows.isNotEmpty() && lastDrivingMs != 0L && now - lastDrivingMs < HIDE_DELAY_MS)
            if (!wanted) { hideAll(); return }
        }

        if (DrivingWidgetPrefs.positionsResetAt != appliedResetAt) {
            appliedResetAt = DrivingWidgetPrefs.positionsResetAt
            hideAll()  // re-added below at their default places
        }
        val selected = DrivingWidgetPrefs.selectedTiles(appContext)
        windows.keys.filter { it !in selected }.forEach { remove(it) }
        selected.forEachIndexed { index, tile -> if (tile !in windows) add(tile, index) }

        // Movable only while it can't distract: arranging, or the car standing still. Once the car
        // moves, the cards let every touch through.
        val touchable = arranging || telemetry.speed < STATIONARY_KMH
        val units = prefs.getCachedUnitSystem()
        for (w in windows.values) {
            if (w.touchable != touchable) setTouchable(w, touchable)
            render(w, telemetry, units)
        }
    }

    private fun render(w: TileWindow, t: VehicleTelemetry, units: UnitSystem) {
        when (w.tile) {
            DrivingWidgetTile.SPEED -> {
                w.value.text = units.convertSpeed(t.speed).roundToInt().toString()
                w.caption.text = units.speedUnit
            }
            DrivingWidgetTile.POWER -> {
                w.value.text = t.enginePower.toString()
                w.value.setTextColor(if (t.enginePower < 0) COLOR_REGEN else COLOR_POWER)
                w.caption.text = "kW"
            }
            DrivingWidgetTile.BATTERY -> {
                val soc = if (t.socPanel > 0) t.socPanel.toDouble() else t.soc
                w.value.text = if (soc > 0.0) soc.roundToInt().toString() else "–"
                w.caption.text = "%"
            }
            DrivingWidgetTile.RANGE -> {
                // The car reports range in metres (AaosCarPropertyReader keeps it in km), so it
                // converts like any other distance.
                val km = t.electricDrivingRangeKm
                w.value.text = if (km > 0) units.convertDistance(km.toDouble()).roundToInt().toString() else "–"
                w.caption.text = units.distanceUnit
            }
            DrivingWidgetTile.OUTSIDE_TEMP -> {
                w.value.text = t.instrumentOutCarTemperature?.toString() ?: "–"
                w.caption.text = "°C"
            }
        }
    }

    // ── Windows ───────────────────────────────────────────────────────────────

    private fun add(tile: DrivingWidgetTile, index: Int) {
        try {
            val ctx = windowContext ?: createWindowContext().also { windowContext = it }
            val wm = windowManager ?: ctx.getSystemService(WindowManager::class.java)?.also { windowManager = it } ?: return
            val (view, value, caption) = buildCard(ctx, tile)
            val saved = DrivingWidgetPrefs.position(appContext, tile)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // Default: a column down the left edge, below the status bar.
                x = saved?.first ?: dp(ctx, DEFAULT_LEFT_DP)
                y = saved?.second ?: dp(ctx, DEFAULT_TOP_DP + index * DEFAULT_STEP_DP)
                title = "BYD Trip Stats widget ${tile.id}"
            }
            val window = TileWindow(tile, view, value, caption, params, touchable = false)
            attachDrag(window, ctx)
            wm.addView(view, params)
            windows[tile] = window
            if (!shownLogged) {
                shownLogged = true
                DiagLog.event(appContext, TAG, "🪟 driving widget shown (${windows.keys.joinToString { it.id }}…)")
            }
        } catch (t: Throwable) {
            logFailureOnce("show", t)
        }
    }

    private fun remove(tile: DrivingWidgetTile) {
        val w = windows.remove(tile) ?: return
        runCatching { windowManager?.removeViewImmediate(w.view) }
    }

    private fun hideAll() {
        windows.keys.toList().forEach { remove(it) }
    }

    private fun setTouchable(w: TileWindow, touchable: Boolean) {
        w.params.flags = if (touchable) BASE_FLAGS else BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        w.touchable = touchable
        runCatching { windowManager?.updateViewLayout(w.view, w.params) }
    }

    /**
     * Dragging only — no tap does anything. The new place is kept per tile, and the card is held on
     * screen so it can't be dragged out of reach.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(w: TileWindow, ctx: Context) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        w.view.setOnTouchListener { view, event ->
            if (!w.touchable) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    startX = w.params.x; startY = w.params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val metrics = ctx.resources.displayMetrics
                    w.params.x = (startX + (event.rawX - downRawX).toInt())
                        .coerceIn(0, (metrics.widthPixels - view.width).coerceAtLeast(0))
                    w.params.y = (startY + (event.rawY - downRawY).toInt())
                        .coerceIn(0, (metrics.heightPixels - view.height).coerceAtLeast(0))
                    runCatching { windowManager?.updateViewLayout(view, w.params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    DrivingWidgetPrefs.savePosition(appContext, w.tile, w.params.x, w.params.y)
                    true
                }
                else -> false
            }
        }
    }

    /** A context for overlay windows on the main display, as Android 11+ expects. */
    private fun createWindowContext(): Context {
        if (Build.VERSION.SDK_INT < 30) return appContext
        val display = appContext.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return appContext
        return appContext.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }

    private fun buildCard(ctx: Context, tile: DrivingWidgetTile): Triple<View, TextView, TextView> {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumWidth = dp(ctx, 88)
            setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 16).toFloat()
                setColor(COLOR_BACKGROUND)
            }
        }
        val value = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tile == DrivingWidgetTile.SPEED) 30f else 24f)
            setTextColor(colorFor(tile))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            text = "–"
        }
        val caption = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(COLOR_LABEL)
            gravity = Gravity.CENTER
        }
        card.addView(value)
        card.addView(caption)
        return Triple(card, value, caption)
    }

    private fun colorFor(tile: DrivingWidgetTile): Int = when (tile) {
        DrivingWidgetTile.SPEED -> COLOR_SPEED
        DrivingWidgetTile.POWER -> COLOR_POWER
        DrivingWidgetTile.BATTERY -> COLOR_SOC
        DrivingWidgetTile.RANGE -> COLOR_RANGE
        DrivingWidgetTile.OUTSIDE_TEMP -> COLOR_TEMP
    }

    // ── Permission ────────────────────────────────────────────────────────────

    /**
     * The permission is missing although the user turned the widget on — a reinstall, or a car set
     * up before this existed. Asks the adb channel, a few times at most per run.
     */
    private fun maybeGrant() {
        if (grantAttempts >= MAX_GRANT_ATTEMPTS) return
        val now = SystemClock.elapsedRealtime()
        if (lastGrantMs != 0L && now - lastGrantMs < GRANT_RETRY_MS) return
        lastGrantMs = now
        grantAttempts++
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching { AdbPermissionManager.grantOverlayPermission(appContext) }
                .getOrElse { "failed: ${it.javaClass.simpleName}: ${it.message}" }
            DiagLog.event(appContext, TAG, "🪟 driving widget: display-over-apps permission $outcome (attempt $grantAttempts)")
        }
    }

    private fun logFailureOnce(where: String, t: Throwable) {
        if (failureLogged) return
        failureLogged = true
        DiagLog.event(appContext, TAG, "🪟 driving widget $where failed: ${t.javaClass.simpleName}: ${t.message}")
    }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "DrivingWidget"
        const val TICK_MS = 500L
        const val HIDE_DELAY_MS = 3_000L
        const val STATIONARY_KMH = 1.0
        const val GRANT_RETRY_MS = 10 * 60_000L
        const val MAX_GRANT_ATTEMPTS = 3

        const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        const val DEFAULT_LEFT_DP = 24
        const val DEFAULT_TOP_DP = 96
        const val DEFAULT_STEP_DP = 84

        // The dashboard's own colours: power orange (green while regenerating), SoC blue, range yellow.
        const val COLOR_BACKGROUND = 0xE0101828.toInt()
        const val COLOR_SPEED = 0xFFFFFFFF.toInt()
        const val COLOR_POWER = 0xFFFFB74D.toInt()
        const val COLOR_REGEN = 0xFF81C784.toInt()
        const val COLOR_SOC = 0xFF4FC3F7.toInt()
        const val COLOR_RANGE = 0xFFFFD54F.toInt()
        const val COLOR_TEMP = 0xFFE0E0E0.toInt()
        const val COLOR_LABEL = 0xFFB0BEC5.toInt()
    }
}
