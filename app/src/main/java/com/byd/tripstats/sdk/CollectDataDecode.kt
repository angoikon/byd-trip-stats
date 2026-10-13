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
