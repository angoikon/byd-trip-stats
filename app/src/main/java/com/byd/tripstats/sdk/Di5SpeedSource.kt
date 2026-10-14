package com.byd.tripstats.sdk

import kotlin.math.abs

// DiLink-5 live speed (Dilink5Client), kept here so it can be unit tested from the shared test set.
//
// The car gives speed two ways, and they don't agree. `onSpeedChanged` events are whole km/h and
// read what the instrument panel shows — 1.046 × GPS above 50 km/h on a Shark 6 (trips 39/41,
// 2026-10-13) — and arrive only when that figure changes. The `getSpeedValue` getter, polled once
// a second, is the decimal ground speed: 0.99 × GPS. Both used to write the live speed, so the
// tile kept switching between them: a steady 70–71 on the panel showed 70–71, dropped to the
// getter's lower figure for a moment, and could sit there for seconds until the next 1 km/h event
// put the panel figure back. Overdrive's DiLink-5 code settles it the same way — the speedometer
// value first, the true speed only as a fallback (CarSvcTelemetry).

/** How long an event keeps the speed the event's own, before the getter may speak up. */
internal const val DI5_SPEED_EVENT_FRESH_MS = 3_000L

/** Below this gap the getter is just the speedometer/true-speed offset, not a change. */
private const val DI5_GETTER_MIN_GAP_KMH = 3.0

/** …or this share of the shown speed, whichever is larger (the offset is ~4.6 %). */
private const val DI5_GETTER_MIN_GAP_FRACTION = 0.08

/**
 * What the 1 s poll should push as the live speed, given the getter's [getterKmh], the speed now
 * shown ([shownKmh]) and the time since the last `onSpeedChanged` event ([sinceEventMs]; null when
 * no event has arrived at all).
 *
 * - No events on this unit: the getter is the only source, so it drives the speed.
 * - An event within [DI5_SPEED_EVENT_FRESH_MS]: the shown speed stays.
 * - Events gone quiet: the shown speed stays unless the getter clearly disagrees — more than
 *   the speedometer offset — which means the speed has really changed and no event said so
 *   (events stopped, or the car pulled up between them), or the getter reads a stop. Then the
 *   getter takes over until the next event.
 *
 * Returning the shown speed (rather than nothing) keeps the push fresh, so the main poll's
 * stale-speed handling behaves exactly as it did when the getter pushed every second.
 */
internal fun di5PolledSpeedKmh(sinceEventMs: Long?, shownKmh: Double, getterKmh: Double): Double {
    if (sinceEventMs == null) return getterKmh
    if (sinceEventMs < DI5_SPEED_EVENT_FRESH_MS) return shownKmh
    if (getterKmh < 0.5) return getterKmh // stopped: always worth showing, however small the gap
    val gap = abs(getterKmh - shownKmh)
    return if (gap > maxOf(DI5_GETTER_MIN_GAP_KMH, DI5_GETTER_MIN_GAP_FRACTION * shownKmh)) getterKmh else shownKmh
}
