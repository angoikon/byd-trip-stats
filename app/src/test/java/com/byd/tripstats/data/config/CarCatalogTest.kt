package com.byd.tripstats.data.config

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for CarCatalog lookup and CarConfig value sanity.
 * Pure JVM.
 */
class CarCatalogTest {

    // ── fromId ────────────────────────────────────────────────────────────────

    @Test fun `fromId returns correct car for known id`() {
        val car = CarCatalog.fromId("BYD_SEAL_EXCELLENCE")
        assertNotNull(car)
        assertEquals("Seal Excellence", car!!.displayName)
        assertEquals(Drivetrain.AWD, car.drivetrain)
    }

    @Test fun `fromId returns null for unknown id`() {
        assertNull(CarCatalog.fromId("UNKNOWN_CAR"))
        assertNull(CarCatalog.fromId(null))
        assertNull(CarCatalog.fromId(""))
    }

    @Test fun `fromId is case sensitive`() {
        assertNull(CarCatalog.fromId("byd_seal_excellence"))
    }

    @Test fun `all catalog IDs are unique`() {
        val ids = CarCatalog.allCars.map { it.id }
        assertEquals("Duplicate IDs found", ids.size, ids.toSet().size)
    }

    // ── Value sanity ──────────────────────────────────────────────────────────

    @Test fun `all cars have positive batteryKwh`() {
        CarCatalog.allCars.forEach { car ->
            assertTrue("${car.id} batteryKwh must be > 0", car.batteryKwh > 0.0)
        }
    }

    @Test fun `all cars have positive wltpKm`() {
        CarCatalog.allCars.forEach { car ->
            assertTrue("${car.id} wltpKm must be > 0", car.wltpKm > 0)
        }
    }

    @Test fun `referenceConsumption converts to valid Wh per km range`() {
        // Real EVs: typically 120–250 Wh/km
        CarCatalog.allCars.forEach { car ->
            val whPerKm = car.referenceConsumptionKwhPer100km * 10.0
            assertTrue("${car.id} Wh/km ($whPerKm) below minimum", whPerKm >= 100.0)
            assertTrue("${car.id} Wh/km ($whPerKm) above maximum", whPerKm <= 300.0)
        }
    }

    @Test fun `tyre pressures are in plausible bar range`() {
        CarCatalog.allCars.forEach { car ->
            assertTrue("${car.id} front pressure too low",  car.frontTyrePressureBar >= 2.0)
            assertTrue("${car.id} front pressure too high", car.frontTyrePressureBar <= 3.5)
            assertTrue("${car.id} rear pressure too low",   car.rearTyrePressureBar  >= 2.0)
            assertTrue("${car.id} rear pressure too high",  car.rearTyrePressureBar  <= 3.5)
        }
    }

    @Test fun `BEV reference consumption is higher than WLTP implied efficiency`() {
        // WLTP range is measured at ideal conditions — real-world reference consumption
        // is typically close to or higher than batteryKwh/wltpKm*100 (the theoretical
        // WLTP-implied value). Most BYD BEVs sit at 1.1x–1.4x; compact/efficient models
        // like the Dolphin Surf can have WLTP numbers that closely match real-world (~1.0x).
        // PHEVs are excluded: their WLTP EV range and gross battery capacity are not
        // directly comparable via this formula.
        CarCatalog.allCars.filter { !it.isPhev }.forEach { car ->
            val wltpImplied = car.batteryKwh / car.wltpKm * 100.0
            val ref = car.referenceConsumptionKwhPer100km
            val ratio = ref / wltpImplied
            assertTrue(
                "${car.id}: ref/implied ratio $ratio out of plausible 0.95–1.4 range",
                ratio in 0.95..1.4
            )
        }
    }

    // ── Drivetrain ────────────────────────────────────────────────────────────

    @Test fun `Seal Excellence is AWD`() {
        assertEquals(Drivetrain.AWD, CarCatalog.BYD_SEAL_EXCELLENCE.drivetrain)
    }

    @Test fun `Seal Dynamic and Premium are RWD`() {
        assertEquals(Drivetrain.RWD, CarCatalog.BYD_SEAL_DYNAMIC_RWD.drivetrain)
        assertEquals(Drivetrain.RWD, CarCatalog.BYD_SEAL_PREMIUM_RWD.drivetrain)
    }

    @Test fun `Dolphin,Atto 3 and Seal U are FWD`() {
        assertEquals(Drivetrain.FWD, CarCatalog.BYD_DOLPHIN_STANDARD.drivetrain)
        assertEquals(Drivetrain.FWD, CarCatalog.BYD_DOLPHIN_EXTENDED.drivetrain)
        assertEquals(Drivetrain.FWD, CarCatalog.BYD_ATTO_3.drivetrain)
        assertEquals(Drivetrain.FWD, CarCatalog.BYD_SEAL_U_COMFORT.drivetrain)
        assertEquals(Drivetrain.FWD, CarCatalog.BYD_SEAL_U_DESIGN.drivetrain)
    }

    @Test fun `catalog contains all 43 expected cars`() {
        assertEquals(43, CarCatalog.allCars.size)
    }

    @Test fun `both Sealion 7 AWD trims are dual-motor`() {
        listOf(CarCatalog.BYD_SEALION_7_DESIGN, CarCatalog.BYD_SEALION_7_PERFORMANCE).forEach { car ->
            assertEquals("${car.id} should be AWD", Drivetrain.AWD, car.drivetrain)
            assertNotNull("${car.id} AWD must declare a front motor", car.frontMotorRatedKw)
            assertNotNull("${car.id} AWD must declare a rear motor", car.rearMotorRatedKw)
        }
    }

    /** Every car offered in the picker must also be in allCars (fromId resolves against it). */
    @Test fun `grouped pickers only contain cars present in allCars`() {
        val all = CarCatalog.allCars.toSet()
        (CarCatalog.groupedBev.values + CarCatalog.groupedPhev.values).flatten().forEach { car ->
            assertTrue("${car.id} is in a picker group but missing from allCars", car in all)
        }
    }

    /** …and nothing in allCars is unreachable from the picker. */
    @Test fun `every car in allCars is reachable from a picker group`() {
        val grouped = (CarCatalog.groupedBev.values + CarCatalog.groupedPhev.values).flatten().toSet()
        CarCatalog.allCars.forEach { car ->
            assertTrue("${car.id} is in allCars but not in any picker group", car in grouped)
        }
    }

    @Test fun `Atto 3 pack sizes are exact blade-cell multiples`() {
        // BYD's blade cell is 0.48 kWh; 104S/126S/156S give the three Atto 3 packs.
        listOf(CarCatalog.BYD_ATTO_3_SR, CarCatalog.BYD_ATTO_3_PREMIUM).forEach { car ->
            assertEquals(
                "${car.id} batteryKwh should equal cellCount × 0.48",
                car.cellCount * 0.48, car.batteryKwh, 0.001
            )
        }
    }
}