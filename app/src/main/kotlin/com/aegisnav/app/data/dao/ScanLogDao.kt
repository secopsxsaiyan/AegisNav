package com.aegisnav.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.aegisnav.app.data.model.ScanLog
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanLogDao {
    @Insert
    suspend fun insert(log: ScanLog)

    @Query("SELECT * FROM scan_logs ORDER BY timestamp DESC LIMIT 100")
    fun getRecent(): Flow<List<ScanLog>>

    @Query("DELETE FROM scan_logs")
    suspend fun deleteAll()

    /** Delete scan log entries older than [cutoff] epoch-ms. Use to enforce the 5-minute ring-buffer TTL. */
    @Query("DELETE FROM scan_logs WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("SELECT DISTINCT deviceAddress FROM scan_logs WHERE timestamp > :since ORDER BY timestamp DESC LIMIT 100")
    fun getRecentAddresses(since: Long): Flow<List<String>>

    /**
     * Fetch all sightings with location data since [since] epoch-ms.
     * Used by FollowingDetector (4.2) to analyse multi-zone device trajectories.
     * Returns at most 2000 rows to keep in-memory analysis bounded.
     */
    @Query(
        "SELECT * FROM scan_logs WHERE timestamp > :since AND lat IS NOT NULL AND lng IS NOT NULL " +
        "ORDER BY deviceAddress, timestamp ASC LIMIT 2000"
    )
    suspend fun getLocatedSightingsSince(since: Long): List<ScanLog>
}