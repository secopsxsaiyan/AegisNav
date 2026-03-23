package com.aegisnav.app.util

import com.aegisnav.app.data.dao.DebugLogDao
import com.aegisnav.app.data.model.DebugLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SecureLogger — writes sensitive structured log entries (e.g. containing raw MAC addresses)
 * to the SQLCipher-encrypted [AppDatabase] `debug_log` table instead of Android logcat.
 *
 * This prevents sensitive device identifiers from appearing in logcat where they could
 * be captured by log-forwarding tools (e.g. Crashlytics custom logging, ADB).
 *
 * Pruning: entries older than 7 days are purged on each write cycle.
 *
 * Usage:
 * ```kotlin
 * secureLogger.d("MyTag", "GATT connected to ${device.address}")
 * secureLogger.i("MyTag", "Tracker MAC: ${alert.mac}")
 * ```
 */
@Singleton
class SecureLogger @Inject constructor(
    private val debugLogDao: DebugLogDao
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sevenDaysMs = 7L * 24 * 60 * 60 * 1000

    fun d(tag: String, message: String) = write("D", tag, message)
    fun i(tag: String, message: String) = write("I", tag, message)
    fun w(tag: String, message: String) = write("W", tag, message)
    fun e(tag: String, message: String) = write("E", tag, message)

    private fun write(level: String, tag: String, message: String) {
        scope.launch {
            val now = System.currentTimeMillis()
            debugLogDao.insert(
                DebugLogEntry(
                    timestamp = now,
                    tag = tag,
                    message = message,
                    level = level
                )
            )
            // Prune entries older than 7 days
            debugLogDao.pruneOlderThan(now - sevenDaysMs)
        }
    }
}
