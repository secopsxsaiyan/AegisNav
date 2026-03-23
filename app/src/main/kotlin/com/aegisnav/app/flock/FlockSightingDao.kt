package com.aegisnav.app.flock

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FlockSightingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sighting: FlockSighting)

    @Query("SELECT * FROM flock_sightings ORDER BY timestamp DESC")
    fun getAll(): Flow<List<FlockSighting>>

    @Query("""
        SELECT * FROM flock_sightings
        WHERE lat BETWEEN :minLat AND :maxLat
          AND lon BETWEEN :minLon AND :maxLon
        ORDER BY timestamp DESC
    """)
    suspend fun getByBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): List<FlockSighting>

    @Query("UPDATE flock_sightings SET reported = 1 WHERE id = :id")
    suspend fun markReported(id: String)

    @Query("DELETE FROM flock_sightings")
    suspend fun deleteAll()
}
