package com.byd.tripstats.data.notify

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The wording of every vehicle event, built from primitives only.
 *
 * Kept free of Context, preferences and entities so the phrasing and the number formatting can
 * be unit-tested without a device — the same split
 * [com.byd.tripstats.service.CellImbalanceEvaluator] uses for its debounce logic.
 *
 * Each builder returns a [VehicleEvent], not a string: the Telegram push and the web companion's
 * notification feed both render it, and a card that read differently in the two places would be
 * a bug nobody would notice until a user compared them. Markup belongs to the channel, so the
 * text here is plain and [VehicleEvent.telegramHtml] escapes it on the way out.
 *
 * The text is English regardless of the app's language, matching the existing cell-imbalance
 * system notification.
 */
object TelegramEventMessages {

    /** Telegram rejects a sendMessage body longer than this. */
    const val MAX_LENGTH = 4096

    private const val KM_TO_MI = 0.621371

    /**
     * At or above this the charge is reported as complete. Not 100: the BMS settles the last
     * fraction of a percent minutes after current stops, and the panel and BMS readings differ
     * slightly, so a full charge routinely records as 99.x.
     */
    private const val FULL_SOC_PCT = 99.0

    /**
     * "🚗 Trip #756 finished" card — distance, duration, energy, consumption against the lifetime
     * average, SoC used, trip score and (when the app can price the energy) cost.
     *
     * Every input is a value the app already stored or derived for this trip; nothing is
     * recalculated with different rules here, so the card and the trip's own screen agree.
     * [efficiencyKwh100km] and [fleetAvgKwh100km] are both in kWh/100 km as stored, and
     * [energyRatePerKwh] is the FIFO cost-basis rate the app prices trips with — conversion to
     * display units and the cost multiplication happen here, once.
     */
    fun tripSummary(
        tripId: Long,
        distanceKm: Double,
        durationMs: Long?,
        energyKwh: Double?,
        efficiencyKwh100km: Double?,
        fleetAvgKwh100km: Double?,
        startSoc: Double,
        endSoc: Double?,
        avgSpeedKmh: Double?,
        tripScore: Int?,
        energyRatePerKwh: Double?,
        currencySymbol: String,
        imperial: Boolean,
        startTimeMs: Long? = null,
        endTimeMs: Long? = null,
        socFromBms: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault(),
    ): VehicleEvent {
        val distUnit = if (imperial) "mi" else "km"
        val consUnit = if (imperial) "kWh/100mi" else "kWh/100km"
        val speedUnit = if (imperial) "mph" else "km/h"
        val lines = mutableListOf<String>()

        // When the trip happened. A summary can arrive hours late — queued while the car had no
        // link, e.g. parked underground — and without this it reads exactly like a fresh one.
        if (startTimeMs != null && endTimeMs != null) lines += timeRange(startTimeMs, endTimeMs, zone)

        val distance = if (imperial) distanceKm * KM_TO_MI else distanceKm
        val firstLine = StringBuilder("${fmt(distance, 1)} $distUnit")
        durationMs?.takeIf { it > 0 }?.let { firstLine.append(" in ${duration(it)}") }
        avgSpeedKmh?.takeIf { it > 0 }?.let {
            val speed = if (imperial) it * KM_TO_MI else it
            firstLine.append(" · avg ${fmt(speed, 0)} $speedUnit")
        }
        lines += firstLine.toString()

        if (energyKwh != null && efficiencyKwh100km != null) {
            val cons = if (imperial) efficiencyKwh100km / KM_TO_MI else efficiencyKwh100km
            lines += "${fmt(energyKwh, 2)} kWh · ${fmt(cons, 1)} $consUnit"
            // The lifetime average already includes this trip (it is computed after the row is
            // written), which is why a first-ever trip compares as 0%.
            if (fleetAvgKwh100km != null && fleetAvgKwh100km > 0.0 && efficiencyKwh100km > 0.0) {
                val avg = if (imperial) fleetAvgKwh100km / KM_TO_MI else fleetAvgKwh100km
                val deltaPct = (efficiencyKwh100km - fleetAvgKwh100km) / fleetAvgKwh100km * 100.0
                val verdict = when {
                    abs(deltaPct) < 1.0 -> "on par with"
                    deltaPct < 0        -> "${fmt(abs(deltaPct), 0)}% better than"
                    else                -> "${fmt(deltaPct, 0)}% worse than"
                }
                lines += "$verdict your ${fmt(avg, 1)} average"
            }
        }

        if (endSoc != null) {
            val used = startSoc - endSoc
            val usedText = if (used > 0) " (−${socValue(used, socFromBms)}%)" else ""
            lines += "${socLabel(socFromBms)} ${socValue(startSoc, socFromBms)}% → " +
                "${socValue(endSoc, socFromBms)}%$usedText"
        }

        // The app hides cost when no rate is resolvable (no tariff and no priced charge); a null
        // rate carries that decision here rather than re-deriving it.
        if (energyKwh != null && energyKwh > 0.0 && energyRatePerKwh != null && energyRatePerKwh > 0.0) {
            lines += "Cost $currencySymbol${fmt(energyKwh * energyRatePerKwh, 2)}"
        }

        tripScore?.let { lines += "Trip score $it/100" }

        // Canonical units throughout, never the imperial ones above: the reader of this is a
        // broker subscriber that cannot see the unit preference the prose was rendered with.
        val tripCost = if (energyKwh != null && energyKwh > 0.0 && energyRatePerKwh != null && energyRatePerKwh > 0.0) {
            energyKwh * energyRatePerKwh
        } else {
            null
        }
        return VehicleEvent(
            type = VehicleEvent.Type.TRIP,
            // The number is the one Trip History prints on the trip's card (#756), so a message can
            // be matched to its row — and quoted in a bug report — without comparing times.
            title = "🚗 Trip #$tripId finished",
            lines = lines,
            key = "trip-$tripId",
            facts = facts(
                "event" to "trip",
                "trip_id" to tripId,
                "started_at" to iso(startTimeMs),
                "ended_at" to iso(endTimeMs),
                "started_at_ms" to startTimeMs,
                "ended_at_ms" to endTimeMs,
                "duration_s" to durationMs?.takeIf { it > 0 }?.div(1000),
                "distance_km" to distanceKm,
                "energy_kwh" to energyKwh,
                "efficiency_kwh_per_100km" to efficiencyKwh100km,
                "fleet_avg_kwh_per_100km" to fleetAvgKwh100km?.takeIf { it > 0.0 },
                "avg_speed_kmh" to avgSpeedKmh?.takeIf { it > 0.0 },
                "soc_start_pct" to startSoc,
                "soc_end_pct" to endSoc,
                "soc_source" to if (socFromBms) "bms" else "panel",
                "trip_score" to tripScore,
                "energy_rate_per_kwh" to energyRatePerKwh?.takeIf { it > 0.0 },
                "cost" to tripCost,
                // Only alongside a cost: a currency on its own says nothing and invites a consumer
                // to pair it with the wrong number.
                "currency" to currencySymbol.takeIf { tripCost != null },
            ),
        )
    }

    /**
     * "🔌 Charging #12 complete" / "🔌 Charging #12 stopped at 43%" card, sent when a session closes.
     *
     * The title states which of the two happened rather than asking the user to configure a
     * target: a session that ended at [FULL_SOC_PCT] or above finished, anything below it
     * stopped — a tripped breaker, a public charger that gave up, or simply an unplug. Both are
     * facts about the session, and neither claims to know the reason.
     *
     * [ratePerKwh] follows the charging screen's own rule — the session's own price when it has
     * one, otherwise the global tariff, null when neither exists — so a session priced at zero
     * reads as free here exactly as it does there.
     *
     * [origin] says how much of this was watched, and it has no default: a caller that has not
     * decided must not be able to claim the session was measured. It also decides what can
     * honestly be said about duration, so [durationMs] is ignored for a
     * [ChargeOrigin.SOC_ESTIMATE] session — that one is bounded by a shutdown and a wake-up
     * rather than measured between them, and the span is mostly parking.
     */
    fun chargingFinished(
        sessionId: Long,
        socEnd: Double,
        socStart: Double,
        kwhAdded: Double?,
        durationMs: Long?,
        avgKw: Double,
        peakKw: Double,
        ratePerKwh: Double?,
        currencySymbol: String,
        origin: ChargeOrigin,
        socFromBms: Boolean = false,
        startTimeMs: Long? = null,
        endTimeMs: Long? = null,
        closedAtMs: Long? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): VehicleEvent {
        val complete = socEnd >= FULL_SOC_PCT
        val lines = mutableListOf<String>()

        // A session nobody watched has no charging duration to report. Its timestamps are the
        // shutdown and the wake-up, so the span between them is however long the car sat there,
        // most of it not charging — an eight-hour night reads as an eight-hour charge.
        val chargedForMs = durationMs?.takeIf { origin != ChargeOrigin.SOC_ESTIMATE }

        // Whether the charge reached full is only knowable when the app saw the end of it. A
        // recovered session ends where the app was killed, which can be the middle of a charge
        // that went on to 100%, so "stopped at 61%" would be a claim about something unobserved.
        val reachedFull = complete.takeIf { origin != ChargeOrigin.RECOVERED }

        // Same reason as the trip card: a queued message must say which session it describes.
        if (startTimeMs != null && endTimeMs != null) lines += timeRange(startTimeMs, endTimeMs, zone)

        val detail = StringBuilder()
        kwhAdded?.takeIf { it > 0 }?.let { detail.append("Added ${fmt(it, 2)} kWh") }
        chargedForMs?.takeIf { it > 0 }?.let {
            detail.append(if (detail.isEmpty()) "Charged for ${duration(it)}" else " in ${duration(it)}")
        }
        if (detail.isNotEmpty()) lines += detail.toString()

        lines += "${socLabel(socFromBms)} ${socValue(socStart, socFromBms)}% → ${socValue(socEnd, socFromBms)}%"

        // A session reconstructed from the SoC delta has no power readings at all; one recorded
        // live has both. Emit only what was actually measured.
        val power = listOfNotNull(
            avgKw.takeIf { it > 0.0 }?.let { "avg ${fmt(it, 1)} kW" },
            peakKw.takeIf { it > 0.0 }?.let { "peak ${fmt(it, 1)} kW" },
        )
        if (power.isNotEmpty()) lines += power.joinToString(" · ")

        if (kwhAdded != null && kwhAdded > 0.0 && ratePerKwh != null) {
            val cost = kwhAdded * ratePerKwh
            lines += if (cost <= 0.0) "Cost free" else "Cost $currencySymbol${fmt(cost, 2)}"
        }

        val chargeCost = if (kwhAdded != null && kwhAdded > 0.0 && ratePerKwh != null) kwhAdded * ratePerKwh else null
        return VehicleEvent(
            type = VehicleEvent.Type.CHARGING,
            // Numbered as Charging History numbers the session's card, for the same reason.
            title = if (complete) "🔌 Charging #$sessionId complete"
                    else "🔌 Charging #$sessionId stopped at ${fmt(socEnd, 0)}%",
            lines = lines,
            key = "charge-$sessionId",
            facts = facts(
                "event" to "charge_session",
                "session_id" to sessionId,
                "started_at" to iso(startTimeMs),
                "ended_at" to iso(endTimeMs),
                "started_at_ms" to startTimeMs,
                "ended_at_ms" to endTimeMs,
                // When the app published this record, which is not when the charge ended: a
                // session the app was killed through is published at the next start, hours later.
                "closed_at" to iso(closedAtMs),
                "closed_at_ms" to closedAtMs,
                // How the app came by all of the above, since a consumer that reads an inference
                // as a measurement has no way to tell from the numbers alone.
                "origin" to origin.wireName,
                "duration_s" to chargedForMs?.takeIf { it > 0 }?.div(1000),
                // Named as the app names it. This is the energy that reached the pack, derived
                // from the SoC delta, so it is smaller than what the charger billed: a consumer
                // pricing a session from it will land under the meter.
                "energy_added_kwh" to kwhAdded?.takeIf { it > 0.0 },
                "soc_start_pct" to socStart,
                "soc_end_pct" to socEnd,
                "soc_source" to if (socFromBms) "bms" else "panel",
                // A session rebuilt from the SoC delta has no power readings at all, so these are
                // absent rather than zero for exactly the sessions that never measured them.
                "avg_kw" to avgKw.takeIf { it > 0.0 },
                "peak_kw" to peakKw.takeIf { it > 0.0 },
                "rate_per_kwh" to ratePerKwh,
                "cost" to chargeCost,
                "currency" to currencySymbol.takeIf { chargeCost != null },
                "complete" to reachedFull,
            ),
        )
    }

    /** The Pro cell-imbalance alert, as an event both channels can carry. */
    fun cellImbalance(spreadV: Double, thresholdV: Double, soc: Double): VehicleEvent {
        val spreadMv = (spreadV * 1000).roundToInt()
        val thresholdMv = (thresholdV * 1000).roundToInt()
        return VehicleEvent(
            type = VehicleEvent.Type.ALERT,
            title = "⚠️ Battery cell imbalance",
            lines = listOf(
                "Cell spread $spreadMv mV exceeds $thresholdMv mV at ${soc.roundToInt()}% SoC.",
                "A persistently high spread can indicate a weak or failing cell — " +
                    "check the Cell Voltage Spread heatmap for the trend.",
            ),
            // Bucketed per minute: the evaluator already debounces, this only stops a repeat
            // within the same minute from appearing twice.
            key = "imbalance-${System.currentTimeMillis() / 60_000L}",
        )
    }

    /** Sent by the "Send test message" button so the user can prove the wiring end to end. */
    fun test(): String = listOf(
        "✅ <b>BYD Trip Stats</b>",
        "Notifications are connected. The events you switched on will arrive here.",
    ).joinToString("\n")

    // ── Formatting helpers ────────────────────────────────────────────────────

    /** Locale-independent: a German head unit must not emit "18,3" into a bot message. */
    private fun fmt(value: Double, decimals: Int): String =
        String.format(java.util.Locale.US, "%.${decimals}f", value)

    /**
     * The machine-readable half of an event. Drops every key the car did not report, so a consumer
     * storing the payload can still tell "no reading" from "a reading of zero" — the same rule the
     * state payload already follows by omitting the cell voltages this car never publishes.
     */
    private fun facts(vararg pairs: Pair<String, Any?>): Map<String, Any?> =
        pairs.filter { it.second != null }.toMap()

    /** Epoch millis as ISO 8601 UTC, beside the raw millis so neither side has to parse. */
    private fun iso(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).toString() }

    // SoC as the trip screen shows it for the selected source (Settings → SoC): the BMS reading
    // with one decimal and labelled, the panel reading as whole percent. Rounding BMS to whole
    // numbers made it indistinguishable from the panel value.
    private fun socLabel(bms: Boolean): String = if (bms) "SoC (BMS)" else "SoC"
    private fun socValue(value: Double, bms: Boolean): String = fmt(value, if (bms) 1 else 0)

    /**
     * "14:14 → 15:20", 24-hour like the trip screens; a trip that crosses midnight carries both
     * dates ("Sep 26 23:50 → Sep 27 00:40"). The end is the trip's stored end time — the same one
     * History shows — which for an auto-stopped trip includes the car-off timeout.
     */
    internal fun timeRange(startMs: Long, endMs: Long, zone: ZoneId): String {
        val time = DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(zone)
        val dated = DateTimeFormatter.ofPattern("MMM dd HH:mm", Locale.US).withZone(zone)
        val start = Instant.ofEpochMilli(startMs)
        val end = Instant.ofEpochMilli(endMs)
        return if (start.atZone(zone).toLocalDate() == end.atZone(zone).toLocalDate()) {
            "${time.format(start)} → ${time.format(end)}"
        } else {
            "${dated.format(start)} → ${dated.format(end)}"
        }
    }

    /** "45 min" / "1 h 23 min" / "38 s" — no zero-padding, no leading "0 h". */
    internal fun duration(ms: Long): String {
        val totalMinutes = ms / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            totalMinutes < 1L -> "${ms / 1000L} s"
            hours < 1L        -> "$minutes min"
            minutes == 0L     -> "$hours h"
            else              -> "$hours h $minutes min"
        }
    }
}
