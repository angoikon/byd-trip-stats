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
        assertTrue(text, text.startsWith("<b>🚗 Trip finished</b>"))
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
            7L, 43.0, 31.0, 9.8, null, 0.0, 0.0, null, "€").key)
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
    ) = TelegramEventMessages.chargingFinished(
        7L, socEnd, socStart, kwhAdded, durationMs, avgKw, peakKw, ratePerKwh, currencySymbol,
    ).text()

    @Test
    fun `a charge that ended short says where it stopped`() {
        val text = charging(socEnd = 43.0)
        assertTrue(text, text.contains("Charging stopped at 43%"))
        assertTrue(text, text.contains("Added 9.80 kWh in 1 h 12 min"))
        assertTrue(text, text.contains("SoC 31% → 43%"))
        assertTrue(text, text.contains("avg 8.2 kW · peak 11.0 kW"))
    }

    /** The BMS settles the last fraction late, so a full charge routinely records as 99.x. */
    @Test
    fun `a full charge reads as complete, not as stopped`() {
        assertTrue(charging(socEnd = 100.0).contains("Charging complete"))
        assertTrue(charging(socEnd = 99.2).contains("Charging complete"))
        assertTrue(charging(socEnd = 98.0).contains("Charging stopped at 98%"))
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
            avgKw = 6.1, peakKw = 7.0, ratePerKwh = null, currencySymbol = "€",
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
            7L, 43.0, 31.2, 9.8, null, 0.0, 0.0, null, "€", socFromBms = true,
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
