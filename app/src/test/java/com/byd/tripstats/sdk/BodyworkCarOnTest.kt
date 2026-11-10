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

    @Test
    fun `the DiLink-5 bodywork line names the power level and keeps the rest as read`() {
        assertEquals(
            "powerLevel=2 (ON) autoSystemState=1 readyLight=0",
            di5BodyworkPowerState(powerLevel = 2, autoSystemState = 1, readyLight = 0),
        )
        assertEquals(
            "powerLevel=0 (OFF) autoSystemState=null readyLight=null",
            di5BodyworkPowerState(powerLevel = 0, autoSystemState = null, readyLight = null),
        )
        assertEquals("powerLevel=1 (ACC)", di5BodyworkPowerState(1, 0, 0).substringBefore(" auto"))
        assertEquals("powerLevel=255 (none)", di5BodyworkPowerState(255, 0, 0).substringBefore(" auto"))
        assertEquals("powerLevel=null", di5BodyworkPowerState(null, 0, 0).substringBefore(" auto"))
        assertEquals("powerLevel=-2147482645", di5BodyworkPowerState(-2147482645, 0, 0).substringBefore(" auto"))
    }

    @Test
    fun `the doors are read over areas 1 to 6, bonnet and boot included`() {
        assertEquals(1..6, BODYWORK_DOOR_AREAS)
    }

    @Test
    fun `a door counts as open only when it reads OPEN`() {
        // All shut, as DiLink 5 reads them (its layer maps the car's "closed" to the SDK's 0).
        assertEquals(0, anyDoorOpenFromStates(listOf(0, 0, 0, 0, 0, 0)))
        // The boot (area 6) open.
        assertEquals(1, anyDoorOpenFromStates(listOf(0, 0, 0, 0, 0, 1)))
        // DiLink 5 answers -1 for a door it has no value for; that is no open door. The old loop
        // read area 0, got -1 there, and reported a door open on every DiLink-5 car.
        assertEquals(0, anyDoorOpenFromStates(listOf(-1, 0, 0, 0, 0, 0)))
        // UNDEFINED and BYD's sentinel are not readings either.
        assertEquals(0, anyDoorOpenFromStates(listOf(255, -2147482645, 0, null, 0, 0)))
    }

    @Test
    fun `no OPEN or CLOSED reading means the door state is unknown`() {
        assertNull(anyDoorOpenFromStates(emptyList()))
        assertNull(anyDoorOpenFromStates(listOf(null, null, null, null, null, null)))
        assertNull(anyDoorOpenFromStates(listOf(-1, -1, 255, -2147482645, null, -1)))
    }

    @Test
    fun `the DiLink-5 door line lists every door and lock as read`() {
        assertEquals(
            "doors(LF,RF,LR,RR,bonnet,boot)=0,0,0,0,0,1 locks(LR,RF,RR)=1,2,null",
            di5BodyworkDoorState(listOf(0, 0, 0, 0, 0, 1), listOf(1, 2, null)),
        )
        assertEquals(listOf(2, 3, 4), DI5_BODYWORK_LOCK_AREAS)
    }

    @Test
    fun `DiLink-5 lock readings - 2 is locked, 1 is unlocked`() {
        // As read on a Sealion 7: 2,2,2 driving and after walking away; 1,1,1 with the driver in.
        assertEquals(1, di5CarLockedFromLockStates(listOf(2, 2, 2)))
        assertEquals(0, di5CarLockedFromLockStates(listOf(1, 1, 1)))
    }

    @Test
    fun `one DiLink-5 door reading unlocked means unlocked`() {
        assertEquals(0, di5CarLockedFromLockStates(listOf(2, 1, 2)))
        // A door without a reading doesn't stop the others from saying locked.
        assertEquals(1, di5CarLockedFromLockStates(listOf(0, 2, null)))
    }

    @Test
    fun `no DiLink-5 lock reading means the lock state is unknown`() {
        assertNull(di5CarLockedFromLockStates(emptyList()))
        assertNull(di5CarLockedFromLockStates(listOf(null, null, null)))
        assertNull(di5CarLockedFromLockStates(listOf(0, -1, 255)))
    }
}
