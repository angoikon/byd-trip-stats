package com.byd.tripstats.connections

import com.byd.tripstats.data.config.CarCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which car gets a fuel gauge, and what happens to the gauges a BEV used to advertise.
 *
 * Every BYD Trip Stats install has published `fuel_percentage` and `fuel_driving_range_km` since
 * the discovery set existed, so on a BEV Home Assistant has always held two entities reading 0.
 * Dropping them from the set is only half the job: the configs are retained, so they also have to
 * be deleted from the broker.
 */
class FuelSensorsTest {

    private val bev = CarCatalog.BYD_SEAL_EXCELLENCE
    private val phev = CarCatalog.BYD_SHARK_6_AWD

    @Test fun theCatalogueStillAgreesWithWhatTheseTestsAssume() {
        // Everything below is meaningless if these two stop being what they are.
        assertFalse(bev.isPhev)
        assertTrue(phev.isPhev)
    }

    @Test fun aCarWithATankGetsBothGauges() {
        assertTrue(MqttConnectionManager.isPhevCar(phev))
        for (id in MqttConnectionManager.FUEL_SENSOR_IDS) {
            assertTrue(id, MqttConnectionManager.advertisesSensor(id, isPhev = true))
        }
    }

    @Test fun aCarWithoutOneGetsNeither() {
        assertFalse(MqttConnectionManager.isPhevCar(bev))
        for (id in MqttConnectionManager.FUEL_SENSOR_IDS) {
            assertFalse(id, MqttConnectionManager.advertisesSensor(id, isPhev = false))
        }
    }

    @Test fun aCarNobodyHasChosenYetIsReadAsABev() {
        // Advertising a tank that turns out not to exist is the harder mistake to undo: the entity
        // has to be deleted from the broker afterwards, which needs a connection it may not have.
        assertFalse(MqttConnectionManager.isPhevCar(null))
    }

    @Test fun everyOtherEntityIsLeftAlone() {
        for (id in listOf("soc", "odometer", "energy_mode", "charging_power", "car_on")) {
            assertTrue(id, MqttConnectionManager.advertisesSensor(id, isPhev = false))
            assertTrue(id, MqttConnectionManager.advertisesSensor(id, isPhev = true))
        }
    }

    @Test fun aBevCarriesADeletionForEachGaugeItUsedToAdvertise() {
        // The topics are spelled out rather than rebuilt, so that changing how a config topic is
        // named in buildDiscoveryMessages without changing it here fails here instead of quietly
        // deleting nothing.
        assertEquals(
            listOf(
                "homeassistant/sensor/mybyd/fuel_percentage/config" to "",
                "homeassistant/sensor/mybyd/fuel_driving_range_km/config" to "",
            ),
            MqttConnectionManager.retiredFuelTopics("mybyd", isPhev = false),
        )
    }

    @Test fun aDeletionIsAnEmptyPayload() {
        // Empty is what tells the broker to drop the retained message. Anything else, including a
        // JSON null or "{}", leaves the entity in place.
        for ((_, payload) in MqttConnectionManager.retiredFuelTopics("mybyd", isPhev = false)) {
            assertEquals("", payload)
        }
    }

    @Test fun aPhevDeletesNothing() {
        assertEquals(emptyList<Pair<String, String>>(), MqttConnectionManager.retiredFuelTopics("mybyd", isPhev = true))
    }

    @Test fun switchingTheSelectedCarIsANewReconciliation() {
        // The manager outlives a car change: the service only restarts its telemetry loop, so the
        // in-memory "already done this" key is what has to notice. Without the car type in it, a
        // PHEV switched to a BEV keeps both fuel entities advertised, and unfed, until the daily
        // refresh a day later.
        val scope = "broker.example:1883|mybyd"
        assertNotEquals(
            MqttConnectionManager.discoveryCheckKey(scope, isPhev = true),
            MqttConnectionManager.discoveryCheckKey(scope, isPhev = false),
        )
    }

    @Test fun theSameCarOnTheSameBrokerIsNotReconciledTwice() {
        val scope = "broker.example:1883|mybyd"
        assertEquals(
            MqttConnectionManager.discoveryCheckKey(scope, isPhev = false),
            MqttConnectionManager.discoveryCheckKey(scope, isPhev = false),
        )
    }

    @Test fun aPhevsDiscoverySetIsUnchangedByThisWork() {
        // A PHEV owner's broker already holds exactly this set, so the fingerprint must not move
        // and nothing should be republished on upgrade.
        val before = MqttConnectionManager.fingerprintOf(
            "broker.example:1883|mybyd",
            listOf("homeassistant/sensor/mybyd/fuel_percentage/config" to """{"name":"Fuel Percentage"}"""),
        )
        val after = MqttConnectionManager.fingerprintOf(
            "broker.example:1883|mybyd",
            listOf("homeassistant/sensor/mybyd/fuel_percentage/config" to """{"name":"Fuel Percentage"}""") +
                MqttConnectionManager.retiredFuelTopics("mybyd", isPhev = true),
        )
        assertEquals(before, after)
    }
}
