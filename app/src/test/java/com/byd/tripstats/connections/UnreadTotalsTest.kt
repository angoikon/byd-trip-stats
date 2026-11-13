package com.byd.tripstats.connections

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The state of charge and the running totals go out only once read ([MqttConnectionManager.putReadTotals]).
 * Each falls back to 0 until the car reports it, and a published 0 read as a real value: a meter
 * reset for the `total_increasing` odometer, and a lifetime's worth of consumption in a day for a
 * consumer that followed `total_discharge` down to 0 and back (Sealion 7, 2026-11-13).
 */
class UnreadTotalsTest {

    private fun payload(soc: Double = 80.0, socPanel: Int = 80, totalDischarge: Double = 702.4, odometer: Double = 2690.3) =
        JSONObject().also { MqttConnectionManager.putReadTotals(it, soc, socPanel, totalDischarge, odometer) }

    @Test fun readValuesGoOutAsRead() {
        val p = payload()
        assertEquals(80.0, p.getDouble("soc"), 0.0)
        assertEquals(80, p.getInt("soc_panel"))
        assertEquals(702.4, p.getDouble("total_discharge"), 0.0)
        assertEquals(2690.3, p.getDouble("odometer"), 0.0)
    }

    @Test fun anUnreadDischargeCounterIsLeftOutNotSentAsZero() {
        val p = payload(totalDischarge = 0.0)
        assertFalse(p.has("total_discharge"))
        // The rest of the message is unaffected.
        assertEquals(2690.3, p.getDouble("odometer"), 0.0)
    }

    @Test fun anUnreadOdometerIsLeftOutSoHomeAssistantSeesNoMeterReset() {
        assertFalse(payload(odometer = 0.0).has("odometer"))
    }

    @Test fun anUnreadStateOfChargeIsLeftOut() {
        val p = payload(soc = 0.0, socPanel = 0)
        assertFalse(p.has("soc"))
        assertFalse(p.has("soc_panel"))
    }
}
