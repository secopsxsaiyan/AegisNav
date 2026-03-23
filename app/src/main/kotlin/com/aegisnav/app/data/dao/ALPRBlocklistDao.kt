package com.aegisnav.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aegisnav.app.data.model.ALPRBlocklist
import kotlinx.coroutines.flow.Flow

@Dao
interface ALPRBlocklistDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(alpr: ALPRBlocklist): Long

    /** Upsert - replaces on lat/lon conflict (for sync worker idempotency). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alpr: ALPRBlocklist)

    /**
     * Returns all ALPR entries in the DB.
     * Safe without a hard cap because the DB is pre-filtered at preload time to only contain
     * cameras for the user's selected states (a few thousand at most for a single state).
     * Use [getNearby] for viewport-scoped map queries (renders at most 200 markers).
     */
    @Query("SELECT * FROM alpr_blocklist")
    fun getAll(): Flow<List<ALPRBlocklist>>

    /** Viewport-scoped query with a hard 200-marker cap to prevent OOM on dense areas. */
    @Query("SELECT * FROM alpr_blocklist WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon LIMIT 200")
    suspend fun getNearby(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<ALPRBlocklist>

    @Query("DELETE FROM alpr_blocklist WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT * FROM alpr_blocklist WHERE id = :id")
    suspend fun getById(id: Int): ALPRBlocklist?

    @Query("SELECT COUNT(*) FROM alpr_blocklist")
    suspend fun count(): Int

    @Query("DELETE FROM alpr_blocklist WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Query("SELECT * FROM alpr_blocklist WHERE state IN (:states) OR state = ''")
    fun getByStates(states: List<String>): Flow<List<ALPRBlocklist>>

    /**
     * Returns entries within a very small bounding box (~10 m radius) for duplicate detection.
     * The delta 0.0001° ≈ 11 m latitude; longitude delta is acceptable at all latitudes ≤ 80°.
     */
    @Query("SELECT * FROM alpr_blocklist WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon")
    suspend fun findWithinBounds(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<ALPRBlocklist>
}
