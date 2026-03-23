package com.aegisnav.app.data.dao

import androidx.room.*
import com.aegisnav.app.data.model.SavedLocation
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedLocationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(location: SavedLocation)

    @Query("SELECT * FROM saved_locations ORDER BY created_at DESC")
    fun getAllNewestFirst(): Flow<List<SavedLocation>>

    @Query("DELETE FROM saved_locations WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM saved_locations WHERE type = :type")
    suspend fun deleteByType(type: String)

    @Query("DELETE FROM saved_locations")
    suspend fun deleteAll()
}
