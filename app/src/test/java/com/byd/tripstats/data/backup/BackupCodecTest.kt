package com.byd.tripstats.data.backup

import com.byd.tripstats.util.BackupNaming
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Tests for the backup format. Pure JVM — no Android context required.
 *
 * The contract that matters: restore reads both the new compressed backups and every plain
 * `.db` written before compression, and tells them apart by content, not by name.
 */
class BackupCodecTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Enough of a SQLite file for the header check, plus repetitive bytes that compress. */
    private fun fakeDb(): File = temp.newFile("live.db").apply {
        writeBytes("SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(64 * 1024) { (it % 7).toByte() })
    }

    @Test
    fun `compressed backup decodes to the original bytes`() {
        val db = fakeDb()
        val gz = temp.newFile("backup.db.gz")
        BackupCodec.compress(db, gz)

        assertTrue("archive should be smaller", gz.length() < db.length())
        assertFalse(BackupCodec.isSQLiteFile(gz))

        val out = temp.newFile("restored.db")
        BackupCodec.decodeTo(gz.inputStream(), out)
        assertArrayEquals(db.readBytes(), out.readBytes())
        assertTrue(BackupCodec.isSQLiteFile(out))
    }

    @Test
    fun `plain backup passes through unchanged`() {
        val db = fakeDb()
        val out = temp.newFile("restored.db")
        BackupCodec.decodeTo(db.inputStream(), out)
        assertArrayEquals(db.readBytes(), out.readBytes())
    }

    @Test
    fun `format is detected from content, not the file name`() {
        val db = fakeDb()
        val misnamed = temp.newFile("renamed.db")
        BackupCodec.compress(db, misnamed)

        val out = temp.newFile("restored.db")
        BackupCodec.decodeTo(misnamed.inputStream(), out)
        assertArrayEquals(db.readBytes(), out.readBytes())
    }

    @Test(expected = IOException::class)
    fun `truncated archive fails to decode`() {
        val gz = temp.newFile("backup.db.gz")
        BackupCodec.compress(fakeDb(), gz)
        val truncated = temp.newFile("truncated.db.gz").apply { writeBytes(gz.readBytes().copyOf(gz.length().toInt() / 2)) }
        BackupCodec.decodeTo(truncated.inputStream(), temp.newFile("out.db"))
    }

    @Test
    fun `non-database file is rejected`() {
        val txt = temp.newFile("notes.db").apply { writeText("hello") }
        assertFalse(BackupCodec.isSQLiteFile(txt))
    }

    @Test
    fun `backup names cover both formats`() {
        assertTrue(BackupCodec.isBackupName("byd_stats_backup_v2.17.0_2026-09-25_10-30.db"))
        assertTrue(BackupCodec.isBackupName("byd_stats_backup_v2.17.0_2026-09-25_10-30.db.gz"))
        assertTrue(BackupCodec.isBackupName("BACKUP.DB.GZ"))
        assertFalse(BackupCodec.isBackupName("byd_stats_settings_v2.17.0_2026-09-25_10-30.json"))
        assertFalse(BackupCodec.isBackupName("diag.log.gz"))
    }

    @Test
    fun `timestamp pairs a compressed backup with its settings file`() {
        val ts = "2026-09-25_10-30"
        assertEquals(ts, BackupNaming.timestampOf("byd_stats_backup_v2.17.0_$ts.db.gz"))
        assertEquals(ts, BackupNaming.timestampOf("byd_stats_backup_v2.17.0_$ts.db"))
        assertEquals(ts, BackupNaming.timestampOf("byd_stats_settings_v2.17.0_$ts.json"))
    }
}
