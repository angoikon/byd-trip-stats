package com.byd.tripstats.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.GZIPInputStream

/**
 * Tests for the diagnostics-log send to Telegram. Pure JVM — no Android context required.
 *
 * Two contracts matter: the archive reads back as the rotated log followed by the current one, and
 * a second tap while a send is under way starts nothing (the failure the Send backup now and
 * Install now buttons both had).
 */
class DiagLogExportTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `archive holds the logs in order and is far smaller`() {
        val prev = temp.newFile("diag.log.prev").apply {
            writeText("10-11 23:59:59.000 Svc: older\n".repeat(2_000))
        }
        val cur = temp.newFile("diag.log").apply {
            writeText("10-12 00:00:01.000 Svc: newer\n".repeat(2_000))
        }
        val gz = temp.newFile("diag.log.gz")

        DiagLogExport.compressLogs(listOf(prev, cur), gz)

        val restored = GZIPInputStream(gz.inputStream()).use { it.readBytes() }
        assertArrayEquals(prev.readBytes() + cur.readBytes(), restored)
        assertTrue("archive should be smaller", gz.length() * 5 < prev.length() + cur.length())
    }

    @Test
    fun `a second launch while one is running is refused and the flag clears afterwards`() = runBlocking {
        val busy = MutableStateFlow(false)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val gate = CompletableDeferred<Unit>()
        val results = mutableListOf<String>()

        val first = DiagLogExport.launchOnce(scope, busy, { r: String -> synchronized(results) { results += r } }) {
            gate.await()
            "first"
        }
        // Claimed synchronously: no yield between the call and this check.
        assertTrue(first)
        assertTrue(busy.value)

        val second = DiagLogExport.launchOnce(scope, busy, { r: String -> synchronized(results) { results += r } }) {
            "second"
        }
        assertFalse("a tap during a send must start nothing", second)

        gate.complete(Unit)
        withTimeout(5_000) { while (busy.value) kotlinx.coroutines.yield() }
        assertEquals(listOf("first"), synchronized(results) { results.toList() })

        // Free again once the first has finished.
        assertTrue(DiagLogExport.launchOnce(scope, busy, { _: String -> }) { "third" })
        scope.cancel()
    }

    @Test
    fun `a scope cancelled before the coroutine starts does not leave the flag stuck`() = runBlocking {
        val busy = MutableStateFlow(false)
        val scope = CoroutineScope(Job()).also { it.cancel() }
        var ran = false

        DiagLogExport.launchOnce(scope, busy, { _: String -> ran = true }) { "never runs" }

        // The cancelled coroutine still completes on the dispatcher, so the flag clears a moment
        // after the call returns rather than inside it.
        withTimeout(5_000) { while (busy.value) kotlinx.coroutines.yield() }
        assertFalse(busy.value)
        assertFalse("work must not run in a cancelled scope", ran)
    }
}
