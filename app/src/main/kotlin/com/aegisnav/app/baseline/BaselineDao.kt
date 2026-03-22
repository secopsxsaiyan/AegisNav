package com.aegisnav.app.baseline

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BaselineDao {

    /** Insert or replace a device profile. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: BaselineDevice)

    /** Fetch all baseline device profiles for a given zone. */
    @Query("SELECT * FROM baseline_devices WHERE zoneId = :zoneId")
    suspend fun getByZone(zoneId: String): List<BaselineDevice>

    /** Fetch a single profile by MAC + zone (null if not yet seen). */
    @Query("SELECT * FROM baseline_devices WHERE mac = :mac AND zoneId = :zoneId LIMIT 1")
    suspend fun get(mac: String, zoneId: String): BaselineDevice?

    /** Delete profiles last seen before [beforeMs] (stale zone cleanup). */
    @Query("DELETE FROM baseline_devices WHERE lastSeen < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)

    /** Total number of distinct devices ever recorded across all zones. */
    @Query("SELECT COUNT(DISTINCT mac) FROM baseline_devices")
    suspend fun countDistinctDevices(): Int

    /** Delete all baseline device fingerprints (factory reset). */
    @Query("DELETE FROM baseline_devices")
    suspend fun deleteAll()
}
