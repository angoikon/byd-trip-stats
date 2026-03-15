package com.byd.tripstats.data.local.dao

import androidx.room.*
import com.byd.tripstats.data.local.entity.ChargingDataPointEntity
import com.byd.tripstats.data.local.entity.ChargingSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChargingSessionDao {

    // ── Sessions ──────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChargingSessionEntity): Long

    @Update
    suspend fun updateSession(session: ChargingSessionEntity)

    @Query("SELECT * FROM charging_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<ChargingSessionEntity>>

    @Query("SELECT * FROM charging_sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: Long): ChargingSessionEntity?

    @Query("SELECT * FROM charging_sessions WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveSession(): ChargingSessionEntity?

    @Query("SELECT * FROM charging_sessions WHERE startTime >= :startDate AND startTime <= :endDate ORDER BY startTime DESC")
    fun getSessionsByDateRange(startDate: Long, endDate: Long): Flow<List<ChargingSessionEntity>>

    @Query("SELECT COUNT(*) FROM charging_sessions WHERE isActive = 0")
    suspend fun getCompletedSessionCount(): Int

    @Query("SELECT SUM(kwhAdded) FROM charging_sessions WHERE kwhAdded IS NOT NULL")
    suspend fun getTotalKwhAdded(): Double?

    // ── Data points ───────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDataPoint(dataPoint: ChargingDataPointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDataPoints(dataPoints: List<ChargingDataPointEntity>)

    @Query("SELECT * FROM charging_data_points WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getDataPointsForSession(sessionId: Long): Flow<List<ChargingDataPointEntity>>

    @Query("SELECT * FROM charging_data_points WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getDataPointsForSessionSync(sessionId: Long): List<ChargingDataPointEntity>

    @Query("DELETE FROM charging_data_points WHERE sessionId = :sessionId")
    suspend fun deleteDataPointsForSession(sessionId: Long)

    @Query("SELECT COUNT(*) FROM charging_data_points WHERE sessionId = :sessionId")
    suspend fun getDataPointCount(sessionId: Long): Int
}