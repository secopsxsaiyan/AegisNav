package com.aegisnav.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_routes")
data class SavedRoute(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val stopsJson: String,  // JSON array of {name, lat, lon}
    val createdAt: Long = System.currentTimeMillis()
)
