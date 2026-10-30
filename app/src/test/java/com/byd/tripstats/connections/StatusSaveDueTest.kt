package com.byd.tripstats.connections

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MQTT publisher's status is saved to storage on a change or once a minute, not with every
 * message: at the 1 s default that was a settings-file write every second of a drive.
 */
class StatusSaveDueTest {

    private val minute = 60_000L

    @Test
    fun `the first status since start is saved`() {
        assertTrue(statusSaveDue("MQTT publish ok", savedStatus = null, nowElapsedMs = 5_000, savedAtElapsedMs = 0, intervalMs = minute))
    }

    @Test
    fun `the same status within a minute is not saved again`() {
        assertFalse(statusSaveDue("MQTT publish ok", "MQTT publish ok", nowElapsedMs = 100_000, savedAtElapsedMs = 41_000, intervalMs = minute))
    }

    @Test
    fun `the same status is saved again once a minute has passed`() {
        assertTrue(statusSaveDue("MQTT publish ok", "MQTT publish ok", nowElapsedMs = 101_000, savedAtElapsedMs = 41_000, intervalMs = minute))
    }

    @Test
    fun `a changed status is saved at once`() {
        assertTrue(statusSaveDue("MQTT publish failed", "MQTT publish ok", nowElapsedMs = 42_000, savedAtElapsedMs = 41_000, intervalMs = minute))
        assertTrue(statusSaveDue("MQTT publish ok", "MQTT publish failed", nowElapsedMs = 43_000, savedAtElapsedMs = 42_000, intervalMs = minute))
    }
}
