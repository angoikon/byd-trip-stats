package com.byd.tripstats.data.backup

import org.junit.Assert.*
import org.junit.Test

/**
 * The scheduled Telegram backup's size estimate and its "not sent" message. Pure JVM.
 *
 * The estimate decides whether a run compresses the whole database (~25 s of a full core on the
 * head unit) or reports straight away that the archive would not fit.
 */
class TelegramBackupWorkerTest {

    private val mib = 1024L * 1024L

    @Test
    fun `estimate scales the last oversized archive to the database now`() {
        // 900 MB compressed to 54 MB last time: 18 MB more database is about 1.1 MB more archive.
        assertEquals(55.08, scheduledBackupEstimate(918 * mib, 900 * mib, 54 * mib) / mib.toDouble(), 0.01)
    }

    @Test
    fun `a database trimmed far enough comes in under the cap`() {
        val estimate = scheduledBackupEstimate(800 * mib, 900 * mib, 54 * mib)
        assertTrue(estimate <= TelegramManager.TELEGRAM_MAX_FILE_SIZE_BYTES)
    }

    @Test
    fun `a database that only grew stays over the cap`() {
        val estimate = scheduledBackupEstimate(901 * mib, 900 * mib, 54 * mib)
        assertTrue(estimate > TelegramManager.TELEGRAM_MAX_FILE_SIZE_BYTES)
    }

    @Test
    fun `message gives the size, the limit and where to trim`() {
        val text = scheduledBackupOversizeMessage(54 * mib + mib / 10, estimated = false)
        assertTrue(text, text.contains("is 54.1 MB, over the 50 MB"))
        assertTrue(text, text.contains("Backup &amp; Restore"))
        assertFalse("bare & breaks Telegram's HTML", Regex("&(?!amp;)").containsMatchIn(text))
    }

    @Test
    fun `an estimated size says so`() {
        assertTrue(scheduledBackupOversizeMessage(55 * mib, estimated = true).contains("is about 55.0 MB"))
    }
}
