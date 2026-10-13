package com.byd.tripstats.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.byd.tripstats.data.config.CarCatalog
import com.byd.tripstats.data.local.BydStatsDatabase
import com.byd.tripstats.data.local.TestDatabaseRule
import org.junit.Rule
import com.byd.tripstats.data.local.entity.ChargingDataPointEntity
import com.byd.tripstats.data.local.entity.ChargingSessionEntity
import com.byd.tripstats.mock.MockDataGenerator
import com.byd.tripstats.test.telemetry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for ChargingRepository (hybrid approach).
 *
 * Two recording modes:
 *   REAL-TIME  — car is ON (carOn=1), chargingPower>0 → live session + data points
 *   SYNTHETIC  — car was OFF during charge → SoC-delta reconstruction on next wake-up
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ChargingRepositoryTest {

    private lateinit var db  : BydStatsDatabase
    private lateinit var repo: ChargingRepository
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val car = CarCatalog.BYD_SEAL_EXCELLENCE   // 82.5 kWh
    private val gen = MockDataGenerator()
    private val SETTLE = 400L

    private val PREFS_NAME  = "charging_shutdown_state"
    private val KEY_SOC     = "last_soc"
    private val KEY_TS      = "last_timestamp"
    private val KEY_TEMP    = "last_temp_avg"
    private val KEY_VOLTAGE = "last_voltage"

    @get:Rule val dbRule = TestDatabaseRule()

    @Before fun setUp() {
        // dbRule already injected the in-memory DB into BydStatsDatabase.INSTANCE
        db = dbRule.db
        ChargingRepository::class.java.getDeclaredField("INSTANCE")
            .also { it.isAccessible = true }.set(null, null)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        repo = ChargingRepository.getInstance(context)
        Thread.sleep(SETTLE)
        gen.reset()
    }

    @After fun tearDown() {
        repo.close()
        ChargingRepository::class.java.getDeclaredField("INSTANCE")
            .also { it.isAccessible = true }.set(null, null)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        // BydStatsDatabase INSTANCE reset and db.close() handled by TestDatabaseRule.after()
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test fun initiallyNotCharging() {
        assertFalse(repo.isCharging.value)
        assertNull(repo.activeSessionId.value)
    }

    @Test fun initiallyNoSessions() = runBlocking {
        assertTrue(repo.getAllSessions().first().isEmpty())
    }

    // ── Real-time: car ON ─────────────────────────────────────────────────────

    @Test fun carOnChargingPacketOpensRealTimeSession() = runBlocking {
        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 1, chargingPower = 7.2), car)
        Thread.sleep(SETTLE)
        assertTrue("Should be charging", repo.isCharging.value)
        assertNotNull("Active session ID should be set", repo.activeSessionId.value)
    }

    @Test fun realTimeSessionStoresCorrectStartSoC() = runBlocking {
        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 1, soc = 45.0), car)
        Thread.sleep(SETTLE)
        val session = repo.getSessionById(repo.activeSessionId.value!!)
        assertEquals(45.0, session!!.socStart, 0.1)
    }

    @Test fun realTimeSessionIsMarkedActive() = runBlocking {
        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 1), car)
        Thread.sleep(SETTLE)
        assertTrue(repo.getAllSessions().first().first().isActive)
    }

    @Test fun realTimeSessionRecordsDataPoints() = runBlocking {
        repeat(3) {
            repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 1), car)
            Thread.sleep(50)
        }
        Thread.sleep(SETTLE)
        val id = repo.activeSessionId.value!!
        assertTrue(repo.getDataPointsForSessionSync(id).isNotEmpty())
    }

    @Test fun peakKwUpdatedDuringRealTimeSession() = runBlocking {
        repo.onTelemetry(gen.generateDcChargingTelemetry(carOn = 1, chargingPower = 50.0), car)
        Thread.sleep(SETTLE)
        repo.onTelemetry(gen.generateDcChargingTelemetry(carOn = 1, chargingPower = 80.0), car)
        Thread.sleep(SETTLE)
        val session = repo.getSessionById(repo.activeSessionId.value!!)
        assertEquals(80.0, session!!.peakKw, 0.1)
    }

    @Test fun multipleConsecutivePacketsResultInOneSession() = runBlocking {
        repeat(5) {
            repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 1), car)
            Thread.sleep(50)
        }
        Thread.sleep(SETTLE)
        assertEquals(1, repo.getAllSessions().first().size)
    }

    // ── Real-time: closing on non-charging packet ─────────────────────────────

    @Test fun nonChargingPacketWithCarOnClosesSession() = runBlocking {
        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 1), car)
        Thread.sleep(SETTLE)
        assertNotNull(repo.activeSessionId.value)

        // Non-charging packet while car still on
        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 1, chargingPower = 0.0), car)
        Thread.sleep(SETTLE)

        assertNull("Session should be closed", repo.activeSessionId.value)
        assertFalse(repo.isCharging.value)
        // The session ran for ~400ms with zero SoC delta, so the micro-session guard
        // deletes it rather than persisting it. State reset is the key invariant here.
        assertTrue("Micro-session should be cleaned up", repo.getAllSessions().first().isEmpty())
    }

    @Test fun carOffPacketKeepsSessionOpenWhenStillCharging() = runBlocking {
        // Car-off while still charging = session stays open and continues recording.
        // The process survives ACC_OFF so real-time data collection continues.
        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 1), car)
        Thread.sleep(SETTLE)
        assertNotNull(repo.activeSessionId.value)

        // Car turns off but charging power is still > 0
        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 0), car)
        Thread.sleep(SETTLE)

        // Session should remain open — car-off + charging = keep recording
        assertNotNull("Session should remain open while charging continues after car-off", repo.activeSessionId.value)
        assertTrue(repo.isCharging.value)
    }

    @Test fun carOffWithNoChargingClosesSession() = runBlocking {
        // Car-off + no charging = session closes
        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 1), car)
        Thread.sleep(SETTLE)
        assertNotNull(repo.activeSessionId.value)

        // Car turns off and charging stops
        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 0, chargingPower = 0.0), car)
        Thread.sleep(SETTLE)

        assertNull("Session should close when car-off and not charging", repo.activeSessionId.value)
        assertFalse(repo.isCharging.value)
    }

    // ── SoC-delta reconstruction (car-off charging) ───────────────────────────

    @Test fun socIncreaseOnCarWakeCreatessyntheticSession() = runBlocking {
        writeShutdownState(soc = 50.0f, tsOffsetMs = -3_600_000L)

        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 1, soc = 75.0, chargingPower = 0.0), car)
        Thread.sleep(SETTLE)

        val sessions = repo.getAllSessions().first()
        assertEquals(1, sessions.size)
        val session = sessions.first()
        assertFalse(session.isActive)
        assertEquals(0.0, session.peakKw, 0.0)   // synthetic — no live data
        assertEquals(0.0, session.avgKw, 0.0)
        assertEquals(50.0, session.socStart, 0.1)
        assertEquals(75.0, session.socEnd!!, 0.1)
    }

    @Test fun syntheticSessionKwhIsCorrect() = runBlocking {
        writeShutdownState(soc = 40.0f, tsOffsetMs = -7_200_000L)
        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 1, soc = 80.0, chargingPower = 0.0), car)
        Thread.sleep(SETTLE)
        // 40% × 82.5 kWh = 33.0 kWh
        assertEquals(33.0, repo.getAllSessions().first().first().kwhAdded!!, 0.5)
    }

    @Test fun socBelowThresholdOnWakeCreatesNoSyntheticSession() = runBlocking {
        writeShutdownState(soc = 79.5f, tsOffsetMs = -600_000L)  // only 0.5% increase
        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 1, soc = 80.0, chargingPower = 0.0), car)
        Thread.sleep(SETTLE)
        assertTrue(repo.getAllSessions().first().isEmpty())
    }

    @Test fun firstEverRunWithNoSavedStateCreatesNoSyntheticSession() = runBlocking {
        // No SharedPreferences written
        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 1, soc = 80.0, chargingPower = 0.0), car)
        Thread.sleep(SETTLE)
        assertTrue(repo.getAllSessions().first().isEmpty())
    }

    @Test fun reconstructionRunsOnlyOncePerServiceLifecycle() = runBlocking {
        writeShutdownState(soc = 50.0f, tsOffsetMs = -3_600_000L)

        // Two carOn packets — reconstruction only on the first
        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 1, soc = 75.0, chargingPower = 0.0), car)
        Thread.sleep(SETTLE)
        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 1, soc = 90.0, chargingPower = 0.0), car)
        Thread.sleep(SETTLE)

        assertEquals(1, repo.getAllSessions().first().filter { !it.isActive }.size)
    }

    @Test fun duplicateGuardPreventsDoubleReconstruction() = runBlocking {
        val shutdownTs = System.currentTimeMillis() - 3_600_000L
        writeShutdownState(soc = 50.0f, tsOffsetMs = -3_600_000L)

        db.chargingSessionDao().insertSession(
            ChargingSessionEntity(
                startTime = shutdownTs + 60_000L,
                socStart  = 50.0, batteryKwh = 82.5,
                carConfigId = "BYD_SEAL_EXCELLENCE",
                isActive = false, socEnd = 75.0, kwhAdded = 20.6
            )
        )
        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 1, soc = 75.0, chargingPower = 0.0), car)
        Thread.sleep(SETTLE)
        assertEquals(1, repo.getAllSessions().first().size)
    }

    // ── SoC persistence ───────────────────────────────────────────────────────

    @Test fun carOffPacketsDoNotOverwriteShutdownBaseline() = runBlocking {
        // Baseline captured while the car was last on (e.g. 60 %).
        writeShutdownState(soc = 60.0f, tsOffsetMs = -60_000L)

        // An off-state packet arrives showing a higher SoC (mid-charge). The
        // baseline must remain unchanged so the next wake-up can compute the
        // correct delta and reconstruct the session.
        repo.onTelemetry(
            telemetry(
                gear = "P", speed = 0.0, enginePower = 0,
                chargingPower = 0.0, carOn = 0, soc = 72.0, battery12vVoltage = 12.4
            ),
            car
        )
        assertEquals(60.0f, repoPrefs().getFloat(KEY_SOC, -1f), 0.1f)
    }

    @Test fun shutdownStatePersistsOnCarOnPackets() = runBlocking {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        // On the first carOn packet, persistShutdownState runs inside the reconstruction
        // coroutine (after the reconstruction check completes), so we must wait for it.
        repo.onTelemetry(
            telemetry(
                gear = "P", speed = 0.0, enginePower = 0,
                chargingPower = 0.0, carOn = 1, soc = 72.0
            ),
            car
        )
        Thread.sleep(SETTLE)
        assertEquals(72.0f, repoPrefs().getFloat(KEY_SOC, -1f), 0.1f)
    }

    // ── Deletion ──────────────────────────────────────────────────────────────

    @Test fun deleteSessionRemovesFromDatabase() = runBlocking {
        writeShutdownState(soc = 50.0f, tsOffsetMs = -3_600_000L)
        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 1, soc = 75.0, chargingPower = 0.0), car)
        Thread.sleep(SETTLE)
        val id = repo.getAllSessions().first().first().id
        repo.deleteSession(id)
        Thread.sleep(SETTLE)
        assertTrue(repo.getAllSessions().first().isEmpty())
    }

    // ── Delivered energy ──────────────────────────────────────────────────────

    /**
     * A charge the app was killed through, finalised at the next start. The pack figure comes
     * from the SoC delta and the received figure from the samples, and the second has to be
     * the larger: more arrives than the pack keeps, and the difference went to heat.
     */
    @Test fun aRecoveredSessionStoresTheEnergyItMeasuredArriving() = runBlocking {
        val lastSeen = System.currentTimeMillis() - 90 * 60_000L   // well past the 15 min resume window
        val started  = lastSeen - 33 * 60_000L
        val id = db.chargingSessionDao().insertSession(
            ChargingSessionEntity(
                startTime = started,
                socStart = 49.0, socStartPanel = 49.0,
                batteryKwh = 82.5, carConfigId = "BYD_SEAL_EXCELLENCE",
                isActive = true,
            )
        )
        // 33 minutes at a steady 50 kW: 27.5 kWh measured arriving.
        db.chargingSessionDao().insertDataPoints(
            (0..66).map { i ->
                ChargingDataPointEntity(
                    sessionId = id,
                    timestamp = started + i * 30_000L,
                    soc = 49.0 + i * (31.0 / 66.0),
                    socPanel = (49.0 + i * (31.0 / 66.0)).toInt(),
                    chargingPower = 50.0,
                )
            }
        )

        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 1, soc = 80.0, chargingPower = 0.0), car)
        Thread.sleep(SETTLE)

        val closed = repo.getSessionById(id)!!
        assertFalse("orphan recovery should have closed it", closed.isActive)
        // 31% of 82.5 kWh reached the pack.
        assertEquals(25.57, closed.kwhAdded!!, 0.05)
        assertEquals(27.5, closed.energyReceivedKwh!!, 0.05)
        assertTrue(
            "more must have arrived than the pack kept",
            closed.energyReceivedKwh!! > closed.kwhAdded!!,
        )
    }

    /**
     * The same charge, sampled by a car whose SDK never binds the charging listener. Those
     * sessions read a flat zero all the way through, including through DC charges that filled
     * the pack, so a figure here would report a real charge as having drawn nothing.
     */
    @Test fun aSessionWhosePowerNeverReportedStoresNoDeliveredEnergy() = runBlocking {
        val lastSeen = System.currentTimeMillis() - 90 * 60_000L
        val started  = lastSeen - 33 * 60_000L
        val id = db.chargingSessionDao().insertSession(
            ChargingSessionEntity(
                startTime = started,
                socStart = 49.0, socStartPanel = 49.0,
                batteryKwh = 82.5, carConfigId = "BYD_SEAL_EXCELLENCE",
                isActive = true,
            )
        )
        db.chargingSessionDao().insertDataPoints(
            (0..66).map { i ->
                ChargingDataPointEntity(
                    sessionId = id,
                    timestamp = started + i * 30_000L,
                    soc = 49.0 + i * (31.0 / 66.0),
                    socPanel = (49.0 + i * (31.0 / 66.0)).toInt(),
                    chargingPower = 0.0,
                )
            }
        )

        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 1, soc = 80.0, chargingPower = 0.0), car)
        Thread.sleep(SETTLE)

        val closed = repo.getSessionById(id)!!
        assertFalse(closed.isActive)
        assertEquals(25.57, closed.kwhAdded!!, 0.05)
        assertNull("a flat zero is a reading that never arrived", closed.energyReceivedKwh)
        // The charge still prices on the pack figure, exactly as it did before the column existed.
        assertEquals(25.57 * 0.59, closed.copy(pricePerKwh = 0.59).explicitCost!!, 0.05)
    }

    /**
     * A near-full charge keeps settling after it closes: the BMS revises SoC upward for minutes
     * while the car stands there, and the pack figure grows with it. The integral is finished
     * and cannot. Past the point where the pack catches up, keeping the stored figure would
     * have less energy arriving than the battery ended up holding, which is not a reading of
     * this charge at all.
     */
    @Test fun aSettledPackFigureThatOvertakesTheDeliveredOneDropsIt() = runBlocking {
        // Close a live off-state charge near full, which is what arms the settle window.
        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 0, soc = 88.0, chargingPower = 7.2), car)
        Thread.sleep(SETTLE)
        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 0, soc = 91.0, chargingPower = 7.2), car)
        Thread.sleep(SETTLE)
        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 0, soc = 91.0, chargingPower = 0.0), car)
        Thread.sleep(SETTLE)

        val closed = repo.getAllSessions().first().single()
        assertFalse("the charge should be closed and settling", closed.isActive)
        // 3% of 82.5 kWh reached the pack. The packets are milliseconds apart in a test, so the
        // integral is meaningless here: write the figure a real charge of this size would have
        // stored, which is the pack gain plus an ordinary 5% lost on the way in.
        val packAtClose = closed.kwhAdded!!
        db.chargingSessionDao().updateSession(closed.copy(energyReceivedKwh = packAtClose * 1.05))
        assertNotNull(repo.getSessionById(closed.id)!!.energyReceivedKwh)

        // The BMS settles two more points onto the same charge, which is 1.65 kWh on this pack
        // and more than the 5% that was lost.
        repo.onTelemetry(gen.generateAcChargingTelemetry(carOn = 0, soc = 93.0, chargingPower = 0.0), car)
        Thread.sleep(SETTLE)

        val settled = repo.getSessionById(closed.id)!!
        assertTrue("settle should have raised the pack figure", settled.kwhAdded!! > packAtClose)
        assertNull(
            "less energy cannot have arrived than the pack ended up holding",
            settled.energyReceivedKwh,
        )
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun writeShutdownState(soc: Float, tsOffsetMs: Long) {
        repoPrefs().edit()
            .putFloat(KEY_SOC,     soc)
            .putLong(KEY_TS,       System.currentTimeMillis() + tsOffsetMs)
            .putFloat(KEY_TEMP,    22.0f)
            .putInt(KEY_VOLTAGE,   450)
            .commit()
    }

    private fun repoPrefs(): SharedPreferences =
        ChargingRepository::class.java.getDeclaredField("prefs")
            .also { it.isAccessible = true }
            .get(repo) as SharedPreferences
}