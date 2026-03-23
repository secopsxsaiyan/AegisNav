package com.aegisnav.app.data.dao

import androidx.room.*
import com.aegisnav.app.data.model.Report
import kotlinx.coroutines.flow.Flow

@Dao
interface ReportsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(report: Report): Long   // returns rowId for correlation

    @Query("SELECT * FROM reports ORDER BY timestamp DESC")
    fun getAllReports(): Flow<List<Report>>

    @Query("SELECT * FROM reports WHERE type = :type ORDER BY timestamp DESC")
    fun getByType(type: String): Flow<List<Report>>

    @Query("SELECT * FROM reports WHERE latitude BETWEEN :minLat AND :maxLat AND longitude BETWEEN :minLon AND :maxLon ORDER BY timestamp DESC")
    suspend fun getNearby(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<Report>

    @Query("UPDATE reports SET confirmedCount = confirmedCount + 1 WHERE id = :id")
    suspend fun upvote(id: Int)

    @Query("UPDATE reports SET clearedCount = clearedCount + 1 WHERE id = :id")
    suspend fun markCleared(id: Int)

    /** Set local user verdict (confirmed/dismissed). No-op if verdict is already set. */
    @Query("UPDATE reports SET user_verdict = :verdict WHERE id = :id AND user_verdict IS NULL")
    suspend fun setUserVerdict(id: Int, verdict: String)

    @Query("DELETE FROM reports")
    suspend fun deleteAll()
}
