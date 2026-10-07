package com.byd.tripstats.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [speedStallLooksMoving] — when the "speed=0 while moving" diagnostic takes the
 * drivetrain for driving. The case it exists for: a Sealion 7 whose rear motor reads 1 rpm at rest,
 * which wrote the line every 30 s at every stop.
 */
class SpeedStallLooksMovingTest {

    @Test
    fun `a motor idling at rest is not driving`() {
        assertFalse(speedStallLooksMoving(frontRpm = 0, rearRpm = 1, powerKw = 0))
        assertFalse(speedStallLooksMoving(frontRpm = null, rearRpm = 3, powerKw = null))
    }

    @Test
    fun `creeping in a queue is not driving`() {
        assertFalse(speedStallLooksMoving(frontRpm = 0, rearRpm = 235, powerKw = 0))
        assertFalse(speedStallLooksMoving(frontRpm = 0, rearRpm = SPEED_STALL_MIN_MOTOR_RPM - 1, powerKw = 1))
    }

    @Test
    fun `either motor turning at driving speed is driving`() {
        assertTrue(speedStallLooksMoving(frontRpm = 0, rearRpm = SPEED_STALL_MIN_MOTOR_RPM, powerKw = 0))
        assertTrue(speedStallLooksMoving(frontRpm = 4940, rearRpm = null, powerKw = null))
    }

    @Test
    fun `traction or regen power of 2 kW is driving`() {
        assertTrue(speedStallLooksMoving(frontRpm = 0, rearRpm = 1, powerKw = 2))
        assertTrue(speedStallLooksMoving(frontRpm = 0, rearRpm = 1, powerKw = -12))
    }

    @Test
    fun `no drivetrain readings is not driving`() {
        assertFalse(speedStallLooksMoving(frontRpm = null, rearRpm = null, powerKw = null))
    }
}
