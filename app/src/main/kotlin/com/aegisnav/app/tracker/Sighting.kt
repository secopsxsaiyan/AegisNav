package com.aegisnav.app.tracker

/**
 * A single observed sighting of a BLE or WiFi device at a specific location and time.
 */
data class Sighting(
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    val rssi: Int,
    val ssid: String? = null   // populated for WiFi sightings; null for BLE
)
