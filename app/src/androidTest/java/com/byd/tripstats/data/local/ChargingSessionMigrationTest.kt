package com.byd.tripstats.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Replays the real migrations against a real file, which is what the schema JSONs are exported
 * and committed for.
 *
 * The charge history is the one table that is never pruned, so a migration that drops it takes
 * years of records with it and the owner has no second copy. Reading the SQL is not enough.
 */
@RunWith(AndroidJUnit4::class)
class ChargingSessionMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BydStatsDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun aChargeRecordedBeforeTheColumnExistedSurvivesAndReportsNoDeliveredEnergy() {
        helper.createDatabase(TEST_DB, 13).apply {
            execSQL(
                """
                INSERT INTO charging_sessions
                    (startTime, endTime, socStart, socEnd, socStartPanel, socEndPanel,
                     kwhAdded, peakKw, avgKw, batteryTempStart, voltageStart, batteryKwh,
                     carConfigId, isActive, isFavourite, pricePerKwh)
                VALUES (1000, 2000, 49.0, 81.0, 49.0, 81.0, 26.5, 43.3, 29.4, 21.0, 566,
                        82.5, 'BYD_SEAL_EXCELLENCE', 0, 0, 0.59)
                """.trimIndent()
            )
            close()
        }

        // validateDroppedTables compares the result against the committed 14.json.
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 14, true, BydStatsDatabase.MIGRATION_13_14,
        )

        db.query("SELECT kwhAdded, pricePerKwh, energyReceivedKwh FROM charging_sessions").use {
            assertTrue("the charge recorded before the upgrade is gone", it.moveToFirst())
            assertEquals(26.5, it.getDouble(0), 1e-9)
            assertEquals(0.59, it.getDouble(1), 1e-9)
            // Null, not zero. Its samples may already have been trimmed, and a zero here would
            // claim the charge drew no power.
            assertTrue("a pre-upgrade charge must not claim a received figure", it.isNull(2))
        }
    }

    private companion object {
        const val TEST_DB = "migration-test-charging"
    }
}
