package com.aegisnav.app.police

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a detected law enforcement equipment sighting.
 *
 * Detection is fully offline. Confidence is built from independent RF signal
 * matches (BLE OUI, BLE device name, WiFi SSID, manufacturer data) as
 * configured in assets/police_signatures.json.
 *
 * [detectionCategory] values: "AXON", "DIGITAL_ALLY", "CRADLEPOINT",
 *   "MOTOROLA_SOLUTIONS", "CELLULAR_MDT", "UNKNOWN_LE"
 *
 * Phase 2B: [estimatedDistanceMeters] is persisted (DB v19 migration adds column).
 */
@Immutable
@Entity(tableName = "police_sightings")
data class PoliceSighting(
    @PrimaryKey val id: String,          // UUID
    val lat: Double,
    val lon: Double,
    val timestamp: Long,
    val confidence: Float,
    val matchedSignals: String,          // JSON list of what matched
    val detectionCategory: String,       // best-guess hardware category
    val reported: Boolean = false,
    /** RSSI-derived distance estimate in metres. Null before DB v19 rows are written. */
    val estimatedDistanceMeters: Double? = null,
    /** Number of distinct devices consolidated into this sighting (DB v24). */
    val deviceCount: Int = 1,
    /** Comma-separated MAC addresses consolidated into this sighting (DB v24). */
    val deviceMacs: String? = null,
    /** User verdict on this sighting: "confirmed", "dismissed", or null (DB v25). */
    val userVerdict: String? = null,
    /** Deadline for user verdict (ms epoch). After this, auto-dismissed (DB v25). */
    val verdictDeadlineMs: Long = 0L,
    /** Officer unit ID if this sighting was correlated to a known unit (DB v25). */
    val officerUnitId: String? = null
)
