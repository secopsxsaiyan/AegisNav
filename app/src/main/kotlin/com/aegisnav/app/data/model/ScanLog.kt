package com.aegisnav.app.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "scan_logs")
data class ScanLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val deviceAddress: String,
    val rssi: Int,
    val timestamp: Long,
    val isTracker: Boolean,
    val manufacturerDataHex: String?,
    val scanType: String,          // "BLE" or "WIFI"
    val lat: Double?,
    val lng: Double?,
    val correlatedReportId: Int? = null,  // set when this log is correlated with a user report
    val ssid: String? = null,             // WiFi network name; null for BLE
    val serviceUuids: String? = null,     // BLE advertised service UUIDs, comma-separated
    val deviceName: String? = null,       // BLE advertised device name
    val trackerType: String? = null       // Phase 3: classified tracker type (TrackerType.name)
)
