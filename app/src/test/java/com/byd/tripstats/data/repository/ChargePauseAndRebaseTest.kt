package com.byd.tripstats.data.repository

import com.byd.tripstats.data.local.entity.ChargingSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the two rules behind one charge staying one session (dev Seal, 2026-11-12: an AC
 * charge showed up as #331 + #332 + a Reconstructed #334 that repeated #332's last 73 minutes):
 * - [ChargingRepository.holdsChargePause] — a pause in the charge doesn't end the session;
 * - [ChargingRepository.rebaseOnRecordedCharge] — a reconstruction measures from the end of a
 *   session that already recorded the charge.
 */
class ChargePauseAndRebaseTest {

    private val hold = ChargingRepository.CHARGE_PAUSE_HOLD_MS

    private fun holds(gear: String, pausedForMs: Long, isDiLink5: Boolean = false) =
        ChargingRepository.holdsChargePause(isDiLink5, gear, pausedForMs)

    // ── Pause hold ────────────────────────────────────────────────────────────

    @Test
    fun `a pause while parked holds the session`() {
        assertTrue(holds("P", 10_000L))     // switching the dev Seal on mid-charge: ~10 s
        assertTrue(holds("P", 0L))
        assertTrue(holds("N", 30_000L))
        assertTrue(holds("P", hold - 1))
    }

    @Test
    fun `the hold runs out`() {
        assertFalse(holds("P", hold))
        assertFalse(holds("P", hold + 60_000L))
    }

    @Test
    fun `a drive gear ends the charge at once`() {
        assertFalse(holds("D", 1_000L))
        assertFalse(holds("R", 1_000L))
    }

    @Test
    fun `DiLink-5 holds its pauses in the data source, not here`() {
        assertFalse(holds("P", 10_000L, isDiLink5 = true))
    }

    @Test
    fun `a clock that went backwards does not hold`() {
        assertFalse(holds("P", -5_000L))
    }

    @Test
    fun `the hold ends before the service's 5-minute self-stop`() {
        assertTrue(hold < 5 * 60_000L)
    }

    // ── Reconstruction baseline ───────────────────────────────────────────────

    private val savedAt = 1_000_000_000L

    /** Saved with the car on, the last time before it was switched off (12:36 on the day). */
    private val saved = ChargingRepository.ShutdownState(
        soc = 78.6, timestamp = savedAt, tempAvg = 21.0, voltage = 560, socPanel = 79,
    )

    private fun session(
        start: Long, end: Long?, socEnd: Double?, panelEnd: Double? = 84.0, active: Boolean = false,
    ) = ChargingSessionEntity(
        id = 332, startTime = start, endTime = end, socStart = 77.7, socEnd = socEnd,
        socEndPanel = panelEnd, batteryTempEnd = 23.0, voltageEnd = 575, isActive = active,
    )

    @Test
    fun `a session that ran past the saved state moves the baseline to its end`() {
        // #332: 12:21 → 13:48, under a state saved at 12:36.
        val recorded = session(start = savedAt - 15 * 60_000L, end = savedAt + 72 * 60_000L, socEnd = 83.9)
        val base = ChargingRepository.rebaseOnRecordedCharge(saved, recorded)
        assertEquals(83.9, base.soc, 0.001)
        assertEquals(savedAt + 72 * 60_000L, base.timestamp)
        assertEquals(84, base.socPanel)
        assertEquals(23.0, base.tempAvg, 0.001)
        assertEquals(575, base.voltage)
    }

    @Test
    fun `no session, or one that ended before the saved state, leaves it as it is`() {
        assertSame(saved, ChargingRepository.rebaseOnRecordedCharge(saved, null))
        val earlier = session(start = savedAt - 3_600_000L, end = savedAt - 60_000L, socEnd = 78.0)
        assertSame(saved, ChargingRepository.rebaseOnRecordedCharge(saved, earlier))
        val endedAtIt = session(start = savedAt - 3_600_000L, end = savedAt, socEnd = 78.6)
        assertSame(saved, ChargingRepository.rebaseOnRecordedCharge(saved, endedAtIt))
    }

    @Test
    fun `an open session or one without an end SoC leaves it as it is`() {
        val open = session(start = savedAt - 60_000L, end = savedAt + 60_000L, socEnd = 80.0, active = true)
        assertSame(saved, ChargingRepository.rebaseOnRecordedCharge(saved, open))
        val noEndSoc = session(start = savedAt - 60_000L, end = savedAt + 60_000L, socEnd = null)
        assertSame(saved, ChargingRepository.rebaseOnRecordedCharge(saved, noEndSoc))
    }

    @Test
    fun `a missing end panel reads as unknown`() {
        val recorded = session(start = savedAt - 60_000L, end = savedAt + 60_000L, socEnd = 80.0, panelEnd = null)
        assertEquals(0, ChargingRepository.rebaseOnRecordedCharge(saved, recorded).socPanel)
    }
}
