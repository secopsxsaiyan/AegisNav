package com.aegisnav.app.police

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface OfficerUnitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(unit: OfficerUnit)

    @Query("SELECT * FROM officer_units WHERE unitId = :unitId")
    suspend fun getById(unitId: String): OfficerUnit?

    @Query("SELECT * FROM officer_units ORDER BY lastSeenMs DESC")
    fun getAllNewestFirst(): Flow<List<OfficerUnit>>

    @Query("UPDATE officer_units SET confirmCount = confirmCount + 1 WHERE unitId = :unitId")
    suspend fun incrementConfirmCount(unitId: String)

    @Query("UPDATE officer_units SET lastSeenMs = :timestamp WHERE unitId = :unitId")
    suspend fun updateLastSeen(unitId: String, timestamp: Long)

    @Query("UPDATE officer_units SET macSet = :macSet WHERE unitId = :unitId")
    suspend fun updateMacSet(unitId: String, macSet: String)

    @Query("SELECT COUNT(*) FROM officer_units WHERE unitId LIKE :cityPrefix || '%'")
    suspend fun countByCity(cityPrefix: String): Int

    @Query("SELECT * FROM officer_units")
    suspend fun getAll(): List<OfficerUnit>

    @Query("DELETE FROM officer_units")
    suspend fun deleteAll()

    @Query("UPDATE officer_units SET userConfirmTapCount = :count, userDismissTapCount = 0 WHERE unitId = :unitId")
    suspend fun setConfirmTapCount(unitId: String, count: Int)

    @Query("UPDATE officer_units SET userDismissTapCount = :count, userConfirmTapCount = 0 WHERE unitId = :unitId")
    suspend fun setDismissTapCount(unitId: String, count: Int)

    @Query("UPDATE officer_units SET verdictLocked = 1, lockedVerdict = :verdict WHERE unitId = :unitId")
    suspend fun lockVerdict(unitId: String, verdict: String)

    @Query("UPDATE officer_units SET lastConfirmTimestamp = :timestamp WHERE unitId = :unitId")
    suspend fun updateLastConfirmTimestamp(unitId: String, timestamp: Long)

    /** Unlock confirmed verdicts whose lastConfirmTimestamp is older than [cutoff] (epoch-ms). */
    @Query("UPDATE officer_units SET verdictLocked = 0, lockedVerdict = NULL WHERE verdictLocked = 1 AND lockedVerdict = 'confirmed' AND lastConfirmTimestamp > 0 AND lastConfirmTimestamp < :cutoff")
    suspend fun pruneExpiredConfirmed(cutoff: Long)
}
