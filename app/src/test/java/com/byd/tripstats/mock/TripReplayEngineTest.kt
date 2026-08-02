package com.byd.tripstats.mock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TripReplayEngineTest {

    private val exportJson = """
        {
          "tripId": 7,
          "startTime": 1000,
          "endTime": 61000,
          "distance": 1.2,
          "dataPoints": [
            {
              "timestamp": 1000,
              "latitude": 37.98, "longitude": 23.72, "altitude": 50.0,
              "speed": 42.5, "power": 12.0, "soc": 61.4,
              "odometer": 12345.6, "batteryTemp": 24.0, "totalDischarge": 4000.5,
              "gear": "D", "isRegenerating": false,
              "engineSpeedFront": 3000, "engineSpeedRear": 0,
              "electricDrivingRangeKm": 55,
              "tyrePressureLF": 38.5, "tyrePressureRF": 38.5,
              "tyrePressureLR": 42.0, "tyrePressureRR": 42.0,
              "soh": 98, "batteryTotalVoltage": 570, "battery12vVoltage": 13.2,
              "batteryCellVoltageMax": 3.33, "batteryCellVoltageMin": 3.31,
              "socPanel": 61,
              "tyreTempLF": 25, "tyreTempRF": 25, "tyreTempLR": 24, "tyreTempRR": 24,
              "rawJson": {"energy_mode":3,"instant_fuel_consumption":5.5,"total_fuel_consumption":100.2}
            },
            {
              "timestamp": 61000,
              "latitude": 37.99, "longitude": 23.73, "altitude": 51.0,
              "speed": 50.0, "power": 15.0, "soc": 61.0,
              "odometer": 12346.8, "batteryTemp": 24.0, "totalDischarge": 4000.9,
              "gear": "D", "isRegenerating": false,
              "engineSpeedFront": 3500, "engineSpeedRear": 0,
              "electricDrivingRangeKm": 54,
              "tyrePressureLF": 38.5, "tyrePressureRF": 38.5,
              "tyrePressureLR": 42.0, "tyrePressureRR": 42.0,
              "soh": 98, "batteryTotalVoltage": 570, "battery12vVoltage": 13.2,
              "batteryCellVoltageMax": 3.33, "batteryCellVoltageMin": 3.31,
              "socPanel": 61,
              "tyreTempLF": 25, "tyreTempRF": 25, "tyreTempLR": 24, "tyreTempRR": 24,
              "rawJson": "{}"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesExportedTripIntoTelemetry() {
        val points = TripReplayEngine.parse(exportJson)

        assertEquals(2, points.size)
        val first = points[0].telemetry
        assertEquals(1000L, points[0].originalMs)
        assertEquals(42.5, first.speed, 0.0001)
        assertEquals("D", first.gear)
        assertEquals(61.4, first.soc, 0.0001)
        assertEquals(12345.6, first.odometer, 0.0001)
        assertEquals(4000.5, first.totalDischarge, 0.0001)
        assertEquals(12, first.enginePower)
        assertEquals(37.98, first.locationLatitude, 0.0001)
        assertEquals(55, first.electricDrivingRangeKm)
        assertEquals(61, first.socPanel)
        // rawJson escape-hatch fields (incl. the PHEV signals) survive the round trip
        assertEquals(3, first.energyMode)
        assertEquals(5.5, first.instantFuelConsumption!!, 0.0001)
        assertEquals(100.2, first.totalFuelConsumption!!, 0.0001)
        // batteryTemp column feeds both cell extremes so batteryTempAvg reproduces it
        assertEquals(24.0, first.batteryTempAvg, 0.0001)
        // A trip data point implies a powered car when rawJson doesn't say otherwise
        assertEquals(2, first.carOn)
        // rawJson given as an embedded string works too
        assertEquals("D", points[1].telemetry.gear)
    }

    @Test
    fun rejectsNonTripJson() {
        assertThrows(IllegalArgumentException::class.java) {
            TripReplayEngine.parse("""{"hello":"world"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TripReplayEngine.parse("not json at all")
        }
    }
}
