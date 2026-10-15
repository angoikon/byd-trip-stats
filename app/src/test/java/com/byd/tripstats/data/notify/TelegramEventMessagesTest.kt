package com.byd.tripstats.data.notify

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/**
 * Tests for the Telegram event message text. Pure JVM — no Android context required.
 *
 * The cases that matter here are the ones that only show up on a user's phone, where they
 * can't be fixed after the fact: a comma decimal separator from the head unit's locale, an
 * imperial user getting metric numbers with a "mi" label, and a cost line printed for a trip
 * the app itself prices as unknown.
 */
class TelegramEventMessagesTest {

    /** The Telegram rendering, which is what most of these assertions read. */
    private fun VehicleEvent.text() = telegramHtml

    private fun tripSummary(
        distanceKm: Double = 20.0,
        durationMs: Long? = 30 * 60_000L,
        energyKwh: Double? = 3.4,
        efficiencyKwh100km: Double? = 17.0,
        fleetAvgKwh100km: Double? = 17.0,
        startSoc: Double = 80.0,
        endSoc: Double? = 76.0,
        avgSpeedKmh: Double? = 40.0,
        tripScore: Int? = null,
        energyRatePerKwh: Double? = null,
        currencySymbol: String = "€",
        imperial: Boolean = false,
    ) = TelegramEventMessages.tripSummary(
        1L, distanceKm, durationMs, energyKwh, efficiencyKwh100km, fleetAvgKwh100km,
        startSoc, endSoc, avgSpeedKmh, tripScore, energyRatePerKwh, currencySymbol, imperial,
    ).text()

    // ── Trip summary ────────────────────────────────────────────────────────────

    @Test
    fun `trip summary carries distance, duration, energy and SoC`() {
        val text = tripSummary()
        assertTrue(text, text.contains("20.0 km in 30 min"))
        assertTrue(text, text.contains("avg 40 km/h"))
        assertTrue(text, text.contains("3.40 kWh · 17.0 kWh/100km"))
        assertTrue(text, text.contains("SoC 80% → 76% (−4%)"))
    }

    /** A German/French head unit locale must not leak "17,0" into the bot message. */
    @Test
    fun `numbers use a dot regardless of the device locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val text = tripSummary()
            assertTrue(text, text.contains("20.0 km"))
            assertFalse(text, text.contains("20,0"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `imperial converts distance, speed and consumption and labels them`() {
        val text = tripSummary(imperial = true)
        assertTrue(text, text.contains("12.4 mi"))
        assertTrue(text, text.contains("mph"))
        assertTrue(text, text.contains("kWh/100mi"))
        assertFalse(text, text.contains("km"))
    }

    @Test
    fun `consumption is compared against the lifetime average`() {
        assertTrue(tripSummary(efficiencyKwh100km = 20.4, fleetAvgKwh100km = 17.0)
            .contains("20% worse than your 17.0 average"))
        assertTrue(tripSummary(efficiencyKwh100km = 13.6, fleetAvgKwh100km = 17.0)
            .contains("20% better than your 17.0 average"))
        assertTrue(tripSummary(efficiencyKwh100km = 17.05, fleetAvgKwh100km = 17.0)
            .contains("on par with"))
    }

    @Test
    fun `comparison is omitted when there is no average to compare against`() {
        val text = tripSummary(fleetAvgKwh100km = null)
        assertTrue(text, text.contains("kWh/100km"))
        assertFalse(text, text.contains("average"))
    }

    /** A null rate is the app's own "no price signal, hide the cost" verdict. */
    @Test
    fun `cost appears only when the app resolved a rate`() {
        assertFalse(tripSummary(energyRatePerKwh = null).contains("Cost"))
        assertFalse(tripSummary(energyRatePerKwh = 0.0).contains("Cost"))
        assertTrue(tripSummary(energyRatePerKwh = 0.25).contains("Cost €0.85"))
    }

    @Test
    fun `trip score is shown when the trip has one`() {
        assertFalse(tripSummary(tripScore = null).contains("Trip score"))
        assertTrue(tripSummary(tripScore = 73).contains("Trip score 73/100"))
    }

    @Test
    fun `an unfinished or dataless trip still produces a valid card`() {
        val text = tripSummary(durationMs = null, energyKwh = null, efficiencyKwh100km = null, endSoc = null)
        assertTrue(text, text.startsWith("<b>🚗 Trip #1 finished</b>"))
        assertTrue(text, text.contains("20.0 km"))
        assertFalse(text, text.contains("SoC"))
    }

    /**
     * The currency symbol is the only free text a user can put in a card. Escaping is the
     * Telegram rendering's job, so the plain body every other channel reads must keep the
     * original — an escaped entity showing up as literal "&lt;" in the web companion's feed
     * is exactly what moving the escape out of the builder prevents.
     */
    @Test
    fun `currency symbol is escaped for Telegram but not in the plain body`() {
        val event = TelegramEventMessages.tripSummary(
            1L, 20.0, 30 * 60_000L, 3.4, 17.0, 17.0, 80.0, 76.0, 40.0, null, 0.25, "<b>", false,
        )
        assertTrue(event.telegramHtml, event.telegramHtml.contains("&lt;b&gt;"))
        assertTrue(event.body, event.body.contains("Cost <b>0.85"))
    }

    /** Both renderings must carry the same title, or the channels quietly disagree. */
    @Test
    fun `the title leads both renderings`() {
        val event = TelegramEventMessages.cellImbalance(0.062, 0.05, 64.0)
        assertEquals("⚠️ Battery cell imbalance", event.title)
        assertTrue(event.telegramHtml.startsWith("<b>⚠️ Battery cell imbalance</b>"))
        assertFalse(event.body, event.body.contains("<b>"))
    }

    /** The key is what stops one row being announced twice. */
    @Test
    fun `events are keyed by the row they describe`() {
        assertEquals("trip-1", TelegramEventMessages.tripSummary(
            1L, 20.0, null, null, null, null, 80.0, null, null, null, null, "€", false).key)
        assertEquals("charge-7", TelegramEventMessages.chargingFinished(
            7L, 43.0, 31.0, 9.8, null, 0.0, 0.0, null, "€", ChargeOrigin.LIVE).key)
    }

    // ── Charging ────────────────────────────────────────────────────────────────

    private fun charging(
        socEnd: Double = 43.0,
        socStart: Double = 31.0,
        kwhAdded: Double? = 9.8,
        durationMs: Long? = 72 * 60_000L,
        avgKw: Double = 8.2,
        peakKw: Double = 11.0,
        ratePerKwh: Double? = null,
        currencySymbol: String = "€",
        origin: ChargeOrigin = ChargeOrigin.LIVE,
    ) = TelegramEventMessages.chargingFinished(
        7L, socEnd, socStart, kwhAdded, durationMs, avgKw, peakKw, ratePerKwh, currencySymbol, origin,
    ).text()

    @Test
    fun `a charge that ended short says where it stopped`() {
        val text = charging(socEnd = 43.0)
        assertTrue(text, text.contains("Charging #7 stopped at 43%"))
        assertTrue(text, text.contains("Added 9.80 kWh in 1 h 12 min"))
        assertTrue(text, text.contains("SoC 31% → 43%"))
        assertTrue(text, text.contains("avg 8.2 kW · peak 11.0 kW"))
    }

    /** The BMS settles the last fraction late, so a full charge routinely records as 99.x. */
    @Test
    fun `a full charge reads as complete, not as stopped`() {
        assertTrue(charging(socEnd = 100.0).contains("Charging #7 complete"))
        assertTrue(charging(socEnd = 99.2).contains("Charging #7 complete"))
        assertTrue(charging(socEnd = 98.0).contains("Charging #7 stopped at 98%"))
    }

    /** The number in the title is the row's id — the one its History card shows — in both channels. */
    @Test
    fun `trip and charging titles carry the History number`() {
        val trip = TelegramEventMessages.tripSummary(
            756L, 20.0, null, null, null, null, 80.0, null, null, null, null, "€", false)
        assertEquals("🚗 Trip #756 finished", trip.title)
        assertTrue(trip.telegramHtml, trip.telegramHtml.startsWith("<b>🚗 Trip #756 finished</b>"))
        val charge = TelegramEventMessages.chargingFinished(
            12L, 43.0, 31.0, 9.8, null, 0.0, 0.0, null, "€", ChargeOrigin.LIVE)
        assertEquals("🔌 Charging #12 stopped at 43%", charge.title)
    }

    /**
     * A reconstructed session is bounded by a shutdown and a wake-up, not measured between them,
     * so the span is the whole park. Printing it as a charging time turns an eight-hour night
     * into an eight-hour charge. The payload drops it for the same reason, and the two must not
     * tell different stories.
     */
    @Test
    fun `the charging card claims a duration only when one was measured`() {
        val watched = charging()
        val inferred = charging(origin = ChargeOrigin.SOC_ESTIMATE)
        assertTrue(watched, watched.contains("Added 9.80 kWh in 1 h 12 min"))
        assertTrue(inferred, inferred.contains("Added 9.80 kWh"))
        assertFalse(inferred, inferred.contains("1 h 12 min"))
    }

    /**
     * A recovered session was watched, just not to the end, so what it did measure stands: the
     * samples stop before the charge did, which makes its duration a floor rather than a fiction.
     */
    @Test
    fun `an interrupted session still reports the time it did measure`() {
        val text = charging(origin = ChargeOrigin.RECOVERED)
        assertTrue(text, text.contains("Added 9.80 kWh in 1 h 12 min"))
    }

    /** A session reconstructed from the SoC delta has no power readings to report. */
    @Test
    fun `charging card omits power when the session was reconstructed`() {
        val text = charging(avgKw = 0.0, peakKw = 0.0)
        assertFalse(text, text.contains("avg"))
        assertFalse(text, text.contains("peak"))
        assertTrue(text, text.contains("SoC 31% → 43%"))
    }

    /** Mirrors the charging screen: no rate at all hides cost; a zero rate reads as free. */
    @Test
    fun `charging cost follows the session's own rate`() {
        assertFalse(charging(ratePerKwh = null).contains("Cost"))
        assertTrue(charging(ratePerKwh = 0.0).contains("Cost free"))
        assertTrue(charging(ratePerKwh = 0.30).contains("Cost €2.94"))
    }

    // ── When the trip happened / which SoC ─────────────────────────────────────

    private val utc = java.time.ZoneId.of("UTC")
    private fun at(iso: String) = java.time.Instant.parse(iso).toEpochMilli()

    /** A summary can arrive hours late (queued underground); the times say which trip it is. */
    @Test
    fun `trip summary leads with its start and end time`() {
        val text = TelegramEventMessages.tripSummary(
            tripId = 752L, distanceKm = 22.8, durationMs = 64 * 60_000L, energyKwh = 3.7,
            efficiencyKwh100km = 16.2, fleetAvgKwh100km = 20.6, startSoc = 53.0, endSoc = 49.0,
            avgSpeedKmh = 21.0, tripScore = null, energyRatePerKwh = null, currencySymbol = "€",
            imperial = false,
            startTimeMs = at("2026-09-26T14:14:00Z"), endTimeMs = at("2026-09-26T15:25:00Z"), zone = utc,
        ).text()
        assertTrue(text, text.contains("14:14 → 15:25"))
        assertTrue(text, text.indexOf("14:14") < text.indexOf("22.8 km"))
    }

    @Test
    fun `a trip across midnight carries both dates`() {
        assertEquals(
            "Sep 26 23:50 → Sep 27 00:40",
            TelegramEventMessages.timeRange(at("2026-09-26T23:50:00Z"), at("2026-09-27T00:40:00Z"), utc),
        )
    }

    private val clockRange = Regex("""\d{2}:\d{2} → """)

    @Test
    fun `no time line without both times`() {
        assertFalse(clockRange.containsMatchIn(tripSummary()))
        assertFalse(clockRange.containsMatchIn(charging()))
    }

    @Test
    fun `charging summary leads with its start and end time`() {
        val text = TelegramEventMessages.chargingFinished(
            sessionId = 7L, socEnd = 80.0, socStart = 43.0, kwhAdded = 30.0, durationMs = 5 * 3_600_000L,
            avgKw = 6.1, peakKw = 7.0, ratePerKwh = null, currencySymbol = "€", origin = ChargeOrigin.LIVE,
            startTimeMs = at("2026-09-26T22:00:00Z"), endTimeMs = at("2026-09-27T03:00:00Z"), zone = utc,
        ).text()
        assertTrue(text, text.contains("Sep 26 22:00 → Sep 27 03:00"))
        assertTrue(text, text.indexOf("Sep 26") < text.indexOf("Added 30.00 kWh"))
    }

    /** BMS rounded to whole percent looked exactly like the panel reading. */
    @Test
    fun `BMS SoC is labelled and keeps its decimal, panel stays whole`() {
        val bms = TelegramEventMessages.tripSummary(
            tripId = 1L, distanceKm = 20.0, durationMs = null, energyKwh = null, efficiencyKwh100km = null,
            fleetAvgKwh100km = null, startSoc = 53.5, endSoc = 48.7, avgSpeedKmh = null, tripScore = null,
            energyRatePerKwh = null, currencySymbol = "€", imperial = false, socFromBms = true,
        ).text()
        assertTrue(bms, bms.contains("SoC (BMS) 53.5% → 48.7% (−4.8%)"))
        assertTrue(tripSummary().contains("SoC 80% → 76% (−4%)"))
        val charge = TelegramEventMessages.chargingFinished(
            7L, 43.0, 31.2, 9.8, null, 0.0, 0.0, null, "€", ChargeOrigin.LIVE, socFromBms = true,
        ).text()
        assertTrue(charge, charge.contains("SoC (BMS) 31.2% → 43.0%"))
    }

    // ── Formatting ──────────────────────────────────────────────────────────────

    @Test
    fun `durations read naturally at every scale`() {
        assertEquals("38 s", TelegramEventMessages.duration(38_000L))
        assertEquals("45 min", TelegramEventMessages.duration(45 * 60_000L))
        assertEquals("2 h", TelegramEventMessages.duration(120 * 60_000L))
        assertEquals("1 h 23 min", TelegramEventMessages.duration(83 * 60_000L))
    }

    @Test
    fun `every message stays within the Telegram length cap`() {
        val messages = listOf(
            tripSummary(tripScore = 73, energyRatePerKwh = 0.30),
            charging(ratePerKwh = 0.30),
            TelegramEventMessages.cellImbalance(0.062, 0.05, 64.0).text(),
            TelegramEventMessages.test(),
        )
        messages.forEach { assertTrue(it.length <= TelegramEventMessages.MAX_LENGTH) }
    }

    @Test
    fun `cell imbalance reports millivolts`() {
        val text = TelegramEventMessages.cellImbalance(0.062, 0.05, 64.0).text()
        assertTrue(text, text.contains("62 mV exceeds 50 mV at 64% SoC"))
    }
}

/**
 * Tests for the structured half of an event, the one MQTT publishes.
 *
 * What matters here is different from the prose: a subscriber cannot see the unit system or the
 * currency the app is set to, cannot ask what a missing reading was, and will store whatever it
 * is given. So the cases below are the ones a parser gets wrong silently — imperial leaking into
 * the numbers, a zero standing in for a reading that never happened, and a currency symbol next
 * to a cost the app itself refused to print.
 */
class VehicleEventFactsTest {

    private fun trip(
        distanceKm: Double = 20.0,
        efficiencyKwh100km: Double? = 17.0,
        energyKwh: Double? = 3.4,
        avgSpeedKmh: Double? = 40.0,
        energyRatePerKwh: Double? = null,
        currencySymbol: String = "€",
        imperial: Boolean = false,
    ) = TelegramEventMessages.tripSummary(
        tripId = 756L,
        distanceKm = distanceKm,
        durationMs = 30 * 60_000L,
        energyKwh = energyKwh,
        efficiencyKwh100km = efficiencyKwh100km,
        fleetAvgKwh100km = 17.0,
        startSoc = 80.0,
        endSoc = 76.0,
        avgSpeedKmh = avgSpeedKmh,
        tripScore = 82,
        energyRatePerKwh = energyRatePerKwh,
        currencySymbol = currencySymbol,
        imperial = imperial,
        startTimeMs = 1_760_000_000_000L,
        endTimeMs = 1_760_001_800_000L,
    ).facts

    private fun charge(
        kwhAdded: Double? = 26.5,
        avgKw: Double = 29.4,
        peakKw: Double = 43.3,
        ratePerKwh: Double? = 0.59,
        origin: ChargeOrigin = ChargeOrigin.LIVE,
        closedAtMs: Long? = 1_760_003_773_000L,
    ) = TelegramEventMessages.chargingFinished(
        sessionId = 12L,
        socEnd = 81.0,
        socStart = 49.0,
        kwhAdded = kwhAdded,
        durationMs = 3_773_000L,
        avgKw = avgKw,
        peakKw = peakKw,
        ratePerKwh = ratePerKwh,
        currencySymbol = "A$",
        startTimeMs = 1_760_000_000_000L,
        endTimeMs = 1_760_003_773_000L,
        origin = origin,
        closedAtMs = closedAtMs,
    ).facts

    @Test
    fun `an imperial user still publishes metric, because the subscriber cannot see the setting`() {
        val metric = trip(imperial = false)
        val imperial = trip(imperial = true)
        assertEquals(20.0, metric["distance_km"])
        assertEquals("the payload must not follow the display preference", metric, imperial)
    }

    @Test
    fun `a reading the car never reported is absent, not zero`() {
        // A trip the app could not price, and a session rebuilt from the SoC delta with no power
        // readings at all. A zero here would be stored as a measurement of zero.
        val unpriced = trip(energyRatePerKwh = null)
        assertFalse(unpriced.containsKey("energy_rate_per_kwh"))
        assertFalse(unpriced.containsKey("cost"))

        val reconstructed = charge(avgKw = 0.0, peakKw = 0.0)
        assertFalse(reconstructed.containsKey("avg_kw"))
        assertFalse(reconstructed.containsKey("peak_kw"))
    }

    @Test
    fun `a currency only travels with a cost it belongs to`() {
        assertFalse(trip(energyRatePerKwh = null).containsKey("currency"))
        assertEquals("€", trip(energyRatePerKwh = 0.30)["currency"])
    }

    @Test
    fun `the cost is the one the prose prints, not a second opinion`() {
        val facts = trip(energyKwh = 3.4, energyRatePerKwh = 0.59)
        assertEquals(3.4 * 0.59, facts["cost"] as Double, 1e-9)
    }

    @Test
    fun `a trip carries what it takes to place it on a timeline`() {
        val facts = trip()
        assertEquals("trip", facts["event"])
        assertEquals(756L, facts["trip_id"])
        assertEquals(1_760_000_000_000L, facts["started_at_ms"])
        assertEquals(1800L, facts["duration_s"])
        // Both forms, so neither side has to parse to compare and neither has to format to read.
        assertEquals("2025-10-09T08:53:20Z", facts["started_at"])
    }

    @Test
    fun `a charge says what reached the pack, how fast, and what it cost`() {
        val facts = charge()
        assertEquals("charge_session", facts["event"])
        assertEquals(12L, facts["session_id"])
        assertEquals(26.5, facts["energy_added_kwh"])
        assertEquals(43.3, facts["peak_kw"])
        assertEquals(0.59, facts["rate_per_kwh"])
        assertEquals(26.5 * 0.59, facts["cost"] as Double, 1e-9)
        assertEquals("A$", facts["currency"])
        // Below the full-SoC threshold, so the session stopped rather than completed — the same
        // fact the title states, in a form something other than a human can branch on.
        assertEquals(false, facts["complete"])
    }

    @Test
    fun `which SoC source produced the numbers travels with them`() {
        val panel = TelegramEventMessages.tripSummary(
            1L, 20.0, null, null, null, null, 80.0, 76.0, null, null, null, "€", false,
            socFromBms = false,
        ).facts
        assertEquals("panel", panel["soc_source"])
    }

    @Test
    fun `how a charge was recorded travels with it`() {
        assertEquals("live", charge()["origin"])
        assertEquals("recovered", charge(origin = ChargeOrigin.RECOVERED)["origin"])
        assertEquals("soc_estimate", charge(origin = ChargeOrigin.SOC_ESTIMATE)["origin"])
    }

    /**
     * The one number a reconstructed session must not state. Its timestamps are a shutdown and a
     * wake-up, so the span between them is the whole park: publishing it as duration turns an
     * eight-hour night into an eight-hour charge, and anything dividing energy by it is wrong by
     * however long the car sat there.
     */
    @Test
    fun `a session nobody watched reports no charging duration`() {
        assertEquals(3773L, charge(origin = ChargeOrigin.LIVE)["duration_s"])
        assertEquals(3773L, charge(origin = ChargeOrigin.RECOVERED)["duration_s"])
        assertFalse(charge(origin = ChargeOrigin.SOC_ESTIMATE).containsKey("duration_s"))
    }

    /**
     * When the app finalised the record, which is not when the charge ended. A consumer that
     * reads a recovered session as fresh will believe the car is still plugged in.
     */
    @Test
    fun `a late record says when it was actually written`() {
        val facts = charge(origin = ChargeOrigin.RECOVERED, closedAtMs = 1_760_040_000_000L)
        assertEquals(1_760_040_000_000L, facts["closed_at_ms"])
        assertEquals("2025-10-09T20:00:00Z", facts["closed_at"])
        assertEquals(1_760_003_773_000L, facts["ended_at_ms"])
    }

    @Test
    fun `a trip says when its record was written, and nothing without a time`() {
        val late = TelegramEventMessages.tripSummary(
            1L, 20.0, 30 * 60_000L, 3.4, 17.0, 17.0, 80.0, 76.0, 40.0, null, 0.25, "€", false,
            startTimeMs = 1_760_000_000_000L, endTimeMs = 1_760_001_800_000L, closedAtMs = 1_760_040_000_000L,
        ).facts
        assertEquals(1_760_040_000_000L, late["closed_at_ms"])
        assertEquals("2025-10-09T20:00:00Z", late["closed_at"])
        val none = TelegramEventMessages.tripSummary(
            1L, 20.0, 30 * 60_000L, 3.4, 17.0, 17.0, 80.0, 76.0, 40.0, null, 0.25, "€", false,
        ).facts
        assertNull(none["closed_at_ms"])
        assertNull(none["closed_at"])
    }

    @Test
    fun `only a charge that ended just now is news`() {
        // The push gate, as a value rather than a branch inside a class that needs a Context.
        // The other two are finalised at the next ignition, hours after the charge ended.
        assertTrue(ChargeOrigin.LIVE.announced)
        assertFalse(ChargeOrigin.RECOVERED.announced)
        assertFalse(ChargeOrigin.SOC_ESTIMATE.announced)
    }

    /**
     * The app was killed somewhere in the middle, so where the samples stop is not where the
     * charge stopped. Reporting `complete: false` there would call a charge that ran on to 100%
     * an interrupted one, on the strength of a reading taken before it finished.
     */
    @Test
    fun `an interrupted session does not say whether the charge reached full`() {
        assertEquals(false, charge(origin = ChargeOrigin.LIVE)["complete"])
        assertEquals(false, charge(origin = ChargeOrigin.SOC_ESTIMATE)["complete"])
        assertFalse(charge(origin = ChargeOrigin.RECOVERED).containsKey("complete"))
    }

    @Test
    fun `a record with no publication time says nothing about one`() {
        val facts = charge(closedAtMs = null)
        assertFalse(facts.containsKey("closed_at"))
        assertFalse(facts.containsKey("closed_at_ms"))
    }
}
