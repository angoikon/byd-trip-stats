package com.byd.tripstats.connections

import com.byd.tripstats.data.preferences.SocSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which state of charge goes into ABRP's `soc` field (issue #24). */
class AbrpSocTest {

    @Test fun panelSourceSendsTheDashboardFigure() {
        // Sealion 7 at 80% on the dashboard, 78.4% on the BMS: ABRP should see 80.
        assertEquals(80.0, AbrpConnectionManager.socForAbrp(SocSource.PANEL, socPanel = 80, socBms = 78.4), 1e-9)
    }

    @Test fun bmsSourceSendsTheBmsFigure() {
        assertEquals(78.4, AbrpConnectionManager.socForAbrp(SocSource.BMS, socPanel = 80, socBms = 78.4), 1e-9)
    }

    @Test fun missingPanelFigureFallsBackToBms() {
        // A car that reports no Panel reading leaves it at 0 — never send that as 0%.
        assertEquals(78.4, AbrpConnectionManager.socForAbrp(SocSource.PANEL, socPanel = 0, socBms = 78.4), 1e-9)
    }
}
