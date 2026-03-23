package com.aegisnav.app.tracker

import java.util.UUID

/**
 * Emitted when a BLE or WiFi device MAC qualifies as a potential tracker:
 * Either 2+ distinct GPS clusters ≥30m apart with route span ≥100m (Path A),
 * or 3+ distinct sightings spanning ≥100m over ≥30 seconds (Path B continuous following),
 * within a 30s–30min observation window.
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
    val confidence: String = "HIGH",     // HIGH or LOW (LOW if infrastructure OUI)
    val alertId: String = UUID.randomUUID().toString()
) {
    init {
        require(sightings.isNotEmpty()) { "TrackerAlert must have at least one sighting" }
    }
}
