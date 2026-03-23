package com.aegisnav.app.data.dao

import androidx.room.*
import com.aegisnav.app.data.model.ThreatEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface ThreatEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: ThreatEvent)

    /** All events newest-first, capped at 200 for the timeline feed. */
    @Query("SELECT * FROM threat_events ORDER BY timestamp DESC LIMIT 200")
    fun getAllNewestFirst(): Flow<List<ThreatEvent>>

    @Query("DELETE FROM threat_events")
    suspend fun deleteAll()

    @Query("DELETE FROM threat_events WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
