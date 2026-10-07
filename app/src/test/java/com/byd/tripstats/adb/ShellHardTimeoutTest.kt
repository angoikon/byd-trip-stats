package com.byd.tripstats.adb

import dadb.AdbShellResponse
import dadb.AdbStream
import dadb.Dadb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch

/**
 * Unit tests for [AdbPermissionManager.shellWithHardTimeout] — the timeout that actually ends a
 * shell command. `dadb.shell` blocks in a read no coroutine timeout can interrupt; Tailscale's
 * Connect spun for ever on exactly that (2026-10-07).
 */
class ShellHardTimeoutTest {

    /** A session that never finishes on its own — like a launch that keeps its output open. */
    private class HangingDadb : Dadb {
        private val closed = CountDownLatch(1)
        var wasClosed = false; private set
        override fun open(destination: String): AdbStream = throw UnsupportedOperationException()
        override fun supportsFeature(feature: String) = false
        override fun close() { wasClosed = true; closed.countDown() }
        override fun shell(command: String): AdbShellResponse {
            closed.await()                       // the blocked read…
            throw IOException("Socket closed")  // …ended by closing the connection
        }
    }

    private class QuickDadb : Dadb {
        var wasClosed = false; private set
        override fun open(destination: String): AdbStream = throw UnsupportedOperationException()
        override fun supportsFeature(feature: String) = false
        override fun close() { wasClosed = true }
        override fun shell(command: String) = AdbShellResponse("ok\n", "", 0)
    }

    @Test
    fun `a command that never finishes is ended at the timeout`() = runBlocking {
        // runBlocking is single-threaded: this also proves the watchdog doesn't need the caller's thread.
        val dadb = HangingDadb()
        val started = System.currentTimeMillis()
        val r = AdbPermissionManager.shellWithHardTimeout(dadb, "hangs", timeoutMs = 300L)
        val took = System.currentTimeMillis() - started
        assertTrue(r.timedOut)
        assertEquals("timeout", r.result.output)
        assertEquals(-1, r.result.exitCode)
        assertTrue(dadb.wasClosed)
        assertTrue("took ${took}ms", took < 5_000L)
    }

    @Test
    fun `a command that finishes in time is untouched`() = runBlocking {
        val dadb = QuickDadb()
        val r = AdbPermissionManager.shellWithHardTimeout(dadb, "echo ok", timeoutMs = 5_000L)
        assertFalse(r.timedOut)
        assertEquals("ok", r.result.output)
        assertEquals(0, r.result.exitCode)
        assertFalse("the connection stays open for the next command", dadb.wasClosed)
    }
}
