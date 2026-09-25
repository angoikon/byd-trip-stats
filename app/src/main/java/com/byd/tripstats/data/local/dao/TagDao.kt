package com.byd.tripstats.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.byd.tripstats.data.local.entity.TagEntity
import com.byd.tripstats.data.local.entity.TripTagCrossRef
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    // ── Tags ────────────────────────────────────────────────────────────────
    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    fun getAllTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getTagByName(name: String): TagEntity?

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun getTagCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Update
    suspend fun updateTag(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id = :tagId")
    suspend fun deleteTagById(tagId: Long)

    // ── Trip ↔ tag links ────────────────────────────────────────────────────
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTagToTrip(ref: TripTagCrossRef)

    @Query("DELETE FROM trip_tags WHERE tripId = :tripId AND tagId = :tagId")
    suspend fun removeTagFromTrip(tripId: Long, tagId: Long)

    @Query("DELETE FROM trip_tags WHERE tripId = :tripId")
    suspend fun clearTagsForTrip(tripId: Long)

    @Query("SELECT COUNT(*) FROM trip_tags WHERE tripId = :tripId")
    suspend fun getTagCountForTrip(tripId: Long): Int

    @Query("DELETE FROM trip_tags WHERE tagId = :tagId")
    suspend fun clearTripsForTag(tagId: Long)

    /** All links — joined in-memory by the ViewModel to tag the History list and filter. */
    @Query("SELECT * FROM trip_tags")
    fun getAllTripTagRefs(): Flow<List<TripTagCrossRef>>

    /** Tags on a single trip, for the Trip Detail screen. */
    @Query(
        "SELECT t.* FROM tags t INNER JOIN trip_tags tt ON t.id = tt.tagId " +
        "WHERE tt.tripId = :tripId ORDER BY t.name COLLATE NOCASE"
    )
    fun getTagsForTrip(tripId: Long): Flow<List<TagEntity>>

    /**
     * Moves every tag link from [oldTripId] to [newTripId] during a trip merge.
     * `OR IGNORE` drops links the survivor already has (the leftover duplicate
     * rows on the absorbed trip are removed when its tag links are cleared).
     */
    @Query("UPDATE OR IGNORE trip_tags SET tripId = :newTripId WHERE tripId = :oldTripId")
    suspend fun reassignTripTags(oldTripId: Long, newTripId: Long)
}
