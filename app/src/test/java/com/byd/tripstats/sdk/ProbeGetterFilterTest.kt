package com.byd.tripstats.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [isProbeReadOnlyGetter] and [probeSweepsDevice] — what the DiLink-3 compatibility
 * probe may call on a BYD device. The names below are the real no-arg methods of the DiLink-3 SDK
 * (dev Seal framework.jar, 2026-11-15); until 2.21.0 the probe called every one of them, commands
 * included, at each start while it was switched on.
 */
class ProbeGetterFilterTest {

    private val int = Int::class.javaPrimitiveType!!
    private val void = Void.TYPE

    @Test
    fun `BYD commands are never called`() {
        val commands = mapOf(
            "FinishOTA" to int, "StartOTA" to int, "syncMcuState" to int,           // ota
            "dspReset" to void, "padReset" to void, "wakeUpMcu" to int,             // power
            "setBCMPowerOnLevel" to int,
            "restoreCarDefaultValue" to int, "restoreDrDefaultValue" to int,       // setting
            "turnOffInsideLight" to int, "takeDrivingRecorderPicture" to int,
            "setMoonRoofAndSunshadeStop" to int, "queryAutoVIN" to int,            // bodywork
            "learningEPB" to int,                                                   // gearbox
            "radioOn" to int, "radioOff" to int, "autoSearch" to int,              // radio
            "setAllStatus" to void,                                                 // nearly every device
        )
        for ((name, type) in commands) {
            assertFalse("$name must not be called", isProbeReadOnlyGetter(name, type))
        }
    }

    @Test
    fun `real getters are still read`() {
        assertTrue(isProbeReadOnlyGetter("getBatteryPowerVoltage", Double::class.javaPrimitiveType!!))
        assertTrue(isProbeReadOnlyGetter("getOTAState", int))
        assertTrue(isProbeReadOnlyGetter("getECUVersion", ByteArray::class.java))
        assertTrue(isProbeReadOnlyGetter("getTBoxSerialNumber", String::class.java))
        assertTrue(isProbeReadOnlyGetter("isAutoParkingOn", Boolean::class.javaPrimitiveType!!))
        assertTrue(isProbeReadOnlyGetter("hasSunroof", Boolean::class.javaPrimitiveType!!))
        assertTrue(isProbeReadOnlyGetter("get12VBatteryVoltage", Double::class.javaPrimitiveType!!))
    }

    @Test
    fun `a getter name that returns nothing is not called`() {
        assertFalse(isProbeReadOnlyGetter("getSomething", void))
    }

    @Test
    fun `names that only start with the same letters are not getters`() {
        assertFalse(isProbeReadOnlyGetter("issueCommand", int))
        assertFalse(isProbeReadOnlyGetter("hashCode", int))
        assertFalse(isProbeReadOnlyGetter("getaway", int))
        assertFalse(isProbeReadOnlyGetter("get", int))
        assertFalse(isProbeReadOnlyGetter("is", Boolean::class.javaPrimitiveType!!))
    }

    @Test
    fun `the OTA device is never swept`() {
        assertFalse(probeSweepsDevice("ota", "android.hardware.bydauto.ota.BYDAutoOtaDevice"))
        assertFalse(probeSweepsDevice("OTA", "x.Y"))
        assertFalse(probeSweepsDevice("firmware", "android.hardware.bydauto.ota.BYDAutoOtaDevice"))
    }

    @Test
    fun `other devices are swept`() {
        assertTrue(probeSweepsDevice("power", "android.hardware.bydauto.power.BYDAutoPowerDevice"))
        assertTrue(probeSweepsDevice("instrument", "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"))
    }
}
