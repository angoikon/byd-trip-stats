package com.byd.tripstats.data.analysis

import kotlin.math.abs
import kotlin.math.exp

/**
 * Instant consumption (kWh/100 km) for the dashboard's Power tile: motor power ÷ speed, plus what
 * the battery spends beyond the motors.
 *
 * The motor power is in whole kW and leaves out climate, the 12 V supply and losses: on the dev
 * Seal's trip 821 it added up to 5.74 kWh where the battery gave 6.40. So the estimator learns the
 * difference as an extra load in kW — the battery's discharge counter minus the integrated motor
 * power, over the trip's continuous readings — and adds it. That load runs whether the car moves
 * or not, so it is learned over every reading: splitting by speed was tried and read 7 % high,
 * because the counter's 0.1 kWh steps land a moment late — energy used pulling away from a stop
 * shows up in the readings after. It adapts to the car and its climate use without a per-car
 * figure; on a car whose power is already measured at the battery it learns ~0. Replaying trip 821
 * through this class, the figure over its moving stretches came within 2 % of the battery there
 * (14.5 vs 14.8 kWh/100 km); the stored points' gaps left the standstill draw out of that replay,
 * which live readings, every 0.2 s, keep.
 *
 * Power and speed are smoothed over a few seconds, which also hides the 1 kW steps (2 kWh/100 km
 * at 50 km/h). Below [MIN_SPEED_KMH] there is no figure — it heads to infinity at a standstill.
 * Under regen it is negative, as on BYD's own instrument. Readings with a gap longer than
 * [MAX_GAP_SECONDS] between them (the dashboard was hidden) restart the smoothing but keep what
 * was learned; [resetExtraLoad] starts the learning over for a new trip.
 */
class InstantConsumptionEstimator(private val smoothingSeconds: Double = 3.0) {

    private var lastMs: Long? = null
    private var lastMotorKw = 0.0
    private var lastDischargeKwh: Double? = null
    private var powerKw: Double? = null
    private var speedKmh: Double? = null

    // Battery energy and motor energy over the same continuous readings, and their duration.
    private var batteryKwh = 0.0
    private var motorKwh = 0.0
    private var hours = 0.0

    /** What the battery spends beyond the motors (climate, 12 V, losses), in kW. */
    var extraLoadKw: Double = 0.0
        private set

    fun resetExtraLoad() {
        batteryKwh = 0.0
        motorKwh = 0.0
        hours = 0.0
        extraLoadKw = 0.0
    }

    /**
     * Feeds one reading — [motorKw] (negative under regen), [speedKmh] and the battery's net
     * discharge counter [totalDischargeKwh] — and returns the instant consumption in kWh/100 km,
     * or null below [MIN_SPEED_KMH].
     */
    fun update(timeMs: Long, motorKw: Double, speedKmh: Double, totalDischargeKwh: Double?): Double? {
        val discharge = totalDischargeKwh?.takeIf { it.isFinite() && it > 0.0 }
        val dtS = lastMs?.let { (timeMs - it) / 1000.0 }
        lastMs = timeMs
        if (dtS == null || dtS <= 0.0 || dtS > MAX_GAP_SECONDS || !motorKw.isFinite() || !speedKmh.isFinite()) {
            powerKw = motorKw.takeIf { it.isFinite() }
            this.speedKmh = speedKmh.takeIf { it.isFinite() }
        } else {
            val a = 1.0 - exp(-dtS / smoothingSeconds)
            powerKw = powerKw?.let { it + a * (motorKw - it) } ?: motorKw
            this.speedKmh = this.speedKmh?.let { it + a * (speedKmh - it) } ?: speedKmh
            // Both energies over the same readings only, so a missing counter can't skew one.
            val previousDischarge = lastDischargeKwh
            if (discharge != null && previousDischarge != null && abs(discharge - previousDischarge) < MAX_COUNTER_STEP_KWH) {
                batteryKwh += discharge - previousDischarge
                motorKwh += lastMotorKw * dtS / 3600.0
                hours += dtS / 3600.0
                if (hours >= MIN_LEARNING_HOURS) {
                    extraLoadKw = ((batteryKwh - motorKwh) / hours).coerceIn(0.0, MAX_EXTRA_LOAD_KW)
                }
            }
        }
        lastMotorKw = motorKw.takeIf { it.isFinite() } ?: 0.0
        lastDischargeKwh = discharge
        return current()
    }

    private fun current(): Double? {
        val v = speedKmh ?: return null
        val p = powerKw ?: return null
        if (v < MIN_SPEED_KMH) return null
        return ((p + extraLoadKw) / v * 100.0).coerceIn(-MAX_SHOWN_KWH_PER_100KM, MAX_SHOWN_KWH_PER_100KM)
    }

    companion object {
        const val MIN_SPEED_KMH = 10.0
        const val MAX_GAP_SECONDS = 10.0
        // Climate, 12 V and losses settle within a few minutes of driving.
        const val MIN_LEARNING_HOURS = 5.0 / 60.0
        // Heat pump or heater flat out, with margin; a larger figure is a counter glitch.
        const val MAX_EXTRA_LOAD_KW = 6.0
        // A counter step this large between two readings (≤ 10 s apart) is a glitch, not driving.
        const val MAX_COUNTER_STEP_KWH = 1.0
        const val MAX_SHOWN_KWH_PER_100KM = 999.0
    }
}
