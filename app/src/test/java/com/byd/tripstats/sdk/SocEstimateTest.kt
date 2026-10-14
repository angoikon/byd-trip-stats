package com.byd.tripstats.sdk

import org.junit.Assert.assertEquals
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
}
