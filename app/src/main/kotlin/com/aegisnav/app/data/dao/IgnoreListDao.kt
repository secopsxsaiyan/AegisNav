package com.aegisnav.app.data.dao

import androidx.room.*
import com.aegisnav.app.data.model.IgnoreListEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface IgnoreListDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: IgnoreListEntry)

    @Query("SELECT * FROM ignore_list")
    fun getAll(): Flow<List<IgnoreListEntry>>

    @Query("SELECT address FROM ignore_list")
    suspend fun getAllAddresses(): List<String>

    @Delete
    suspend fun delete(entry: IgnoreListEntry)

    /**
     * Phase 3 — Feature 3.9.
     * Delete expired non-permanent entries only.
     * Permanent entries (permanent = 1) are never removed by this query.
     */
    @Query("DELETE FROM ignore_list WHERE expiresAt < :now AND permanent = 0")
    suspend fun deleteExpired(now: Long)

    /** Phase 3 — return addresses of all permanent entries. */
    @Query("SELECT address FROM ignore_list WHERE permanent = 1")
    suspend fun getPermanentAddresses(): List<String>

    @Query("DELETE FROM ignore_list WHERE address = :address")
    suspend fun deleteByAddress(address: String)

    @Query("DELETE FROM ignore_list")
    suspend fun deleteAll()
}
