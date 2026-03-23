package com.aegisnav.app.flock

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a detected Flock Safety infrastructure node sighting.
 *
 * Detection is fully offline - no network required. Confidence is built from
 * independent RF signal matches (BLE service UUID, BLE manufacturer data,
 * WiFi SSID pattern, OUI prefix) as configured in assets/flock_signatures.json.
 */
@Immutable
@Entity(tableName = "flock_sightings")
data class FlockSighting(
    @PrimaryKey val id: String,         // UUID
    val lat: Double,
    val lon: Double,
    val timestamp: Long,
    val confidence: Float,
    val matchedSignals: String,         // JSON list of what matched
    val reported: Boolean = false
)
