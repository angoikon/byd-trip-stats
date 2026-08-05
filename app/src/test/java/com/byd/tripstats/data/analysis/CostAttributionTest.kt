package com.byd.tripstats.data.analysis

import com.byd.tripstats.data.local.entity.ChargingSessionEntity
import com.byd.tripstats.data.local.entity.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CostAttributionTest {

    private fun trip(
        id: Long,
        start: Long,
        energy: Double? = null,
        startOdo: Double = 0.0,
        endOdo: Double? = null,
        endTime: Long? = null,
    ) = TripEntity(
        id = id,
        startTime = start,
        startOdometer = startOdo,
        startSoc = 50.0,
        startTotalDischarge = 0.0,
        endTotalDischarge = energy,   // energyConsumed = end - start = energy
        endOdometer = endOdo,
        endTime = endTime,
        isActive = false,
    )

    private fun charge(
        id: Long,
        start: Long,
        kwh: Double? = null,
        price: Double? = null,
        startOdo: Double? = null,
        active: Boolean = false,
        soc: Double = 20.0,
        // 0.0 (the entity default) disables SOC reconciliation, preserving the
        // plain-FIFO behaviour the older tests pin down.
        capacity: Double = 0.0,
    ) = ChargingSessionEntity(
        id = id,
        startTime = start,
        socStart = soc,
        kwhAdded = kwh,
        startOdometer = startOdo,
        pricePerKwh = price,
        isActive = active,
        batteryKwh = capacity,
    )

    // ── blendedTripRates (FIFO cost basis) ────────────────────────────────────

    @Test fun fifoSpendsOldestCheapEnergyFirst() {
        // 36 kWh @0.135 then 18 kWh @0.40; a 25 kWh trip draws only the cheap €0.135 lot.
        val sessions = listOf(
            charge(1, start = 100, kwh = 36.0, price = 0.135),
            charge(2, start = 200, kwh = 18.0, price = 0.40),
        )
        val trips = listOf(trip(10, start = 300, energy = 25.0))
        assertEquals(0.135, CostAttribution.blendedTripRates(trips, sessions, tariff = 0.30)[10]!!, 1e-9)
    }

    @Test fun fifoSpillsIntoNewerLotOnceOlderRunsOut() {
        // Same charges; a 40 kWh trip empties the 36 kWh @0.135 lot then takes 4 kWh @0.40.
        val sessions = listOf(
            charge(1, start = 100, kwh = 36.0, price = 0.135),
            charge(2, start = 200, kwh = 18.0, price = 0.40),
        )
        val trips = listOf(trip(10, start = 300, energy = 40.0))
        val expected = (36 * 0.135 + 4 * 0.40) / 40.0  // 6.46 / 40 = 0.1615
        assertEquals(expected, CostAttribution.blendedTripRates(trips, sessions, tariff = 0.30)[10]!!, 1e-9)
    }

    @Test fun singleChargeInheritsItsExactPrice() {
        val sessions = listOf(charge(1, start = 100, kwh = 30.0, price = 0.30))
        val trips = listOf(trip(10, start = 200, energy = 10.0))
        assertEquals(0.30, CostAttribution.blendedTripRates(trips, sessions, tariff = 0.50)[10]!!, 1e-9)
    }

    @Test fun freeChargeMakesTripFree() {
        val sessions = listOf(charge(1, start = 100, kwh = 20.0, price = 0.0))
        val trips = listOf(trip(10, start = 200, energy = 5.0))
        assertEquals(0.0, CostAttribution.blendedTripRates(trips, sessions, tariff = 0.30)[10]!!, 1e-9)
    }

    @Test fun chargeWithoutPriceUsesGlobalTariff() {
        val sessions = listOf(charge(1, start = 100, kwh = 20.0, price = null))
        val trips = listOf(trip(10, start = 200, energy = 5.0))
        assertEquals(0.30, CostAttribution.blendedTripRates(trips, sessions, tariff = 0.30)[10]!!, 1e-9)
    }

    @Test fun fifoCarriesRemainingLotsAcrossTrips() {
        // A(10@0.10); trip1 spends 5 @0.10 leaving 5. Then B(10@0.30). trip2 spends 10:
        // the leftover 5 @0.10 (oldest) then 5 @0.30 → 0.5 + 1.5 = 2.0 over 10 kWh = €0.20/kWh.
        val sessions = listOf(
            charge(1, start = 100, kwh = 10.0, price = 0.10),
            charge(2, start = 200, kwh = 10.0, price = 0.30),
        )
        val trips = listOf(
            trip(10, start = 150, energy = 5.0),
            trip(20, start = 250, energy = 10.0),
        )
        val rates = CostAttribution.blendedTripRates(trips, sessions, tariff = 0.0)
        assertEquals(0.10, rates[10]!!, 1e-9)
        assertEquals(0.20, rates[20]!!, 1e-9)
    }

    @Test fun midTripChargeFuelsTheRestOfTheSameTrip() {
        // Single long trip (100→400) using 50 kWh, with a mid-trip DC charge at t=200.
        // Pre-trip A(30@0.10) + mid-trip D(30@0.40) are both queued before the trip (keyed at end).
        // FIFO: 30 @0.10 (3.0) + 20 @0.40 (8.0) = 11.0 over 50 kWh = €0.22/kWh.
        val sessions = listOf(
            charge(1, start = 50, kwh = 30.0, price = 0.10),
            charge(2, start = 200, kwh = 30.0, price = 0.40),   // starts during the trip
        )
        val trips = listOf(trip(10, start = 100, endTime = 400, energy = 50.0))
        assertEquals(0.22, CostAttribution.blendedTripRates(trips, sessions, tariff = 0.30)[10]!!, 1e-9)
    }

    @Test fun energyBeforeAnyChargeIsPricedAtTariff() {
        // A trip before any recorded charge draws entirely from the (unknown) pre-existing charge.
        val trips = listOf(trip(10, start = 100, energy = 10.0))
        assertEquals(0.25, CostAttribution.blendedTripRates(trips, emptyList(), tariff = 0.25)[10]!!, 1e-9)
    }

    @Test fun partialDeficitBlendsLotsAndTariff() {
        // Lot holds 4 kWh @0.10; a 10 kWh trip takes 4 from it + 6 deficit @tariff 0.20.
        val sessions = listOf(charge(1, start = 100, kwh = 4.0, price = 0.10))
        val trips = listOf(trip(10, start = 200, energy = 10.0))
        val rate = CostAttribution.blendedTripRates(trips, sessions, tariff = 0.20)[10]!!
        assertEquals((4 * 0.10 + 6 * 0.20) / 10.0, rate, 1e-9)  // 1.6/10 = 0.16
    }

    @Test fun unresolvableWithoutTariffOrAnyPrice() {
        val trips = listOf(trip(10, start = 100, energy = 10.0))
        assertNull(CostAttribution.blendedTripRates(trips, emptyList(), tariff = 0.0)[10])
    }

    @Test fun activeChargeIsExcludedFromTheQueue() {
        val sessions = listOf(charge(1, start = 100, kwh = 20.0, price = 0.10, active = true))
        val trips = listOf(trip(10, start = 200, energy = 5.0))
        // The only charge is still in progress → trip falls back to tariff.
        assertEquals(0.30, CostAttribution.blendedTripRates(trips, sessions, tariff = 0.30)[10]!!, 1e-9)
    }

    // ── SOC reconciliation at charge events ──────────────────────────────────

    @Test fun lowSocChargeEvictsStaleLotsSoNewPriceReachesTheNextTrip() {
        // The reported bug: the ledger only drains through recorded trips, so unrecorded
        // consumption (HVAC, idle) leaves stale surplus that shadows every new charge.
        // Charge 1 fills 30 kWh @0.10; trip 1 records only 10. The pack then physically
        // drains to 10% unrecorded. Charge 2 (10% × 30 kWh = 3 kWh trim target) must
        // evict the 17 kWh of phantom energy so its own price reaches the next trip.
        val sessions = listOf(
            charge(1, start = 100, kwh = 30.0, price = 0.10),
            charge(2, start = 300, kwh = 27.0, price = 0.40, soc = 10.0, capacity = 30.0),
        )
        val trips = listOf(
            trip(10, start = 150, endTime = 200, energy = 10.0),
            trip(20, start = 400, endTime = 450, energy = 10.0),
        )
        val rates = CostAttribution.blendedTripRates(trips, sessions, tariff = 0.10)
        assertEquals(0.10, rates[10]!!, 1e-9)
        // 3 kWh survive @0.10, the remaining 7 come from the new @0.40 lot.
        assertEquals((3 * 0.10 + 7 * 0.40) / 10.0, rates[20]!!, 1e-9)  // 0.31
    }

    @Test fun nearEmptyPlugInAttributesTheNextTripAlmostEntirelyToTheNewCharge() {
        // Months of drift left 40 kWh of tariff-priced phantom in the queue. An
        // 8.3% → 100% charge proves the pack held only 2.49 kWh — after eviction the
        // post-charge trip prices essentially at the newly set charge price.
        val sessions = listOf(
            charge(1, start = 100, kwh = 40.0, price = null),                       // tariff lot
            charge(2, start = 300, kwh = 27.0, price = 0.50, soc = 8.3, capacity = 30.0),
        )
        val trips = listOf(trip(10, start = 400, endTime = 450, energy = 20.0))
        val rate = CostAttribution.blendedTripRates(trips, sessions, tariff = 0.135)[10]!!
        assertEquals((2.49 * 0.135 + 17.51 * 0.50) / 20.0, rate, 1e-9)  // ≈ 0.4546
    }

    @Test fun reconciliationEvictsOldestFirstShrinkingLotsPartially() {
        // Queue [5 @0.10, 10 @0.20]; trim target 12 kWh → the oldest lot is cut by 3,
        // the newer lot untouched. The next trip draws 2 @0.10 + 2 @0.20.
        val sessions = listOf(
            charge(1, start = 100, kwh = 5.0, price = 0.10),
            charge(2, start = 200, kwh = 10.0, price = 0.20),
            charge(3, start = 300, kwh = 10.0, price = 0.40, soc = 40.0, capacity = 30.0),
        )
        val trips = listOf(trip(10, start = 400, endTime = 450, energy = 4.0))
        val rate = CostAttribution.blendedTripRates(trips, sessions, tariff = 0.0)[10]!!
        assertEquals((2 * 0.10 + 2 * 0.20) / 4.0, rate, 1e-9)  // 0.15
    }

    @Test fun highSocChargeLeavesTheQueueIntact() {
        // Trim target above the queued total → nothing is evicted (plain FIFO).
        val sessions = listOf(
            charge(1, start = 100, kwh = 10.0, price = 0.10),
            charge(2, start = 200, kwh = 5.0, price = 0.40, soc = 80.0, capacity = 30.0),
        )
        val trips = listOf(trip(10, start = 300, endTime = 350, energy = 10.0))
        assertEquals(0.10, CostAttribution.blendedTripRates(trips, sessions, tariff = 0.0)[10]!!, 1e-9)
    }

    @Test fun staleZeroSocOrMissingCapacitySkipsReconciliation() {
        // A stale-0 SOC reading or a legacy row without the capacity snapshot must
        // not wipe the queue — both fall back to plain FIFO.
        val zeroSoc = listOf(
            charge(1, start = 100, kwh = 36.0, price = 0.135),
            charge(2, start = 200, kwh = 5.0, price = 0.40, soc = 0.0, capacity = 30.0),
        )
        val noCapacity = listOf(
            charge(1, start = 100, kwh = 36.0, price = 0.135),
            charge(2, start = 200, kwh = 5.0, price = 0.40, soc = 50.0, capacity = 0.0),
        )
        val trips = listOf(trip(10, start = 300, endTime = 350, energy = 10.0))
        assertEquals(0.135, CostAttribution.blendedTripRates(trips, zeroSoc, tariff = 0.0)[10]!!, 1e-9)
        assertEquals(0.135, CostAttribution.blendedTripRates(trips, noCapacity, tariff = 0.0)[10]!!, 1e-9)
    }

    // ── distancesSincePreviousCharge ──────────────────────────────────────────

    @Test fun distanceIsOdometerDeltaBetweenCharges() {
        val sessions = listOf(
            charge(1, start = 100, startOdo = 1000.0),
            charge(2, start = 200, startOdo = 1150.0),
            charge(3, start = 300, startOdo = 1150.0), // top-up, no driving
        )
        val d = CostAttribution.distancesSincePreviousCharge(sessions, emptyList())
        assertNull(d[1])              // first charge has no predecessor
        assertEquals(150.0, d[2]!!, 1e-9)
        assertEquals(0.0, d[3]!!, 1e-9)
    }

    @Test fun legacyChargeFallsBackToTripOdometer() {
        val sessions = listOf(
            charge(1, start = 100, startOdo = 1000.0),
            charge(2, start = 300, startOdo = null),
        )
        val trips = listOf(
            trip(50, start = 150, endOdo = 1200.0, endTime = 250),
        )
        val d = CostAttribution.distancesSincePreviousCharge(sessions, trips)
        assertEquals(200.0, d[2]!!, 1e-9)  // 1200 (trip end) − 1000 (charge 1)
    }

    @Test fun negativeDeltaYieldsNull() {
        val sessions = listOf(
            charge(1, start = 100, startOdo = 2000.0),
            charge(2, start = 200, startOdo = 1500.0), // odometer went backwards → glitch
        )
        val d = CostAttribution.distancesSincePreviousCharge(sessions, emptyList())
        assertNull(d[2])
    }

    @Test fun activeSessionsAreExcludedFromDistances() {
        val sessions = listOf(
            charge(1, start = 100, startOdo = 1000.0),
            charge(2, start = 200, startOdo = 1100.0, active = true),
        )
        val d = CostAttribution.distancesSincePreviousCharge(sessions, emptyList())
        assertNull(d[2])              // active session not keyed
        assertEquals(1, d.size)
    }
}
