package com.aegisnav.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import com.aegisnav.app.data.model.RedLightCamera

@Dao
interface RedLightCameraDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(camera: RedLightCamera)

    @Query("SELECT * FROM red_light_cameras")
    fun getAll(): Flow<List<RedLightCamera>>

    @Query("""
        SELECT * FROM red_light_cameras
        WHERE lat BETWEEN :minLat AND :maxLat
          AND lon BETWEEN :minLon AND :maxLon
    """)
    fun getByBounds(
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double
    ): Flow<List<RedLightCamera>>

    @Query("SELECT COUNT(*) FROM red_light_cameras")
    suspend fun count(): Int

    @Query("DELETE FROM red_light_cameras")
    suspend fun deleteAll()

    @Query("SELECT * FROM red_light_cameras WHERE state IN (:states) OR state = ''")
    fun getByStates(states: List<String>): Flow<List<RedLightCamera>>
}
