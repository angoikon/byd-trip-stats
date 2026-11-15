package com.byd.tripstats.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for what the UID-2000 telemetry daemon does when a message on its looper throws: carry
 * on (DiLink-3 only), how often it logs, how it paces itself, and what the log line holds.
 */
class DaemonLoopPolicyTest {

    @Test
    fun `only DiLink-3 on Android 10 re-enters the loop`() {
        assertTrue(daemonLoopRecovers(isDiLink5 = false, sdkInt = 29))
        assertFalse("DiLink-5 keeps the old behaviour", daemonLoopRecovers(isDiLink5 = true, sdkInt = 30))
        assertFalse("DiLink-5 reporting an old SDK", daemonLoopRecovers(isDiLink5 = true, sdkInt = 29))
        assertFalse("DiLink-100 / Android 14", daemonLoopRecovers(isDiLink5 = false, sdkInt = 34))
        assertFalse("anything newer than Android 10", daemonLoopRecovers(isDiLink5 = false, sdkInt = 30))
    }

    @Test
    fun `logs the first five failures, then every 100th`() {
        val logged = (1..1000).filter { daemonLoopShouldLog(it) }
        assertEquals(listOf(1, 2, 3, 4, 5, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000), logged)
    }

    @Test
    fun `an isolated failure re-enters at once, a quick repeat waits a second`() {
        assertEquals(0L, daemonLoopBackoffMs(null))
        assertEquals(0L, daemonLoopBackoffMs(60_000L))
        assertEquals(0L, daemonLoopBackoffMs(1_000L))
        assertEquals(1_000L, daemonLoopBackoffMs(999L))
        assertEquals(1_000L, daemonLoopBackoffMs(0L))
    }

    @Test
    fun `the log line carries the stack and the cause`() {
        val cause = IllegalStateException("inner")
        val e = SecurityException("Given calling package android does not match caller's uid 2000", cause)
        val text = describeLoopError(e)
        assertTrue(text.startsWith("java.lang.SecurityException: Given calling package android"))
        assertTrue(text.contains("\n    at "))
        assertTrue(text.contains("caused by java.lang.IllegalStateException: inner"))
    }

    @Test
    fun `the stack is capped`() {
        val e = RuntimeException("x").apply {
            stackTrace = Array(100) { StackTraceElement("C", "m$it", "F.kt", it) }
        }
        assertEquals(30, describeLoopError(e).split("\n    at ").size - 1)
    }
}
