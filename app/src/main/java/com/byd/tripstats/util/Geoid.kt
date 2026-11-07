package com.byd.tripstats.util

import android.util.Log
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import java.nio.ByteBuffer
import kotlin.math.round

/**
 * Turns the altitude the car's GPS reports into height above sea level.
 *
 * GPS altitude is height above the WGS84 ellipsoid, a smooth mathematical surface. Sea level (the
 * geoid) sits above or below it depending on where you are: about +40 to +50 m across Italy, −92 m at
 * Chennai. The difference is the geoid undulation, which this looks up in the EGM96 model.
 *
 * The database, the live telemetry and the replay files keep the reading exactly as the car gave
 * it, and only what people read goes through here: that way a trip recorded by any version reads
 * right, and nothing can be corrected twice. Calculations on height *differences* (the gravity
 * terms, the heatmaps) stay on the raw reading, since the geoid changes by only centimetres per km.
 *
 * Android's own converter (`AltitudeConverter`) needs API 34 and these head units start at 29.
 */
object Geoid {
    private const val TAG = "Geoid"
    private const val RESOURCE = "/geoid/egm96_30min.bin"
    private const val CELL_DEGREES = 0.5
    private const val ROWS = 361 // 90°N to 90°S
    private const val COLUMNS = 720 // eastward from 0°E

    /** The grid in centimetres, or null if it couldn't be read: altitudes then stay as they are. */
    private val grid: ShortArray? by lazy {
        runCatching {
            val bytes = checkNotNull(Geoid::class.java.getResourceAsStream(RESOURCE)) { "missing" }
                .use { it.readBytes() }
            check(bytes.size == ROWS * COLUMNS * Short.SIZE_BYTES) { "${bytes.size} bytes" }
            ShortArray(ROWS * COLUMNS).also { ByteBuffer.wrap(bytes).asShortBuffer().get(it) }
        }.onFailure { Log.w(TAG, "Geoid grid unavailable: $it") }.getOrNull()
    }

    /** Height of sea level above the WGS84 ellipsoid here, in metres; null for an invalid position. */
    fun undulationM(latitude: Double, longitude: Double): Double? {
        if (!longitude.isFinite() || latitude.isNaN() || latitude !in -90.0..90.0) return null
        val cells = grid ?: return null

        val rowPosition = (90.0 - latitude) / CELL_DEGREES
        val top = rowPosition.toInt().coerceAtMost(ROWS - 2)
        val rowFraction = rowPosition - top

        val columnPosition = (((longitude % 360.0) + 360.0) % 360.0) / CELL_DEGREES
        val left = columnPosition.toInt().coerceAtMost(COLUMNS - 1)
        val columnFraction = columnPosition - left
        val right = (left + 1) % COLUMNS // the last column's neighbour is the first, across 0°E

        fun cell(row: Int, column: Int) = cells[row * COLUMNS + column].toDouble()
        val northCm = cell(top, left) + (cell(top, right) - cell(top, left)) * columnFraction
        val southCm = cell(top + 1, left) + (cell(top + 1, right) - cell(top + 1, left)) * columnFraction
        return (northCm + (southCm - northCm) * rowFraction) / 100.0
    }

    /**
     * [altitudeM] from the GPS as height above sea level, to the centimetre. A reading of exactly 0
     * means "no altitude" throughout the app, as does a position of exactly 0, 0, so both come back
     * unchanged; a result can be negative, because a car can be below sea level.
     */
    fun aboveSeaLevel(altitudeM: Double, latitude: Double, longitude: Double): Double {
        if (altitudeM == 0.0 || !altitudeM.isFinite()) return altitudeM
        if (latitude == 0.0 && longitude == 0.0) return altitudeM
        val undulationM = undulationM(latitude, longitude) ?: return altitudeM
        return round((altitudeM - undulationM) * 100.0) / 100.0
    }
}

/** This point's GPS altitude as height above sea level. See [Geoid]. */
val TripDataPointEntity.altitudeAboveSeaLevel: Double
    get() = Geoid.aboveSeaLevel(altitude, latitude, longitude)
