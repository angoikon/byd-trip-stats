package com.byd.tripstats.data.preferences

/**
 * How much recent driving the projected range takes its consumption from (Settings → Preferences →
 * Battery and range estimate, or a tap on the dashboard's Range tile). The window runs back across
 * trips, so a drive starts from how the car was driven before instead of from scratch. Shorter
 * reacts faster to the road you're on; longer is steadier.
 */
enum class RangeWindow(val km: Double) {
    LAST_10_KM(10.0),
    LAST_100_KM(100.0),
    LAST_500_KM(500.0);

    companion object {
        val DEFAULT = LAST_100_KM
    }
}

/** "100 km", or "62 mi" for imperial — the window is defined in kilometres either way. */
fun RangeWindow.displayLabel(unitSystem: UnitSystem): String =
    "%.0f %s".format(unitSystem.convertDistance(km), unitSystem.distanceUnit)
