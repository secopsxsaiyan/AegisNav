package com.aegisnav.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A device MAC address that the user has marked "always alert on".
 * When a watched device is seen during scanning, [WatchlistAlertManager]
 * fires a TTS alert and creates a HIGH-threat WATCHLIST event.
 * Repeated sightings within a 15-minute window are grouped into the same event.
 */
@Entity(tableName = "watchlist")
data class WatchlistEntry(
    @PrimaryKey val mac: String,
    val type: String,        // "BLE", "WIFI", etc.
    val label: String,       // user-visible label
    val createdAt: Long = System.currentTimeMillis()
)
