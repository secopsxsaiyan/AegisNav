package com.aegisnav.app.baseline

import androidx.room.Entity
import androidx.room.Index

/**
 * Room entity representing a device's baseline presence profile within a geographic zone.
 *
 * One row per (mac, zoneId) pair.  Updated each time the device is observed in the zone.
 *
 * After [BaselineEngine.LEARNING_PERIOD_MS] has elapsed since [firstSeen], the profile
 * is considered active and newly-appearing devices or off-hours appearances are flagged
 * as anomalies.
 *
 * Zone IDs are computed at 0.001° precision (≈ 100 m) by [BaselineEngine.zoneIdFor].
 */
@Entity(
    tableName = "baseline_devices",
    primaryKeys = ["mac", "zoneId"],
    indices = [Index(value = ["zoneId"])]
)
data class BaselineDevice(
    /** Device MAC address (BLE) or BSSID (WiFi). */
    val mac: String,
    /** Geographic zone identifier — e.g. "37.775:-122.419". */
    val zoneId: String,
    /** Epoch-ms timestamp of the first observation in this zone. */
    val firstSeen: Long,
    /** Epoch-ms timestamp of the most recent observation. */
    val lastSeen: Long,
    /** Total number of observations in this zone. */
    val frequency: Int,
    /**
     * 24-element JSON integer array where index = hour-of-day (0–23) and
     * value = number of times the device was seen during that hour.
     * Used to detect "unusual time" anomalies.
     */
    val typicalHoursJson: String
)
