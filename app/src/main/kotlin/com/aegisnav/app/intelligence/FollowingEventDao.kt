package com.aegisnav.app.intelligence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FollowingEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: FollowingEvent)

    /** Fetch all following events newer than [since] epoch-ms, newest first. */
    @Query("SELECT * FROM following_events WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun getRecent(since: Long): List<FollowingEvent>

    /** Fetch the N most recent events. */
    @Query("SELECT * FROM following_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<FollowingEvent>

    /** Delete events older than [before] epoch-ms. */
    @Query("DELETE FROM following_events WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    /** Total count of following events in the table. */
    @Query("SELECT COUNT(*) FROM following_events")
    suspend fun count(): Int

    /** Delete all following detection events (factory reset). */
    @Query("DELETE FROM following_events")
    suspend fun deleteAll()
}
