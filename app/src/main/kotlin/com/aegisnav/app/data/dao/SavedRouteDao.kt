package com.aegisnav.app.data.dao

import androidx.room.*
import com.aegisnav.app.data.model.SavedRoute
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedRouteDao {

    @Query("SELECT * FROM saved_routes ORDER BY createdAt DESC")
    fun getAll(): Flow<List<SavedRoute>>

    @Insert
    suspend fun insert(route: SavedRoute)

    @Query("DELETE FROM saved_routes WHERE id = :id")
    suspend fun deleteById(id: Int)
}
