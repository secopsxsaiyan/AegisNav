package com.aegisnav.app.data.model

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "reports")
data class Report(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val type: String,
    val subtype: String? = null,
    @ColumnInfo(name = "sub_option") val subOption: String? = null,
    @ColumnInfo(name = "is_group") val isGroup: Boolean = false,
    val latitude: Double,
    val longitude: Double,
    val description: String,
    val timestamp: Long = System.currentTimeMillis(),
    val threatLevel: String = "LOW",
    val confirmedCount: Int = 0,
    val clearedCount: Int = 0,
    /** Local user verdict: null = unreviewed, "confirmed", or "dismissed". Once set, cannot be changed. */
    @ColumnInfo(name = "user_verdict") val userVerdict: String? = null
)
