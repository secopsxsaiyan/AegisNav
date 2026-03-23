package com.aegisnav.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for the `debug_log` table.
 *
 * Stores structured log entries containing sensitive data (e.g. MAC addresses)
 * inside the SQLCipher-encrypted AppDatabase instead of writing to logcat.
 *
 * Pruning: entries older than 7 days are removed via [DebugLogDao.pruneOlderThan].
 */
@Entity(tableName = "debug_log")
data class DebugLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val tag: String,
    val message: String,
    val level: String   // "D", "I", "W", "E"
)
