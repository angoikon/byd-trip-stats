package com.byd.tripstats.data.preferences

enum class UnitSystem { METRIC, IMPERIAL }

private const val KM_TO_MI = 0.621371
private const val M_TO_FT = 3.28084

/** Convert a distance calculated/stored in km to the display unit. BMS-sourced range values (electricDrivingRangeKm, projectedRangeKm) already arrive in the car's native unit and must NOT be passed here. */
fun UnitSystem.convertDistance(km: Double): Double =
    if (this == UnitSystem.IMPERIAL) km * KM_TO_MI else km

/** Inverse of [convertDistance]: takes a value expressed in the user's display unit (km or miles) and returns the equivalent in km for storage. */
fun UnitSystem.toKilometers(distanceInDisplayUnit: Double): Double =
    if (this == UnitSystem.IMPERIAL) distanceInDisplayUnit / KM_TO_MI else distanceInDisplayUnit

/** Convert a speed in km/h to the display unit. GPS/SDK speed is always in km/h regardless of market. BMS-sourced odometer is excluded — use only for app-calculated or GPS speeds. */
fun UnitSystem.convertSpeed(kmh: Double): Double =
    if (this == UnitSystem.IMPERIAL) kmh * KM_TO_MI else kmh

/** Convert efficiency stored as kWh/100km to the display unit (kWh/100mi for imperial). */
fun UnitSystem.convertEfficiency(kwhPer100km: Double): Double =
    if (this == UnitSystem.IMPERIAL) kwhPer100km / KM_TO_MI else kwhPer100km

val UnitSystem.distanceUnit: String get() = if (this == UnitSystem.IMPERIAL) "mi" else "km"
val UnitSystem.speedUnit: String get() = if (this == UnitSystem.IMPERIAL) "mph" else "km/h"
val UnitSystem.consumptionUnit: String get() = if (this == UnitSystem.IMPERIAL) "kWh/100mi" else "kWh/100km"
val UnitSystem.isImperial: Boolean get() = this == UnitSystem.IMPERIAL

/** Convert a height in metres to the display unit (feet for imperial). */
fun UnitSystem.convertAltitude(m: Double): Double =
    if (this == UnitSystem.IMPERIAL) m * M_TO_FT else m

val UnitSystem.altitudeUnit: String get() = if (this == UnitSystem.IMPERIAL) "ft" else "m"
