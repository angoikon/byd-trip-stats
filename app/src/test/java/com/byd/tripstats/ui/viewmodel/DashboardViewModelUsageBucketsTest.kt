package com.byd.tripstats.ui.viewmodel

import com.byd.tripstats.data.local.entity.TripEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/** Tests for [DashboardViewModel.usageBuckets] — the consumption charts' day / week / month buckets. */
class DashboardViewModelUsageBucketsTest {

    private val zone = ZoneId.of("Europe/Athens")
    private lateinit var savedLocale: Locale
    private lateinit var savedZone: TimeZone

    @Before
    fun pinLocale() {
        savedLocale = Locale.getDefault()
        savedZone = TimeZone.getDefault()
        Locale.setDefault(Locale.UK)          // weeks start on Monday
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
        TimeZone.setDefault(savedZone)
    }

    private fun ms(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private var nextId = 1L
    private fun trip(startMs: Long, km: Double, kwh: Double, active: Boolean = false) = TripEntity(
        id = nextId++,
        startTime = startMs,
        endTime = if (active) null else startMs + 3_600_000L,
        startOdometer = 1000.0,
        endOdometer = if (active) null else 1000.0 + km,
        startSoc = 80.0,
        startTotalDischarge = 500.0,
        endTotalDischarge = if (active) null else 500.0 + kwh,
        isActive = active,
    )

    @Test
    fun `every day is a bucket, an empty one is zero with no consumption`() {
        val trips = listOf(trip(ms(2026, 10, 12, 9), 40.0, 6.0), trip(ms(2026, 10, 14, 18), 20.0, 4.0))
        val buckets = DashboardViewModel.usageBuckets(
            trips, ms(2026, 10, 12), ms(2026, 10, 15), DashboardViewModel.UsageBucketSize.DAY, "dd/MM"
        )
        assertEquals(listOf("12/10", "13/10", "14/10"), buckets.map { it.dateLabel })
        assertEquals(listOf(40.0, 0.0, 20.0), buckets.map { it.distanceKm })
        assertEquals(listOf(6.0, 0.0, 4.0), buckets.map { it.energyKwh })
        assertEquals(15.0, buckets[0].consumptionKwhPer100km!!, 1e-9)
        assertNull(buckets[1].consumptionKwhPer100km)
    }

    @Test
    fun `distance and energy count every trip, consumption only the plausible ones`() {
        val day = ms(2026, 10, 12, 8)
        val trips = listOf(
            trip(day, 30.0, 4.5),            // 15 kWh/100 km
            trip(day + 60_000, 0.2, 0.3),    // a parked pre-heat: 150 kWh/100 km over 200 m
            trip(day + 120_000, 5.0, 0.0),   // no energy recorded
            trip(day + 180_000, 9.0, 1.0, active = true),
        )
        val b = DashboardViewModel.usageBuckets(
            trips, ms(2026, 10, 12), ms(2026, 10, 13), DashboardViewModel.UsageBucketSize.DAY, "dd/MM"
        ).single()
        assertEquals(35.2, b.distanceKm, 1e-9)          // the active trip has no distance yet
        assertEquals(4.8, b.energyKwh, 1e-9)
        assertEquals(15.0, b.consumptionKwhPer100km!!, 1e-9)
        assertEquals(30.0, b.consumptionDistanceKm, 1e-9)
    }

    @Test
    fun `weeks start on the locale's first day and the range clips the first one`() {
        // 2026-10-07 is a Wednesday; its week starts Monday 5 October.
        val trips = listOf(
            trip(ms(2026, 10, 5, 12), 100.0, 15.0),  // before the range, same week
            trip(ms(2026, 10, 8, 12), 10.0, 1.5),
            trip(ms(2026, 10, 13, 12), 20.0, 3.0),   // next week
        )
        val buckets = DashboardViewModel.usageBuckets(
            trips, ms(2026, 10, 7), ms(2026, 10, 19), DashboardViewModel.UsageBucketSize.WEEK, "dd/MM"
        )
        assertEquals(listOf("07/10", "12/10"), buckets.map { it.dateLabel })
        assertEquals(listOf(10.0, 20.0), buckets.map { it.distanceKm })
    }

    @Test
    fun `a daylight-saving change doesn't move a trip to the wrong day`() {
        // Clocks go forward in Athens at 03:00 on 29 March 2026: that day has 23 hours.
        val lateOn29th = trip(ms(2026, 3, 29, 23, 30), 12.0, 2.0)
        val buckets = DashboardViewModel.usageBuckets(
            listOf(lateOn29th), ms(2026, 3, 28), ms(2026, 3, 31), DashboardViewModel.UsageBucketSize.DAY, "dd/MM"
        )
        assertEquals(listOf("28/03", "29/03", "30/03"), buckets.map { it.dateLabel })
        assertEquals(listOf(0.0, 12.0, 0.0), buckets.map { it.distanceKm })
    }

    @Test
    fun `months are calendar months`() {
        val trips = listOf(trip(ms(2026, 1, 31, 20), 50.0, 8.0), trip(ms(2026, 2, 1, 7), 30.0, 5.0))
        val buckets = DashboardViewModel.usageBuckets(
            trips, ms(2026, 1, 1), ms(2026, 3, 1), DashboardViewModel.UsageBucketSize.MONTH, "MMM yy"
        )
        assertEquals(2, buckets.size)
        assertEquals(listOf(50.0, 30.0), buckets.map { it.distanceKm })
    }

    @Test
    fun `a picked range is shown by day up to a month, by week up to half a year, then by month`() {
        fun size(days: Int) = DashboardViewModel.UsageRange(ms(2026, 1, 1), ms(2026, 1, 1) + (days - 1) * 86_400_000L).bucketSize
        assertEquals(DashboardViewModel.UsageBucketSize.DAY, size(1))
        assertEquals(DashboardViewModel.UsageBucketSize.DAY, size(31))
        assertEquals(DashboardViewModel.UsageBucketSize.WEEK, size(32))
        assertEquals(DashboardViewModel.UsageBucketSize.WEEK, size(182))
        assertEquals(DashboardViewModel.UsageBucketSize.MONTH, size(183))
    }
}
