package com.aegisnav.app.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Immutable
@Entity(
    tableName = "alpr_blocklist",
    indices = [Index(value = ["lat", "lon"], unique = true)]
)
data class ALPRBlocklist(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val lat: Double,
    val lon: Double,
    val ssid: String?,
    val mac: String?,
    val desc: String,
    val reported: Long,
    val verified: Boolean = false,
    @androidx.room.ColumnInfo(name = "source") val source: String = "OSM",
    @androidx.room.ColumnInfo(name = "state") val state: String = ""
)
