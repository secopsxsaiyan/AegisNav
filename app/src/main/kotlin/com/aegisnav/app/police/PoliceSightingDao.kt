package com.aegisnav.app.police

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow


@Dao
interface PoliceSightingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sighting: PoliceSighting)

    @Update
    suspend fun update(sighting: PoliceSighting)

    @Query("SELECT * FROM police_sightings ORDER BY timestamp DESC")
    fun getAll(): Flow<List<PoliceSighting>>

    @Query("SELECT COUNT(*) FROM police_sightings")
    fun countFlow(): Flow<Int>

    @Query("""
        SELECT * FROM police_sightings
        WHERE lat BETWEEN :minLat AND :maxLat
          AND lon BETWEEN :minLon AND :maxLon
        ORDER BY timestamp DESC
    """)
    suspend fun getByBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): List<PoliceSighting>

    @Query("UPDATE police_sightings SET reported = 1 WHERE id = :id")
    suspend fun markReported(id: String)

    @Query("DELETE FROM police_sightings")
    suspend fun deleteAll()

    @Query("SELECT * FROM police_sightings ORDER BY timestamp DESC LIMIT 1")
    fun getMostRecent(): Flow<PoliceSighting?>

    @Query("SELECT * FROM police_sightings ORDER BY timestamp DESC")
    fun getAllNewestFirst(): Flow<List<PoliceSighting>>

    @Query("UPDATE police_sightings SET officerUnitId = :unitId WHERE id = :id")
    suspend fun setOfficerUnitId(id: String, unitId: String)

    @Query("UPDATE police_sightings SET userVerdict = :verdict WHERE id = :id")
    suspend fun setUserVerdict(id: String, verdict: String)
}
