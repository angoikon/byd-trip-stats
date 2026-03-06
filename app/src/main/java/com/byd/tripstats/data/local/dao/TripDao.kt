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

    @Query("SELECT COUNT(*) FROM trip_data_points WHERE tripId = :tripId")
    suspend fun getDataPointCount(tripId: Long): Int

    @Query("SELECT * FROM trip_data_points WHERE tripId = :tripId AND timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp ASC")
    fun getDataPointsInTimeRange(tripId: Long, startTime: Long, endTime: Long): Flow<List<TripDataPointEntity>>

    @Query("SELECT * FROM trip_data_points WHERE tripId = :tripId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastDataPointForTrip(tripId: Long): TripDataPointEntity?

    /** Bulk-deletes points by primary key. Room batches the IN (...) clause.
     *  Used by thinOldDataPoints — first and last points of each trip are never passed here. */
    @Query("DELETE FROM trip_data_points WHERE id IN (:ids)")
    suspend fun deleteDataPointsByIds(ids: List<Long>)
}

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
}