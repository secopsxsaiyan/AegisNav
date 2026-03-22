package com.aegisnav.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import com.aegisnav.app.data.model.SpeedCamera

@Dao
interface SpeedCameraDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(camera: SpeedCamera)

    @Query("SELECT * FROM speed_cameras")
    fun getAll(): Flow<List<SpeedCamera>>

    @Query("""
        SELECT * FROM speed_cameras
        WHERE lat BETWEEN :minLat AND :maxLat
          AND lon BETWEEN :minLon AND :maxLon
    """)
    fun getByBounds(
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double
    ): Flow<List<SpeedCamera>>

    @Query("SELECT COUNT(*) FROM speed_cameras")
    suspend fun count(): Int

    @Query("DELETE FROM speed_cameras")
    suspend fun deleteAll()

    @Query("SELECT * FROM speed_cameras WHERE state IN (:states) OR state = ''")
    fun getByStates(states: List<String>): Flow<List<SpeedCamera>>
}
