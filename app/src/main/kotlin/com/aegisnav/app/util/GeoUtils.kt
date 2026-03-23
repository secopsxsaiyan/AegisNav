package com.aegisnav.app.util

import kotlin.math.*

object GeoUtils {
    /** Haversine distance in metres between two WGS-84 coordinates. */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).let { it * it }
        return 2 * r * asin(sqrt(a))
    }
}
