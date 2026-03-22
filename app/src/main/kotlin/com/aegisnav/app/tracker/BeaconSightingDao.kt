package com.aegisnav.app.tracker

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface BeaconSightingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sighting: BeaconSighting): Long

    @Update
    suspend fun update(sighting: BeaconSighting)

    /** All sightings for a specific MAC, oldest-first. */
    @Query("SELECT * FROM beacon_sightings WHERE mac = :mac ORDER BY timestamp ASC")
    suspend fun getForMac(mac: String): List<BeaconSighting>

    /** Distinct MACs seen since [since] epoch-ms. */
    @Query("SELECT DISTINCT mac FROM beacon_sightings WHERE timestamp >= :since")
    suspend fun getDistinctMacsSince(since: Long): List<String>

    /** Sightings for a MAC within a time range, oldest-first. */
    @Query(
        "SELECT * FROM beacon_sightings " +
        "WHERE mac = :mac AND timestamp BETWEEN :from AND :to " +
        "ORDER BY timestamp ASC"
    )
    suspend fun getForMacInRange(mac: String, from: Long, to: Long): List<BeaconSighting>

    /** Count of sightings for a MAC. */
    @Query("SELECT COUNT(*) FROM beacon_sightings WHERE mac = :mac")
    suspend fun countForMac(mac: String): Int

    /** Mark all sightings for a MAC as confirmed. */
    @Query(
        "UPDATE beacon_sightings SET confirmedTracker = 1 WHERE mac = :mac"
    )
    suspend fun markConfirmed(mac: String)

    // ── Pruning queries (Feature 3.13) ────────────────────────────────────────

    /**
     * Delete non-confirmed sightings older than [cutoff].
     * Used for the 30-day correlated-data window.
     */
    @Query(
        "DELETE FROM beacon_sightings WHERE confirmedTracker = 0 AND timestamp < :cutoff"
    )
    suspend fun pruneNonConfirmed(cutoff: Long)

    /**
     * Delete confirmed-tracker sightings older than [cutoff].
     * Used for the 90-day confirmed-data window.
     */
    @Query(
        "DELETE FROM beacon_sightings WHERE confirmedTracker = 1 AND timestamp < :cutoff"
    )
    suspend fun pruneConfirmed(cutoff: Long)

    /** Delete all sightings. */
    @Query("DELETE FROM beacon_sightings")
    suspend fun deleteAll()
}
