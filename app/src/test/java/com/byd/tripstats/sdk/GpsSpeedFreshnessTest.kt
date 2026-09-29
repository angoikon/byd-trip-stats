package com.byd.tripstats.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [locationFixAgeMs] and [gpsSpeedKmhIfFresh] — the guard that stops the last GPS fix
 * before an underground garage from standing in for the car's speed all night.
 */
class GpsSpeedFreshnessTest {

    private val nowNanos = 54_000_000_000_000L   // 54,000 s since boot
    private val nowWall = 1_790_000_000_000L

    // ── Fix age ───────────────────────────────────────────────────────────────

    @Test
    fun `age comes from the monotonic clock when the fix carries it`() {
        assertEquals(3_000L, locationFixAgeMs(nowNanos - 3_000_000_000L, nowNanos, 0L, nowWall))
    }

    @Test
    fun `a fix stamped on a previous boot's clock has no knowable age`() {
        // After the nightly reboot getLastKnownLocation can return a fix whose monotonic stamp is from
        // the old boot: larger than "now" on the new one. Clamping that to 0 would call it brand new.
        val now17sAfterBoot = 17_000_000_000L
        assertNull(locationFixAgeMs(nowNanos, now17sAfterBoot, nowWall, nowWall))
    }

    @Test
    fun `wall clock is the fallback when the fix has no monotonic stamp`() {
        assertEquals(4_000L, locationFixAgeMs(0L, nowNanos, nowWall - 4_000L, nowWall))
    }

    @Test
    fun `a fix slightly ahead of an unsynced wall clock counts as current`() {
        assertEquals(0L, locationFixAgeMs(0L, nowNanos, nowWall + 1_500L, nowWall))
    }

    @Test
    fun `a fix far ahead of the wall clock has no knowable age`() {
        assertNull(locationFixAgeMs(0L, nowNanos, nowWall + 60_000L, nowWall))
    }

    // ── Speed ─────────────────────────────────────────────────────────────────

    @Test
    fun `a fresh fix gives its speed in km per h`() {
        assertEquals(36.0, gpsSpeedKmhIfFresh(10f, hasSpeed = true, fixAgeMs = 1_000L)!!, 1e-6)
    }

    @Test
    fun `the garage case - the last fix before losing signal is dropped once it is stale`() {
        // 1.7491112 m/s is the 6.3 km/h that held a parked Sealion 7 "moving" all night.
        assertNull(gpsSpeedKmhIfFresh(1.7491112f, hasSpeed = true, fixAgeMs = 11_000L))
        assertNull(gpsSpeedKmhIfFresh(1.7491112f, hasSpeed = true, fixAgeMs = 8 * 3_600_000L))
    }

    @Test
    fun `the age limit is inclusive`() {
        assertEquals(
            3.6,
            gpsSpeedKmhIfFresh(1f, hasSpeed = true, fixAgeMs = GPS_SPEED_MAX_FIX_AGE_MS)!!,
            1e-6,
        )
    }

    @Test
    fun `an unknown age gives no speed`() {
        assertNull(gpsSpeedKmhIfFresh(10f, hasSpeed = true, fixAgeMs = null))
    }

    @Test
    fun `a fix without speed gives no speed`() {
        assertNull(gpsSpeedKmhIfFresh(0f, hasSpeed = false, fixAgeMs = 500L))
    }

    @Test
    fun `nonsense speeds give no speed`() {
        assertNull(gpsSpeedKmhIfFresh(Float.NaN, hasSpeed = true, fixAgeMs = 500L))
        assertNull(gpsSpeedKmhIfFresh(-1f, hasSpeed = true, fixAgeMs = 500L))
    }
}
