package com.aegisnav.app.data.repository

import com.aegisnav.app.data.dao.ScanLogDao
import com.aegisnav.app.data.model.ScanLog

class ScanLogRepository(private val dao: ScanLogDao) {

    companion object {
        /** Raw scan entries older than this are pruned on every insert (ring-buffer TTL). */
        private const val SCAN_LOG_TTL_MS = 60L * 60 * 1000  // 1 hour — keeps last hour only
    }

    /**
     * Insert a scan log entry and immediately prune any entries older than the 1-hour TTL.
     * This enforces the ring-buffer contract: raw BLE/WiFi scan data is never persisted
     * beyond the alert-trigger window.
     */
    suspend fun insert(log: ScanLog) {
        dao.insert(log)
        dao.deleteOlderThan(System.currentTimeMillis() - SCAN_LOG_TTL_MS)
    }

    fun getRecent() = dao.getRecent()
    suspend fun deleteAll() = dao.deleteAll()
    fun getRecentAddresses(since: Long) = dao.getRecentAddresses(since)
}