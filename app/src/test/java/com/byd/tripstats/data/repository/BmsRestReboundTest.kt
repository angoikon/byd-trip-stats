package com.byd.tripstats.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ChargingRepository.isBmsRestRebound] — telling a BMS SoC that crept back up
 * after a drive from an off-state charge, before a "Reconstructed" charging session is created.
 */
class BmsRestReboundTest {

    private fun rebound(bmsRise: Double, panelBefore: Int, panelNow: Int) =
        ChargingRepository.isBmsRestRebound(bmsRise, panelBefore, panelNow)

    @Test
    fun `a small BMS rise with the panel unchanged is a rebound`() {
        assertTrue(rebound(1.5, 50, 50))   // 26 Sep 15:20, parked underground, not charging
        assertTrue(rebound(1.6, 53, 53))   // 25 Sep 22:06
        assertTrue(rebound(2.9, 60, 59))   // panel even slipped a point
    }

    @Test
    fun `a charge that moved the panel is kept`() {
        assertFalse(rebound(1.5, 50, 51))
        assertFalse(rebound(15.0, 42, 57))
    }

    @Test
    fun `a large BMS rise is trusted even if the panel still reads its old value`() {
        assertFalse(rebound(3.0, 42, 42))
        assertFalse(rebound(15.0, 42, 42))
    }

    @Test
    fun `without both panel readings the check stands aside`() {
        assertFalse(rebound(1.5, 0, 50))
        assertFalse(rebound(1.5, 50, 0))
    }
}
