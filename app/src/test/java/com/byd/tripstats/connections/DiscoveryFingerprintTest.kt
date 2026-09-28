package com.byd.tripstats.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the persisted discovery fingerprint has to notice, and what it has to ignore.
 *
 * It exists because a head unit that restarts the app several times a day was republishing all 49
 * retained configs on each restart — around 19.6 KB that Home Assistant already had.
 */
class DiscoveryFingerprintTest {

    private val scope = "broker.example:1883|mybyd"

    private val HOUR = 60L * 60 * 1000
    private val DAY = 24 * HOUR

    private val set = listOf(
        "homeassistant/sensor/mybyd/soc/config" to """{"name":"State of Charge","unit_of_measurement":"%"}""",
        "homeassistant/binary_sensor/mybyd/car_on/config" to """{"name":"Car On","device_class":"running"}""",
    )

    @Test fun anUnchangedSetOnAnUnchangedBrokerLooksUnchanged() {
        // The whole point: neither a reconnect nor an app restart may look like a change.
        assertEquals(
            MqttConnectionManager.fingerprintOf(scope, set),
            MqttConnectionManager.fingerprintOf(scope, set),
        )
    }

    @Test fun aDifferentBrokerHasNeverSeenThisSet() {
        // Point the app at another broker and it holds none of these retained topics, so an
        // identical payload set still has to be sent again.
        assertNotEquals(
            MqttConnectionManager.fingerprintOf(scope, set),
            MqttConnectionManager.fingerprintOf("other.example:1883|mybyd", set),
        )
    }

    @Test fun renamingTheCarIsAWholeNewSetOfTopics() {
        assertNotEquals(
            MqttConnectionManager.fingerprintOf(scope, set),
            MqttConnectionManager.fingerprintOf("broker.example:1883|thecar", set),
        )
    }

    @Test fun anEntityAddedByAnUpgradeIsNoticed() {
        // An upgrade that adds a sensor has to reach Home Assistant without anyone pressing anything.
        val grown = set + ("homeassistant/sensor/mybyd/cabin_temp/config" to """{"name":"Cabin Temperature"}""")
        assertNotEquals(
            MqttConnectionManager.fingerprintOf(scope, set),
            MqttConnectionManager.fingerprintOf(scope, grown),
        )
    }

    @Test fun aCorrectedPayloadOnAnUnchangedTopicIsNoticed() {
        // Same topics, one of them carrying a fixed unit or device class.
        val edited = listOf(set[0].first to """{"name":"State of Charge","unit_of_measurement":"percent"}""", set[1])
        assertNotEquals(
            MqttConnectionManager.fingerprintOf(scope, set),
            MqttConnectionManager.fingerprintOf(scope, edited),
        )
    }

    @Test fun aSetNeverSentIsStale() {
        assertTrue(MqttConnectionManager.discoveryIsStale(nowMs = DAY, publishedAtMs = 0L))
    }

    @Test fun aSetSentTodayIsNotRestated() {
        assertFalse(MqttConnectionManager.discoveryIsStale(nowMs = DAY + HOUR * 23, publishedAtMs = DAY))
    }

    @Test fun aSetOlderThanADayIsRestated() {
        // A broker that quietly lost its retained messages gets them back without anyone noticing
        // the entities had gone — one burst a day, against the eight a day the gate exists to stop.
        assertTrue(MqttConnectionManager.discoveryIsStale(nowMs = DAY + HOUR * 25, publishedAtMs = DAY))
    }

    @Test fun aTimestampFromTheFutureIsStale() {
        // Head units boot with the wrong clock. A stored time that is somehow ahead of now must not
        // switch the refresh off forever.
        assertTrue(MqttConnectionManager.discoveryIsStale(nowMs = DAY, publishedAtMs = DAY + HOUR))
    }

    @Test fun textMovedAcrossTheTopicPayloadBoundaryIsNoticed() {
        // A digest fed one flat run of bytes cannot tell "ab" + "c" from "a" + "bc", which would
        // quietly accept a changed set. The fields are separated so it can.
        assertNotEquals(
            MqttConnectionManager.fingerprintOf(scope, listOf("ab" to "c")),
            MqttConnectionManager.fingerprintOf(scope, listOf("a" to "bc")),
        )
    }
}
