package com.aegisnav.app.data.dao

import androidx.room.*
import com.aegisnav.app.data.model.WatchlistEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchlistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: WatchlistEntry)

    @Query("DELETE FROM watchlist WHERE mac = :mac")
    suspend fun delete(mac: String)

    @Query("SELECT * FROM watchlist ORDER BY createdAt DESC")
    fun getAll(): Flow<List<WatchlistEntry>>

    @Query("SELECT mac FROM watchlist")
    suspend fun getAllMacs(): List<String>

    @Query("SELECT * FROM watchlist WHERE mac = :mac")
    suspend fun getByMac(mac: String): WatchlistEntry?

    @Query("DELETE FROM watchlist")
    suspend fun deleteAll()
}
