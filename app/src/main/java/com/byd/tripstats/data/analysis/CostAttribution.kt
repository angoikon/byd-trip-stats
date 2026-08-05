package com.byd.tripstats.data.analysis

import com.byd.tripstats.data.local.entity.ChargingSessionEntity
import com.byd.tripstats.data.local.entity.TripEntity

/**
 * Pure cost/distance attribution shared by the dashboard flows and covered by unit tests.
 *
 * The model: cost is *incurred* at a charge (the price you paid when you plugged in) and *spent*
 * across the trips that draw down that energy. The battery is a **FIFO stack of energy lots** — the
 * oldest kWh (at the price it was bought) is spent first — so a trip fuelled by a €0.135 home charge
 * pays €0.135 until that energy runs out, then spills into a later €0.40 charge (see [blendedTripRates]).
 *
 * **SOC reconciliation.** The ledger only drains through recorded trips, but the pack also drains
 * through HVAC, idle sitting and anything unrecorded — left alone, the queue accumulates phantom
 * surplus and its front goes stale (months-old lots shadowing every newly priced charge). Each
 * charge's plug-in SOC is ground truth for how much old energy can still physically be in the
 * pack: before a charge's lot is appended, the queue is trimmed (oldest first) down to
 * `socStart × batteryKwh`. Anything evicted provably left the pack unrecorded. This bounds drift
 * to a single charge-to-charge window.
 *
 * "Distance between charges" is the odometer delta between consecutive charges, anchored on each
 * charge's plug-in odometer (with a trip-odometer fallback for legacy charges recorded before that
 * field existed).
 */
object CostAttribution {

    /**
     * Effective electricity rate (currency/kWh) for every completed trip, keyed by trip id, using
     * **FIFO cost-basis** accounting — the oldest energy in the battery is spent first, at the price
     * it was bought at, before moving on to newer (possibly dearer) energy. This is more precise
     * than a single last-charge rate or a whole-tank weighted average: a trip fuelled from a
     * €0.135 home charge that still has headroom pays €0.135, and only spills into a later €0.40
     * charge once the cheap energy runs out.
     *
     * The battery is a FIFO queue of `(kWh, price)` lots. Walking events in time order:
     *   • a **charge** first reconciles the queue against physical reality — the queue is trimmed
     *     (oldest first) to `socStart × batteryKwh`, the pack's energy content at plug-in, because
     *     anything beyond that left the pack unrecorded (HVAC, idle, unlogged drives) — and then
     *     appends a lot of its kWh at its own price ([ChargingSessionEntity.pricePerKwh]),
     *     or the global [tariff] when it has none — keyed at its `startTime`;
     *   • a **trip** draws its energy from the front of the queue (oldest first), summing
     *     `kWh × lotPrice`, and is keyed at its **endTime** so a charge that happens *during* a
     *     single long trip is already in the queue and fuels the rest of that trip.
     * Energy a trip needs beyond the recorded lots (e.g. the pack was already part-charged before
     * any recorded session) is priced at the global [tariff].
     *
     * A trip's returned rate is `total cost ÷ energy`, so `energy × rate` reproduces the cost.
     * There is deliberately no per-trip price override — trip rates derive purely from
     * charges + tariff, so the two histories can never disagree (single source of truth).
     *
     * Returns null for a trip whose cost can't be resolved at all (no tariff set and no priced
     * charge anywhere, or a tariff-priced deficit with the tariff at 0), so callers hide the cost.
     */
    fun blendedTripRates(
        trips: List<TripEntity>,
        sessions: List<ChargingSessionEntity>,
        tariff: Double
    ): Map<Long, Double?> {
        // Without any price signal at all, costs stay hidden (fresh user, no tariff configured).
        val anyPriceInfo = tariff > 0.0 || sessions.any { !it.isActive && it.pricePerKwh != null }

        // time, order (charge=0 before trip=1 on ties), kWh, chargeRate, tripId (null for a charge),
        // and — charges only — the pack's physical energy content at plug-in (the trim target).
        data class Event(
            val time: Long, val order: Int, val kwh: Double, val rate: Double,
            val tripId: Long?, val packKwhAtPlugIn: Double? = null
        )

        val events = ArrayList<Event>()
        sessions.filter { !it.isActive && (it.kwhAdded ?: 0.0) > 0.0 }.forEach { s ->
            // Plug-in SOC × pack capacity = how much old energy can still be in the pack.
            // Guarded to positive readings: a stale-0 SOC or a legacy row without the
            // capacity snapshot must not wipe the queue — reconciliation is simply skipped.
            val packKwhAtPlugIn = if (s.socStart > 0.0 && s.batteryKwh > 0.0)
                (s.socStart / 100.0).coerceAtMost(1.0) * s.batteryKwh
            else null
            events += Event(s.startTime, 0, s.kwhAdded!!, s.pricePerKwh ?: tariff, null, packKwhAtPlugIn)
        }
        trips.filter { !it.isActive && (it.energyConsumed ?: 0.0) > 0.0 }.forEach {
            // Keyed at endTime so a mid-trip charge is already queued when this trip draws down.
            events += Event(it.endTime ?: it.startTime, 1, it.energyConsumed!!, 0.0, it.id)
        }
        events.sortWith(compareBy({ it.time }, { it.order }))

        // FIFO queue of energy lots; the head is the oldest energy, spent first.
        class Lot(var kwh: Double, val rate: Double)
        val queue = ArrayDeque<Lot>()
        var queueKwh = 0.0
        val result = HashMap<Long, Double?>()
        for (e in events) {
            if (e.tripId == null) {
                // SOC reconciliation: the queue cannot hold more energy than the pack
                // physically did at plug-in. The surplus left the pack unrecorded (HVAC,
                // idle drain, unlogged drives) — evict it oldest-first, so stale lots
                // can't shadow this charge from the trips that follow it.
                e.packKwhAtPlugIn?.let { target ->
                    var excess = queueKwh - target
                    while (excess > 1e-9 && queue.isNotEmpty()) {
                        val lot = queue.first()
                        val cut = minOf(lot.kwh, excess)
                        lot.kwh -= cut
                        queueKwh -= cut
                        excess -= cut
                        if (lot.kwh <= 1e-9) queue.removeFirst()
                    }
                }
                queue.addLast(Lot(e.kwh, e.rate))
                queueKwh += e.kwh
            } else {
                var remaining = e.kwh
                var cost = 0.0
                var fromLots = 0.0
                while (remaining > 1e-9 && queue.isNotEmpty()) {
                    val lot = queue.first()
                    val take = minOf(remaining, lot.kwh)
                    cost += take * lot.rate
                    fromLots += take
                    lot.kwh -= take
                    queueKwh -= take
                    remaining -= take
                    if (lot.kwh <= 1e-9) queue.removeFirst()
                }
                // Energy beyond the recorded lots (pre-existing / under-recorded) → tariff.
                cost += remaining * tariff
                val resolvable = anyPriceInfo && (fromLots > 1e-9 || tariff > 0.0)
                result[e.tripId] = if (resolvable) cost / e.kwh else null
            }
        }
        return result
    }

    /**
     * Distance (km) driven since the previous charge, keyed by completed charging-session id.
     * Each charge is anchored on its plug-in odometer ([ChargingSessionEntity.startOdometer]);
     * legacy rows without it fall back to the [TripEntity.endOdometer] of the last trip that
     * ended before the charge. A session's value is the delta from the immediately preceding
     * charge's anchor — null when either anchor is missing or the delta is negative (odometer
     * glitch / reset). The first charge in history has no predecessor, so its value is null.
     */
    fun distancesSincePreviousCharge(
        sessions: List<ChargingSessionEntity>,
        trips: List<TripEntity>
    ): Map<Long, Double?> {
        val completed = sessions.filter { !it.isActive }.sortedBy { it.startTime }
        val tripsEndAsc = trips
            .filter { it.endOdometer != null && it.endTime != null }
            .sortedBy { it.endTime }

        fun anchorFor(session: ChargingSessionEntity): Double? =
            session.startOdometer
                ?: tripsEndAsc.lastOrNull { (it.endTime ?: 0L) <= session.startTime }?.endOdometer

        val result = LinkedHashMap<Long, Double?>()
        var prevAnchor: Double? = null
        var havePrev = false
        for (s in completed) {
            val anchor = anchorFor(s)
            result[s.id] =
                if (havePrev && prevAnchor != null && anchor != null && anchor - prevAnchor >= 0.0)
                    anchor - prevAnchor
                else null
            prevAnchor = anchor
            havePrev = true
        }
        return result
    }
}
