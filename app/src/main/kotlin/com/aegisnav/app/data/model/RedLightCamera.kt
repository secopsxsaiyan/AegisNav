package com.aegisnav.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Red light camera location from bundled GeoJSON asset.
 * Sources: OSM (primary), POI Factory (if POI_FACTORY_COOKIE is set at build time).
 * Unique constraint on lat/lon enables upsert-based idempotent sync.
 */
@Entity(
    tableName = "red_light_cameras",
    indices = [Index(value = ["lat", "lon"], unique = true)]
)
data class RedLightCamera(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val lat: Double,
    val lon: Double,
    val desc: String,
    val source: String = "OSM",
    val state: String = "",
    val verified: Boolean = true,
    val reported: Long = System.currentTimeMillis()
)
