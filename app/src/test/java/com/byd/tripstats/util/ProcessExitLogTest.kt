package com.byd.tripstats.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/** The diag.log line [ProcessExitLog] writes for each ended process (Android's `ApplicationExitInfo`). */
class ProcessExitLogTest {

    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-11-09 13:09:02 UTC
    private val endedAt = 1_794_229_742_000L

    @Test
    fun `a force-stop reads as user requested, with Android's description`() {
        assertEquals(
            "previous process pid=4940 (com.byd.tripstats) ended 11-09 13:09:02, 25 s before this start: " +
                "USER_REQUESTED status=0 importance=foreground service :: stop com.byd.tripstats due to from pid 1234",
            processExitLine(
                timestampMs = endedAt,
                nowMs = endedAt + 25_400,
                pid = 4940,
                processName = "com.byd.tripstats",
                reason = 10,
                status = 0,
                importance = 125,
                description = "stop com.byd.tripstats due to from pid 1234",
                zone = utc,
            ),
        )
    }

    @Test
    fun `a crash without a description, logged long after`() {
        assertEquals(
            "previous process pid=77 (?) ended 11-09 13:09:02, 3 h before this start: CRASH status=0 importance=cached",
            processExitLine(endedAt, endedAt + 3 * 3_600_000L + 59_000, 77, null, 4, 0, 400, null, utc),
        )
    }

    @Test
    fun `the time since the exit is in the unit that reads best`() {
        fun ago(ms: Long) = processExitLine(endedAt, endedAt + ms, 1, "p", 2, 9, 300, "", utc)
            .substringAfter("13:09:02, ").substringBefore(" before")
        assertEquals("0 s", ago(-5_000))       // a clock step back never reads negative
        assertEquals("119 s", ago(119_999))
        assertEquals("2 min", ago(120_000))
        assertEquals("119 min", ago(7_199_000))
        assertEquals("2 h", ago(7_200_000))
        assertEquals("2 d", ago(48 * 3_600_000L))
    }

    @Test
    fun `descriptions are kept to one line and 120 characters`() {
        val line = processExitLine(endedAt, endedAt, 1, "p", 13, 0, 1000, "  first\nsecond" + "x".repeat(200), utc)
        assertEquals(" :: first second" + "x".repeat(108), line.substring(line.indexOf(" :: ")))
    }

    @Test
    fun `every reason Android defines has its name, others their number`() {
        assertEquals("UNKNOWN", exitReasonName(0))
        assertEquals("SIGNALED", exitReasonName(2))
        assertEquals("LOW_MEMORY", exitReasonName(3))
        assertEquals("CRASH_NATIVE", exitReasonName(5))
        assertEquals("ANR", exitReasonName(6))
        assertEquals("USER_REQUESTED", exitReasonName(10))
        assertEquals("OTHER", exitReasonName(13))
        assertEquals("PACKAGE_UPDATED", exitReasonName(16))
        assertEquals("reason=17", exitReasonName(17))
        assertEquals("perceptible", exitImportanceName(230))
        assertEquals("150", exitImportanceName(150))
    }
}
