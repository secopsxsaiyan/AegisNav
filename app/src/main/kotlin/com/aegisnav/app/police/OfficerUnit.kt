package com.aegisnav.app.police

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent officer unit — a group of BLE/WiFi MACs confirmed to belong
 * to the same law enforcement vehicle/officer across multiple locations.
 *
 * [unitId] is city-based: "plantcity0", "plantcity1", "tampa0", etc.
 * [macSet] is comma-separated uppercase MACs.
 * [confirmCount] tracks how many times the user has confirmed sightings
 * from this unit — at ≥3, future sightings are auto-confirmed.
 */
@Immutable
@Entity(tableName = "officer_units")
data class OfficerUnit(
    @PrimaryKey val unitId: String,
    val macSet: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val confirmCount: Int = 0,
    /** How many times the user has tapped ✓ confirm consecutively. Reset on dismiss. */
    val userConfirmTapCount: Int = 0,
    /** How many times the user has tapped ✗ dismiss consecutively. Reset on confirm. */
    val userDismissTapCount: Int = 0,
    /** True once either tap count reaches 3 — verdict is permanent. */
    val verdictLocked: Boolean = false,
    /** The locked verdict: "confirmed" or "dismissed". Null until locked. */
    val lockedVerdict: String? = null,
    /** Epoch-ms of the last user confirm tap. Used to expire confirmed markers after 1 hour. */
    val lastConfirmTimestamp: Long = 0L
)
