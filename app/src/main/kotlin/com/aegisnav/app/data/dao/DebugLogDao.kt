package com.aegisnav.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aegisnav.app.data.model.DebugLogEntry

@Dao
interface DebugLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DebugLogEntry)

    /**
     * Delete all entries whose [DebugLogEntry.timestamp] is older than [cutoffMs].
     * Intended to be called periodically to prune entries older than 7 days.
     */
    @Query("DELETE FROM debug_log WHERE timestamp < :cutoffMs")
    suspend fun pruneOlderThan(cutoffMs: Long)

    /** Delete all debug log entries (factory reset). */
    @Query("DELETE FROM debug_log")
    suspend fun deleteAll()
}
