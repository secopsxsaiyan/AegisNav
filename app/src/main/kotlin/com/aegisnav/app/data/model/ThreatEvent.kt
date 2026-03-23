package com.aegisnav.app.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted record of a threat detection event.
 *
 * Only high-level alert data is stored here - raw scan entries are
 * never written to disk (ring-buffer only).
 *
 * [type] values: "TRACKER", "BLE_CORRELATION", "ALPR_PROXIMITY"
 * [detailJson] contains a JSON snapshot of the alert payload for display in ThreatHistoryScreen.
 */
@Immutable
@Entity(tableName = "threat_events")
data class ThreatEvent(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val type: String,              // "TRACKER" | "BLE_CORRELATION" | "ALPR_PROXIMITY"
    val mac: String,               // device MAC (may be empty for non-device alerts)
    val timestamp: Long,           // epoch ms of detection
    val detailJson: String         // JSON blob for TrackerAlert / threat bundle
)
