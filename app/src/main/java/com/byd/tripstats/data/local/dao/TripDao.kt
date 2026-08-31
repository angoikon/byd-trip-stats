package com.byd.tripstats.data.local.dao

import androidx.room.*
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.local.entity.TripSegmentEntity
import com.byd.tripstats.data.local.entity.TripStatsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    fun getAllTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveTrip(): TripEntity?

    @Query("SELECT * FROM trips WHERE id = :tripId")
    suspend fun getTripById(tripId: Long): TripEntity?

    @Query("SELECT * FROM trips WHERE id = :tripId")
    fun getTripByIdFlow(tripId: Long): Flow<TripEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: TripEntity): Long

    @Update
    suspend fun updateTrip(trip: TripEntity)

    @Delete
    suspend fun deleteTrip(trip: TripEntity)

    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun deleteTripById(tripId: Long)

    @Query("UPDATE trips SET isFavourite = :favourite WHERE id = :tripId")
    suspend fun setFavourite(tripId: Long, favourite: Boolean)

    @Query("SELECT * FROM trips WHERE startTime >= :startDate AND startTime <= :endDate ORDER BY startTime DESC")
    fun getTripsByDateRange(startDate: Long, endDate: Long): Flow<List<TripEntity>>

    @Query("SELECT COUNT(*) FROM trips")
    suspend fun getTripCount(): Int

    @Query("SELECT SUM(endOdometer - startOdometer) FROM trips WHERE endOdometer IS NOT NULL")
    suspend fun getTotalDistance(): Double?

    @Query("SELECT AVG((endTotalDischarge - startTotalDischarge) / (endOdometer - startOdometer) * 100) FROM trips WHERE endOdometer IS NOT NULL AND endTotalDischarge IS NOT NULL AND (endOdometer - startOdometer) >= 0.5")
    suspend fun getAverageEfficiency(): Double?

    /** Returns all completed trips that started before [beforeTimestamp].
     *  Used by DatabaseMaintenanceWorker to find candidates for point thinning. */
    @Query("SELECT * FROM trips WHERE isActive = 0 AND startTime < :beforeTimestamp")
    suspend fun getCompletedTripsBefore(beforeTimestamp: Long): List<TripEntity>

    /** Returns completed trips that have no corresponding row in trip_stats.
     *  Used on startup to backfill stats for trips that missed calculateTripStats. */
    @Query("SELECT * FROM trips WHERE isActive = 0 AND id NOT IN (SELECT tripId FROM trip_stats)")
    suspend fun getCompletedTripsWithoutStats(): List<TripEntity>

    /** Completed trips whose panel-SoC pair collapsed onto a flat "x% → x%" — the
     *  signature of the pre-2.15.1 cold-start close path (see
     *  TripRepository.repairFlatEndSocPanel). Trips with no panel reading at all
     *  (startSocPanel = 0) are excluded: there is nothing to recover there. */
    @Query("SELECT * FROM trips WHERE isActive = 0 AND endSocPanel IS NOT NULL AND endSocPanel = startSocPanel AND startSocPanel > 0")
    suspend fun getTripsWithFlatPanelSoc(): List<TripEntity>
}

@Dao
interface TripDataPointDao {
    @Query("SELECT * FROM trip_data_points WHERE tripId = :tripId ORDER BY timestamp ASC")
    fun getDataPointsForTrip(tripId: Long): Flow<List<TripDataPointEntity>>

    @Query("SELECT * FROM trip_data_points WHERE tripId = :tripId ORDER BY timestamp ASC")
    suspend fun getDataPointsForTripSync(tripId: Long): List<TripDataPointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDataPoint(dataPoint: TripDataPointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDataPoints(dataPoints: List<TripDataPointEntity>)

    @Query("DELETE FROM trip_data_points WHERE tripId = :tripId")
    suspend fun deleteDataPointsForTrip(tripId: Long)

    /** Re-points every data point of [oldTripId] onto [newTripId]. Used by trip merge. */
    @Query("UPDATE trip_data_points SET tripId = :newTripId WHERE tripId = :oldTripId")
    suspend fun reassignTripId(oldTripId: Long, newTripId: Long)

    @Query("SELECT COUNT(*) FROM trip_data_points WHERE tripId = :tripId")
    suspend fun getDataPointCount(tripId: Long): Int

    @Query("SELECT * FROM trip_data_points WHERE tripId = :tripId AND timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp ASC")
    fun getDataPointsInTimeRange(tripId: Long, startTime: Long, endTime: Long): Flow<List<TripDataPointEntity>>

    @Query("SELECT * FROM trip_data_points WHERE tripId = :tripId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastDataPointForTrip(tripId: Long): TripDataPointEntity?

    /** Most recent point whose odometer has advanced past [minOdometer].
     *  After the car parks, it can go offline and fill
     *  subsequent samples with odometer=0 — skip those so trip-close logic
     *  doesn't collapse endOdometer down to trip.startOdometer. */
    @Query("SELECT * FROM trip_data_points WHERE tripId = :tripId AND odometer > :minOdometer ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastValidOdometerPointForTrip(tripId: Long, minOdometer: Double): TripDataPointEntity?

    /** Panel SoC of the most recent point of [tripId] that actually carries a reading
     *  (0 means the panel value was never recorded / read as 0). Lets the one-shot
     *  flat-endSocPanel repair resolve a trip without materialising all of its points. */
    @Query("SELECT socPanel FROM trip_data_points WHERE tripId = :tripId AND socPanel > 0 ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastPanelSocForTrip(tripId: Long): Int?

    /** Bulk-deletes points by primary key. Room batches the IN (...) clause.
     *  Used by thinOldDataPoints — first and last points of each trip are never passed here. */
    @Query("DELETE FROM trip_data_points WHERE id IN (:ids)")
    suspend fun deleteDataPointsByIds(ids: List<Long>)

    /**
     * Returns the average SoH recorded across all data points for each trip.
     * Prefers the precise statistic SoH (sohPrecise, e.g. 97.6) recorded since 2.9.1 so the
     * degradation chart/report stay decimal-accurate and match the dashboard; falls back to the
     * rounded integer soh for pre-2.9.1 rows. Zero values are filtered (default before first
     * telemetry with an SoH field). Used by battery degradation tracking to plot SoH over time.
     */
    @Query(
        "SELECT tripId, AVG(CASE WHEN sohPrecise > 0 THEN sohPrecise ELSE soh END) as avgSoh " +
        "FROM trip_data_points WHERE sohPrecise > 0 OR soh > 0 GROUP BY tripId"
    )
    fun getAvgSohPerTrip(): Flow<List<TripSohSummary>>

    // ── One-time precise-SoH backfill (2.9.1) ─────────────────────────────────
    // Recovers the precise statistic_battery_soh that older rows already carry inside
    // rawJson (it just wasn't a queryable column before v8) into sohPrecise, so the
    // degradation chart/report are decimal-accurate across all history.
    /** Data points (paged by id) not yet backfilled whose rawJson holds the precise value. */
    @Query(
        "SELECT id, rawJson FROM trip_data_points " +
        "WHERE sohPrecise = 0 AND id > :afterId AND rawJson LIKE '%statistic_battery_soh%' " +
        "ORDER BY id LIMIT :limit"
    )
    suspend fun pointsNeedingSohBackfill(afterId: Long, limit: Int): List<SohBackfillRow>

    @Query("UPDATE trip_data_points SET sohPrecise = :value WHERE id = :id")
    suspend fun setSohPrecise(id: Long, value: Double)
}

/** Lightweight projection returned by [TripDataPointDao.getAvgSohPerTrip]. */
data class TripSohSummary(val tripId: Long, val avgSoh: Double)

/** Projection for the one-time precise-SoH backfill (id + rawJson). */
data class SohBackfillRow(val id: Long, val rawJson: String)

@Dao
interface TripStatsDao {
    @Query("SELECT * FROM trip_stats WHERE tripId = :tripId")
    suspend fun getStatsForTrip(tripId: Long): TripStatsEntity?

    @Query("SELECT * FROM trip_stats WHERE tripId = :tripId")
    fun getStatsForTripFlow(tripId: Long): Flow<TripStatsEntity?>

    @Query("SELECT * FROM trip_stats")
    fun getAllTripStats(): Flow<List<TripStatsEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStats(stats: TripStatsEntity)

    @Update
    suspend fun updateStats(stats: TripStatsEntity)

    @Query("DELETE FROM trip_stats WHERE tripId = :tripId")
    suspend fun deleteStatsForTrip(tripId: Long)
}

@Dao
interface TripSegmentDao {
    @Insert
    suspend fun insertSegment(segment: TripSegmentEntity): Long

    @Query("SELECT * FROM trip_segments WHERE tripId = :tripId ORDER BY startTime ASC")
    fun getSegmentsForTrip(tripId: Long): Flow<List<TripSegmentEntity>>

    /** Used by calculateTripStats to build the compressed route from segment endpoints. */
    @Query("SELECT * FROM trip_segments WHERE tripId = :tripId ORDER BY startTime ASC")
    suspend fun getSegmentsForTripSync(tripId: Long): List<TripSegmentEntity>

    /** Called by deleteTrip to keep segment data in sync with the trip row. */
    @Query("DELETE FROM trip_segments WHERE tripId = :tripId")
    suspend fun deleteSegmentsForTrip(tripId: Long)

    /** Re-points every segment of [oldTripId] onto [newTripId]. Used by trip merge. */
    @Query("UPDATE trip_segments SET tripId = :newTripId WHERE tripId = :oldTripId")
    suspend fun reassignTripId(oldTripId: Long, newTripId: Long)
}