package com.aegisnav.app.intelligence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a detected multi-location following event.
 *
 * Persisted when a device is observed at ≥2 distinct geofence zones within
 * 24 hours, or transitions between zones faster than physically plausible
 * without active mobility (i.e. active mobile following).
 *
 * [followingType] values:
 *  - [FollowingEvent.TYPE_MULTI_LOCATION] — device seen at 2+ zones within 24 h
 *  - [FollowingEvent.TYPE_MOBILE_FOLLOWING] — zones >1 km apart, transition ≤30 min
 */
@Entity(
    tableName = "following_events",
    indices = [Index(value = ["mac"]), Index(value = ["timestamp"])]
)
data class FollowingEvent(
    @PrimaryKey val id: String,          // UUID
    val mac: String,
    val firstZoneId: String,
    val secondZoneId: String,
    /** Epoch-ms of first sighting at firstZoneId within the analysis window. */
    val firstSeenAtA: Long,
    /** Epoch-ms of last sighting at firstZoneId before departure. */
    val lastSeenAtA: Long,
    /** Epoch-ms of first sighting at secondZoneId after departure. */
    val firstSeenAtB: Long,
    /** Haversine distance between zone centroids in metres. */
    val distanceMeters: Double,
    /** Time from last sighting at A to first sighting at B, in minutes. */
    val transitionMinutes: Double,
    val followingType: String,
    /** Epoch-ms when this event was created. */
    val timestamp: Long
) {
    companion object {
        const val TYPE_MULTI_LOCATION   = "MULTI_LOCATION"
        const val TYPE_MOBILE_FOLLOWING = "MOBILE_FOLLOWING"
    }
}
