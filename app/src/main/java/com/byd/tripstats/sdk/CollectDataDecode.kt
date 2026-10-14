package com.byd.tripstats.sdk

import com.byd.tripstats.data.config.CarConfig

// DiLink-5 collectdata decoding (Dilink5Client.registerCollectData), kept here so it can be unit
// tested from the shared test set — the dilink5 source set has no tests of its own.

/** The most one motor controller can report: GB/T 32960's ±1000 A (raw 0–20000 in 0.1 A steps). */
private const val MAX_MOTOR_AMPS = 1000.0

/** The range the rear-only reading has always accepted. */
private const val MAX_BUS_AMPS = 2000.0

/** One collectdata channel in real units: `(raw − offset) × scale`. */
internal fun decodeCollectChannel(raw: Int, offset: Int, scale: Double): Double = (raw - offset) * scale

/**
 * An RPM read from the engine device's getters, decoded with the same per-car settings as the
 * collectdata event ([CarConfig.motorRpmOffsetFront] etc.).
 *
 * The data source polls those getters alongside the decoded `onDriverMotorSpeed` events and takes
 * them whenever the events have been quiet for 3 s — at start-up, before the events flow, and
 * whenever the car goes quiet. On a Shark 6 they return the same encoding as the events (front
 * 15000 / rear 30000 at rest), so until now those spells showed raw RPM, fired the "speed 0 while
 * moving" diagnostic, and made the drivetrain look alive. With a car's offset and scale it is
 * decoded the same way, a stopped motor reading just below its baseline counts as 0, and a value
 * beyond 30 000 rpm is dropped. Without them — every other car — the raw value comes back exactly as
 * it was, and so do [sentinels], which the caller handles itself.
 */
internal fun decodeEngineGetterRpm(raw: Int?, offset: Int, scale: Double, sentinels: Set<Int>): Int? {
    if (raw == null || (offset == 0 && scale == 1.0) || raw in sentinels) return raw
    val rpm = kotlin.math.round(decodeCollectChannel(raw, offset, scale)).toInt()
    return when {
        rpm < 0 -> 0
        rpm > 30_000 -> null
        else -> rpm
    }
}

/**
 * HV current in amps (regen negative) from `onMotorMCUGeneratrixCurrent(front, rear)`, decoded
 * with the car's [CarConfig.hvCurrentOffset] / [CarConfig.hvCurrentScale]; null when nothing
 * usable came in.
 *
 * Without [CarConfig.hvCurrentSumChannels] it is the rear channel alone, exactly as Dilink5Client
 * has always read it. With it (Shark 6) it is front + rear, each first checked against what one
 * motor can draw, so a channel reading rubbish adds nothing rather than throwing the total off.
 */
internal fun decodeHvCurrentAmps(front: Int, rear: Int, cfg: CarConfig?): Double? {
    val offset = cfg?.hvCurrentOffset ?: 0
    val scale = cfg?.hvCurrentScale ?: 1.0
    val rearAmps = decodeCollectChannel(rear, offset, scale)
    if (cfg?.hvCurrentSumChannels != true) return rearAmps.takeIf { it in -MAX_BUS_AMPS..MAX_BUS_AMPS }
    val f = decodeCollectChannel(front, offset, scale).takeIf { it in -MAX_MOTOR_AMPS..MAX_MOTOR_AMPS }
    val r = rearAmps.takeIf { it in -MAX_MOTOR_AMPS..MAX_MOTOR_AMPS }
    if (f == null && r == null) return null
    return (f ?: 0.0) + (r ?: 0.0)
}
