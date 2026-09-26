package com.byd.tripstats.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [carOnFromBodyworkPowerLevel] — the bodywork power level as carOn on cars that
 * report no power state (SDK §6.1.9: 0x0 OFF / 0x1 ACC / 0x2 ON; the listener adds 3 = OK).
 */
class BodyworkCarOnTest {

    @Test
    fun `ON and OK mean the car is on`() {
        assertEquals(2, carOnFromBodyworkPowerLevel(2))   // P with the power on, on the dev car
        assertEquals(2, carOnFromBodyworkPowerLevel(3))
    }

    @Test
    fun `OFF and ACC mean the car is off`() {
        assertEquals(0, carOnFromBodyworkPowerLevel(0))   // every app start after a switch-off
        assertEquals(0, carOnFromBodyworkPowerLevel(1))   // infotainment only, not drivable
    }

    @Test
    fun `sentinels and a missing reading give no answer`() {
        assertNull(carOnFromBodyworkPowerLevel(null))
        assertNull(carOnFromBodyworkPowerLevel(4))            // FAKE_OK
        assertNull(carOnFromBodyworkPowerLevel(255))          // INVALID
        assertNull(carOnFromBodyworkPowerLevel(-2147482645))  // BODYWORK_COMMAND_INVALID_VALUE
    }
}
