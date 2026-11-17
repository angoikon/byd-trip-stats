package com.byd.tripstats.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [pickSocEstimate] — the SoC the app reports as its BMS figure.
 */
class SocEstimateTest {

    @Test fun `the BMS reading wins when there is one`() {
        assertEquals(61.7, pickSocEstimate(bmsSoc = 61.7, statisticSocPct = 62.0, panelSoc = 62.0), 0.0)
    }

    @Test fun `a zero statistic SoC no longer beats the panel`() {
        // Shark 6: BMS 62 set aside as a copy of the panel's 62, statistic SoC 0 — trips stored 0 %
        assertEquals(62.0, pickSocEstimate(bmsSoc = null, statisticSocPct = 0.0, panelSoc = 62.0), 0.0)
    }

    @Test fun `a real statistic SoC is still used before the panel`() {
        assertEquals(61.5, pickSocEstimate(bmsSoc = null, statisticSocPct = 61.5, panelSoc = 62.0), 0.0)
    }

    @Test fun `an empty battery still reads 0`() {
        assertEquals(0.0, pickSocEstimate(bmsSoc = null, statisticSocPct = 0.0, panelSoc = 0.0), 0.0)
        assertEquals(0.0, pickSocEstimate(bmsSoc = null, statisticSocPct = null, panelSoc = null), 0.0)
    }

    @Test fun `an out-of-range statistic SoC is passed over`() {
        assertEquals(62.0, pickSocEstimate(bmsSoc = null, statisticSocPct = 432.0, panelSoc = 62.0), 0.0)
    }

    // ── acceptDirectBmsSoc ────────────────────────────────────────────────────
    // Readings from charge #331 on the dev Seal (2026-11-12): the panel is the BMS rounded down and
    // the derived SoC (remaining kWh ÷ 82.5) runs ~2 points below the BMS.

    @Test fun `a decimal BMS reading near the panel is the BMS once the car is known to read decimals`() {
        assertTrue(acceptDirectBmsSoc(70.6, derived = 68.48, panel = 70.0, bmsReadsDecimal = true))
        assertTrue(acceptDirectBmsSoc(72.0, derived = 70.06, panel = 72.0, bmsReadsDecimal = true))
    }

    @Test fun `without the latch the first half of every percent went to the derived SoC`() {
        // The old rule, kept for cars whose BMS reading has never shown a fraction.
        assertFalse(acceptDirectBmsSoc(70.4, derived = 68.48, panel = 70.0, bmsReadsDecimal = false))
        assertTrue(acceptDirectBmsSoc(70.6, derived = 68.48, panel = 70.0, bmsReadsDecimal = false))
        assertTrue(acceptDirectBmsSoc(77.2, derived = 75.76, panel = 77.0, bmsReadsDecimal = false))  // within 1.5
    }

    @Test fun `a whole-number copy of the panel is still set aside`() {
        // Shark 6: BMS 62 = the panel's 62, and nothing has ever read in decimals there.
        assertFalse(acceptDirectBmsSoc(62.0, derived = null, panel = 62.0, bmsReadsDecimal = false))
    }

    @Test fun `only a fraction counts as decimal`() {
        assertTrue(isDecimalSoc(70.6))
        assertTrue(isDecimalSoc(69.80000305175781))   // a float reading widened to double
        assertFalse(isDecimalSoc(62.0))
        assertFalse(isDecimalSoc(61.99999))
        assertFalse(isDecimalSoc(0.0))
    }
}
