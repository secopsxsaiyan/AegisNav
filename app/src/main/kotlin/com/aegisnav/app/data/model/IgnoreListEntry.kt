package com.aegisnav.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "ignore_list", indices = [Index(value = ["address"], unique = true)])
data class IgnoreListEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val address: String,       // MAC address or SSID
    val type: String,          // "BLE" or "WIFI"
    val label: String,         // human-readable name
    val addedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000, // 30-day expiry
    val permanent: Boolean = false  // Phase 3.9: permanent entries survive expiry pruning
)
