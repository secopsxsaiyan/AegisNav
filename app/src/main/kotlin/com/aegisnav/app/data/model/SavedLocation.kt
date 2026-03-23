package com.aegisnav.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_locations")
data class SavedLocation(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val lat: Double,
    val lon: Double,
    val type: String,   // "HOME" | "WORK" | "STORE" | "GAS" | "CUSTOM"
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
