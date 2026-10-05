package com.byd.tripstats.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the Android Automotive vehicle-property mappings in [AaosPlatform] — ignition as
 * carOn and VehicleGear as the gear letter (DiLink 100 / Atto 3 EVO).
 */
class AaosPlatformTest {

    @Test
    fun `ON and START mean the car is on`() {
        assertEquals(2, AaosPlatform.carOnFromIgnitionState(4))
        assertEquals(2, AaosPlatform.carOnFromIgnitionState(5))
    }

    @Test
    fun `LOCK, OFF and ACC mean the car is off`() {
        assertEquals(0, AaosPlatform.carOnFromIgnitionState(1))
        assertEquals(0, AaosPlatform.carOnFromIgnitionState(2))   // the Atto at switch-off, 2026-10-05
        assertEquals(0, AaosPlatform.carOnFromIgnitionState(3))   // infotainment only, not drivable
    }

    @Test
    fun `UNDEFINED and a missing reading give no answer`() {
        assertNull(AaosPlatform.carOnFromIgnitionState(null))
        assertNull(AaosPlatform.carOnFromIgnitionState(0))
        assertNull(AaosPlatform.carOnFromIgnitionState(9))
    }

    @Test
    fun `gears map to their letters`() {
        assertEquals("P", AaosPlatform.gearLetter(0x4))   // the Atto parked
        assertEquals("R", AaosPlatform.gearLetter(0x2))
        assertEquals("N", AaosPlatform.gearLetter(0x1))
        assertEquals("D", AaosPlatform.gearLetter(0x8))
        assertEquals("D", AaosPlatform.gearLetter(0x10))  // GEAR_1 and up: a forward gear
    }

    @Test
    fun `unknown gears give no answer`() {
        assertNull(AaosPlatform.gearLetter(null))
        assertNull(AaosPlatform.gearLetter(0))
    }
}
