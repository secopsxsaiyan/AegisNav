package com.aegisnav.app.tracker

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 3 — Feature 3.6.
 *
 * Persistent record of every non-ignored BLE sighting.
 * Stored in [beacon_sightings] table and evaluated by [BeaconHistoryManager]
 * to detect devices seen at 3+ locations over 2+ hours.
 *
 * [confirmedTracker] is set to true when [BeaconHistoryManager] has verified
 * the device meets the stalking threshold.  Confirmed sightings are kept for
 * 90 days; non-confirmed are pruned after 30 days; raw scan data after 7 days.
 */
@Entity(
    tableName = "beacon_sightings",
    indices = [
        Index(value = ["mac"]),
        Index(value = ["timestamp"])
    ]
)
data class BeaconSighting(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Device MAC address (may be virtual MAC after correlation). */
    val mac: String,
    val timestamp: Long,
    val rssi: Int,
    val lat: Double?,
    val lng: Double?,
    /** [TrackerType] name (string for Room compatibility). */
    val trackerType: String = TrackerType.UNKNOWN.name,
    /** Risk score at time of sighting (type weight × RSSI quality). */
    val riskScore: Float = 0f,
    /** True when this device has been confirmed as a tracker (3+ locations, 2+ hours). */
    val confirmedTracker: Boolean = false
)
