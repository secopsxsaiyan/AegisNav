package com.aegisnav.app.tracker

import java.util.UUID

/**
 * Emitted when a BLE or WiFi device MAC qualifies as a potential tracker:
 * 3+ distinct GPS locations each ≥150 m apart, within a 5–30 minute window.
 */
data class TrackerAlert(
    val mac: String,
    val manufacturer: String?,          // OUI lookup result
    val sightings: List<Sighting>,       // all qualifying sighting locations
    val firstSeen: Long,
    val lastSeen: Long,
    val rssiTrend: List<Int>,            // signal strength over time
    val stopCount: Int = 0,              // number of qualifying location clusters
    val maxSpreadMeters: Double = 0.0,   // max pairwise distance between stop centroids
    val alertId: String = UUID.randomUUID().toString()
) {
    init {
        require(sightings.isNotEmpty()) { "TrackerAlert must have at least one sighting" }
    }
}
