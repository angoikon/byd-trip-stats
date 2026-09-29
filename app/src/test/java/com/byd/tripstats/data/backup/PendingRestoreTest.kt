package com.byd.tripstats.data.backup

import com.byd.tripstats.data.backup.LocalBackupManager.DbSummary
import com.byd.tripstats.data.backup.LocalBackupManager.PendingRestore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The warning on the restore confirmation: it has to fire for the pick that cost the dev car
 * its history on 2026-09-27 — a backup with nothing in it, over six months of trips.
 */
class PendingRestoreTest {

    private val day = 86_400_000L

    private fun summary(trips: Int, last: Long?) =
        DbSummary(trips = trips, firstTrip = last?.let { it - 100 * day }, lastTrip = last, chargingSessions = 0)

    private fun pending(backup: DbSummary, current: DbSummary?) =
        PendingRestore("backup.db.gz", backup, current, settings = null, file = File("unused"))

    @Test
    fun `an empty backup over a full database warns`() {
        assertTrue(pending(summary(0, null), summary(509, 200 * day)).losesData)
    }

    @Test
    fun `an older backup with as many trips warns`() {
        assertTrue(pending(summary(509, 190 * day), summary(509, 200 * day)).losesData)
    }

    @Test
    fun `a newer backup does not warn`() {
        assertFalse(pending(summary(512, 201 * day), summary(509, 200 * day)).losesData)
    }

    @Test
    fun `restoring onto a fresh install does not warn`() {
        assertFalse(pending(summary(509, 200 * day), summary(0, null)).losesData)
        assertFalse(pending(summary(509, 200 * day), current = null).losesData)
    }
}
