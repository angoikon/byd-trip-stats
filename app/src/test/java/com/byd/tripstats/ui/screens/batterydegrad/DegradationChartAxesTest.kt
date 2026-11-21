package com.byd.tripstats.ui.screens.batterydegrad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** The Battery degradation chart's axes: x/y spans, the date-tick step and where ticks fall. */
class DegradationChartAxesTest {

    private val day = 86_400_000L
    private val utc = TimeZone.getTimeZone("UTC")

    private fun ms(year: Int, month: Int, dayOfMonth: Int, zone: TimeZone = utc): Long =
        GregorianCalendar(zone).apply {
            clear()
            set(year, month - 1, dayOfMonth)
        }.timeInMillis

    private fun step(field: Int, count: Int) =
        DATE_TICK_STEPS.first { it.field == field && it.count == count }

    // ── x span ───────────────────────────────────────────────────────────────

    @Test
    fun `projected tail is a quarter of the recorded span, so trips fill the width`() {
        val (from, to) = degradationXRange(0L, 200 * day)
        assertEquals(0.0, from, 0.0)
        assertEquals(250.0 * day, to, 1.0)
    }

    @Test
    fun `a single-day history still gets a day of projection`() {
        val (from, to) = degradationXRange(1_000L, 1_000L)
        assertEquals(1_000.0 + day, to, 1.0)
        assertTrue(to > from)
    }

    // ── y span ───────────────────────────────────────────────────────────────

    @Test
    fun `fits a history around 97-98 percent instead of starting from 94`() {
        val (yMin, yMax) = degradationYRange(97.0, 98.02, trendStart = 98.1, trendEnd = 97.6)
        assertEquals(96.0, yMin, 0.0)
        assertEquals(100.0, yMax, 0.0)
    }

    @Test
    fun `follows a lower pack without an empty band up to 100`() {
        val (yMin, yMax) = degradationYRange(88.0, 90.0, trendStart = 90.2, trendEnd = 87.8)
        assertEquals(86.0, yMin, 0.0)
        assertEquals(92.0, yMax, 0.0)
    }

    @Test
    fun `a flat 100 percent history is at least three percent tall`() {
        val (yMin, yMax) = degradationYRange(100.0, 100.0, 100.0, 100.0)
        assertEquals(97.0, yMin, 0.0)
        assertEquals(100.0, yMax, 0.0)
    }

    @Test
    fun `a steep trend widens the span by at most three percent past the data`() {
        val (yMin, _) = degradationYRange(95.0, 96.0, trendStart = 96.0, trendEnd = 80.0)
        assertEquals(91.0, yMin, 0.0)
    }

    @Test
    fun `a rising trend adds no headroom above 100`() {
        val (_, yMax) = degradationYRange(99.0, 99.6, trendStart = 99.0, trendEnd = 100.8)
        assertEquals(100.0, yMax, 0.0)
    }

    // ── tick step ────────────────────────────────────────────────────────────

    @Test
    fun `picks the finest step whose labels fit`() {
        // ~9 months on a wide head-unit chart, 60-px labels needing 90 px each.
        val chosen = pickDateTickStep(274.0 * day, 3000f) { 60f }
        assertEquals(Calendar.WEEK_OF_YEAR, chosen.field)
        assertEquals(2, chosen.count)
    }

    @Test
    fun `a narrow chart falls back to months`() {
        val chosen = pickDateTickStep(274.0 * day, 700f) { 60f }
        assertEquals(Calendar.MONTH, chosen.field)
        assertEquals(2, chosen.count)
    }

    @Test
    fun `uses the coarsest step when nothing fits`() {
        val chosen = pickDateTickStep(100 * 365.25 * day, 100f) { 60f }
        assertEquals(DATE_TICK_STEPS.last(), chosen)
    }

    // ── tick positions ───────────────────────────────────────────────────────

    @Test
    fun `monthly ticks fall on the first of each month inside the span`() {
        val ticks = dateTicks(ms(2026, 4, 20), ms(2026, 11, 18), step(Calendar.MONTH, 1), utc, Locale.UK)
        assertEquals((5..11).map { ms(2026, it, 1) }, ticks)
    }

    @Test
    fun `quarterly ticks fall on January, April, July and October`() {
        val ticks = dateTicks(ms(2025, 11, 10), ms(2027, 2, 1), step(Calendar.MONTH, 3), utc, Locale.UK)
        assertEquals(
            listOf(ms(2026, 1, 1), ms(2026, 4, 1), ms(2026, 7, 1), ms(2026, 10, 1), ms(2027, 1, 1)),
            ticks
        )
    }

    @Test
    fun `weekly ticks fall on the locale's first day of the week`() {
        val ticks = dateTicks(ms(2026, 11, 4), ms(2026, 12, 1), step(Calendar.WEEK_OF_YEAR, 1), utc, Locale.UK)
        val cal = Calendar.getInstance(utc, Locale.UK)
        assertEquals(4, ticks.size)
        ticks.forEach {
            cal.timeInMillis = it
            assertEquals(Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
        }
    }

    @Test
    fun `daily ticks stay on local midnight across a DST change`() {
        val athens = TimeZone.getTimeZone("Europe/Athens")
        val ticks = dateTicks(
            ms(2026, 3, 27, athens), ms(2026, 4, 1, athens),
            step(Calendar.DAY_OF_MONTH, 1), athens, Locale.UK
        )
        val cal = Calendar.getInstance(athens, Locale.UK)
        assertEquals(6, ticks.size)
        ticks.forEach {
            cal.timeInMillis = it
            assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        }
    }

    @Test
    fun `yearly ticks fall on New Year`() {
        val ticks = dateTicks(ms(2024, 6, 1), ms(2028, 3, 1), step(Calendar.YEAR, 2), utc, Locale.UK)
        assertEquals(listOf(ms(2026, 1, 1), ms(2028, 1, 1)), ticks)
    }
}
