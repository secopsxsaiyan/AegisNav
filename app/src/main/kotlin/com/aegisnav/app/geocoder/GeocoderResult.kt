package com.aegisnav.app.geocoder

/**
 * A geocoding result from the offline FTS4 database.
 */
data class OfflineGeocoderResult(
    val displayName: String,
    val lat: Double,
    val lon: Double,
    val type: String,   // "city", "town", "village", "street", "address", "poi"
    val state: String
)
