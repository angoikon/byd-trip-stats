package com.byd.tripstats.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [di5PolledSpeedKmh] — which of DiLink-5's two speeds the live speed shows.
 * Events = the speedometer figure (whole km/h, ~4.6 % above ground speed); getter = ground speed.
 */
class Di5SpeedSourceTest {

    @Test fun `a unit with no speed events shows the getter`() {
        assertEquals(67.9, di5PolledSpeedKmh(sinceEventMs = null, shownKmh = 0.0, getterKmh = 67.9), 0.0)
    }

    @Test fun `a fresh event keeps the speedometer figure`() {
        assertEquals(71.0, di5PolledSpeedKmh(sinceEventMs = 1_000, shownKmh = 71.0, getterKmh = 67.9), 0.0)
        // even a big disagreement — the event is newer evidence than a 1 s poll
        assertEquals(71.0, di5PolledSpeedKmh(sinceEventMs = 2_999, shownKmh = 71.0, getterKmh = 40.0), 0.0)
    }

    @Test fun `a steady cruise keeps the speedometer figure, not the getter's lower one`() {
        // Duncs 2026-10-13: panel steady at 70–71, app dipped to the getter's ~64–68 and hung there
        assertEquals(71.0, di5PolledSpeedKmh(sinceEventMs = 5_000, shownKmh = 71.0, getterKmh = 67.9), 0.0)
        assertEquals(136.0, di5PolledSpeedKmh(sinceEventMs = 8_000, shownKmh = 136.0, getterKmh = 130.0), 0.0)
    }

    @Test fun `a real change with the events quiet goes to the getter`() {
        assertEquals(55.0, di5PolledSpeedKmh(sinceEventMs = 4_000, shownKmh = 71.0, getterKmh = 55.0), 0.0)
        assertEquals(90.0, di5PolledSpeedKmh(sinceEventMs = 4_000, shownKmh = 71.0, getterKmh = 90.0), 0.0)
    }

    @Test fun `a stop always shows, however small the gap`() {
        assertEquals(0.0, di5PolledSpeedKmh(sinceEventMs = 4_000, shownKmh = 2.0, getterKmh = 0.0), 0.0)
        assertEquals(0.0, di5PolledSpeedKmh(sinceEventMs = 4_000, shownKmh = 30.0, getterKmh = 0.0), 0.0)
    }

    @Test fun `low speeds use the absolute threshold`() {
        // 8 % of 5 km/h is 0.4 — the 3 km/h floor decides there
        assertEquals(5.0, di5PolledSpeedKmh(sinceEventMs = 4_000, shownKmh = 5.0, getterKmh = 3.0), 0.0)
        assertEquals(1.5, di5PolledSpeedKmh(sinceEventMs = 4_000, shownKmh = 5.0, getterKmh = 1.5), 0.0)
    }
}
