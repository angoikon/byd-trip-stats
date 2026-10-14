package com.byd.tripstats.sdk

import com.byd.tripstats.data.config.CarCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the DiLink-5 collectdata decode — [decodeHvCurrentAmps] and [decodeCollectChannel].
 * `onMotorMCUGeneratrixCurrent(a, b)` / `onDriverMotorSpeed(a, b)` are (front, rear) per BYD's
 * CollectDataManagerImpl. Sealion 7 reports plain amps and rpm; the Shark 6 reports amps as
 * 10000 + 0.1 A steps and has its front motor doing the electric driving.
 */
class CollectDataDecodeTest {

    private val sealion7 = CarCatalog.BYD_SEALION_7_DESIGN
    private val shark = CarCatalog.BYD_SHARK_6_AWD
    private val eps = 1e-9

    // ── Every car but the Shark: the rear channel alone, exactly as before ────────

    @Test fun `Sealion 7 reads the rear channel as plain amps`() {
        assertEquals(120.0, decodeHvCurrentAmps(front = 0, rear = 120, cfg = sealion7)!!, eps)
        assertEquals(-45.0, decodeHvCurrentAmps(front = 0, rear = -45, cfg = sealion7)!!, eps)
    }

    @Test fun `Sealion 7 ignores the front channel, whatever it reads`() {
        assertEquals(120.0, decodeHvCurrentAmps(front = 300, rear = 120, cfg = sealion7)!!, eps)
        assertEquals(120.0, decodeHvCurrentAmps(front = 50535, rear = 120, cfg = sealion7)!!, eps)
    }

    @Test fun `the rear-only range check is unchanged`() {
        assertEquals(2000.0, decodeHvCurrentAmps(front = 0, rear = 2000, cfg = sealion7)!!, eps)
        assertNull(decodeHvCurrentAmps(front = 0, rear = 2001, cfg = sealion7))
        assertNull(decodeHvCurrentAmps(front = 0, rear = 10000, cfg = sealion7)) // a Shark-style raw value
    }

    @Test fun `no car selected behaves like the Sealion 7`() {
        assertEquals(120.0, decodeHvCurrentAmps(front = 300, rear = 120, cfg = null)!!, eps)
    }

    @Test fun `only the Shark adds the front channel`() {
        assertEquals(
            listOf("BYD_SHARK_6_AWD"),
            CarCatalog.allCars.filter { it.hvCurrentSumChannels }.map { it.id },
        )
    }

    // ── Shark 6: front + rear, 0.1 A steps around 10000 ──────────────────────

    @Test fun `Shark pulling away reads the front motor's current`() {
        // compat probe 2026-10-05: a=10041 b=10000 while creeping off at a few km/h
        assertEquals(4.1, decodeHvCurrentAmps(front = 10041, rear = 10000, cfg = shark)!!, 1e-6)
    }

    @Test fun `Shark at rest reads zero`() {
        assertEquals(0.0, decodeHvCurrentAmps(front = 10000, rear = 10000, cfg = shark)!!, eps)
    }

    @Test fun `Shark adds both motors when both pull`() {
        assertEquals(70.0, decodeHvCurrentAmps(front = 10500, rear = 10200, cfg = shark)!!, 1e-6)
    }

    @Test fun `Shark regen is negative`() {
        assertEquals(-20.0, decodeHvCurrentAmps(front = 9800, rear = 10000, cfg = shark)!!, 1e-6)
    }

    @Test fun `a channel reading rubbish adds nothing to the Shark's total`() {
        // 65535 would be +5553.5 A — beyond any one motor
        assertEquals(10.0, decodeHvCurrentAmps(front = 65535, rear = 10100, cfg = shark)!!, 1e-6)
        assertEquals(10.0, decodeHvCurrentAmps(front = 10100, rear = 65535, cfg = shark)!!, 1e-6)
    }

    @Test fun `the Shark gives nothing when both channels read rubbish`() {
        assertNull(decodeHvCurrentAmps(front = 65535, rear = 65535, cfg = shark))
    }

    // ── RPM: same decode, the Shark's front channel counts down ──────────────

    @Test fun `Shark motor rpm decodes to positive values on both axles`() {
        // PR #17 sample at ~12 km/h: a=13976, b=30842
        assertEquals(1024.0, decodeCollectChannel(13976, shark.motorRpmOffsetFront, shark.motorRpmScaleFront), eps)
        assertEquals(842.0, decodeCollectChannel(30842, shark.motorRpmOffsetRear, shark.motorRpmScaleRear), eps)
        // and 0 parked (a≈15000, b≈30000)
        assertEquals(0.0, decodeCollectChannel(15000, shark.motorRpmOffsetFront, shark.motorRpmScaleFront), eps)
    }

    // ── decodeEngineGetterRpm: the getters that stand in when the events are quiet ──

    private val sentinels = setOf(8191, 16383, 32767, 65535)
    private fun sharkFront(raw: Int?) = decodeEngineGetterRpm(raw, shark.motorRpmOffsetFront, shark.motorRpmScaleFront, sentinels)
    private fun sharkRear(raw: Int?) = decodeEngineGetterRpm(raw, shark.motorRpmOffsetRear, shark.motorRpmScaleRear, sentinels)

    @Test fun `a Shark at rest reads 0 from the getters, not 15000 and 30000`() {
        // diag 2026-10-14 12:04 after a boot: rpmF=15000 rpmR=30000 while parked
        assertEquals(0, sharkFront(15000))
        assertEquals(1, sharkFront(14999))
        assertEquals(0, sharkRear(30000))
        assertEquals(0, sharkRear(29999))   // just below the baseline counts as stopped
    }

    @Test fun `a moving Shark reads the same rpm from the getters as from the events`() {
        assertEquals(1024, sharkFront(13976))
        assertEquals(842, sharkRear(30842))
    }

    @Test fun `getter sentinels and no reading pass through for the caller`() {
        assertEquals(65535, sharkFront(65535))
        assertEquals(8191, sharkRear(8191))
        assertEquals(null, sharkFront(null))
    }

    @Test fun `every car without a decode gets the raw getter value back`() {
        for (raw in listOf(0, 1, 850, 4321, 15000, 30000, 50535, 65535)) {
            assertEquals(raw, decodeEngineGetterRpm(raw, sealion7.motorRpmOffsetFront, sealion7.motorRpmScaleFront, sentinels))
            assertEquals(raw, decodeEngineGetterRpm(raw, 0, 1.0, sentinels))
        }
    }

    @Test fun `Sealion 7 rpm passes through unchanged`() {
        assertEquals(4321.0, decodeCollectChannel(4321, sealion7.motorRpmOffsetFront, sealion7.motorRpmScaleFront), eps)
    }
}
