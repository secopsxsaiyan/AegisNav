package com.aegisnav.app.correlation

import com.aegisnav.app.util.AppLog
import com.aegisnav.app.data.model.Report
import com.aegisnav.app.data.model.ScanLog
import com.aegisnav.app.data.repository.ReportsRepository
import com.aegisnav.app.data.repository.ScanLogRepository
import com.aegisnav.app.di.ApplicationScope
import com.aegisnav.app.p2p.P2PManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Correlates user-submitted reports with recent BLE/WiFi scan data from the ring buffer.
 * On a match: persists correlated logs to DB, computes threat level, broadcasts to P2P.
 * Unmatched ring buffer entries are discarded after 5-minute TTL.
 */
@Singleton
class CorrelationEngine @Inject constructor(
    private val scanLogRepository: ScanLogRepository,
    private val reportsRepository: ReportsRepository,
    private val p2pManager: P2PManager,
    @ApplicationScope private val scope: CoroutineScope
) {
    companion object {
        const val CORRELATION_WINDOW_BACK_MS  = 2 * 60 * 1000L  // 2 min back
        const val CORRELATION_WINDOW_AHEAD_MS = 30 * 1000L       // 30 sec forward
        const val CORRELATION_RADIUS_M        = 500.0            // 500 metres
        const val RING_BUFFER_TTL_MS          = 5 * 60 * 1000L
    }

    // In-memory ring buffer: BLE + WiFi events for last 5 min (thread-safe)
    private val ringBuffer = LinkedBlockingDeque<ScanLog>(500)
        /**
     * Called by ScanService for every BLE/WiFi result - never writes to DB here.
     * Ignored-address filtering is done in ScanService before calling this method.
     */
    fun addToRingBuffer(log: ScanLog) {
        val now = System.currentTimeMillis()
        // Evict expired entries - use peekFirst() (nullable) to avoid NPE on concurrent drain
        while (true) {
            val head = ringBuffer.peekFirst() ?: break
            if (now - head.timestamp <= RING_BUFFER_TTL_MS) break
            ringBuffer.pollFirst()
        }
        // offerLast drops the entry if capacity (500) is exceeded rather than throwing
        ringBuffer.offerLast(log)
    }

    /**
     * Triggered when user submits a report.
     * Correlates with ring buffer entries within time + distance window.
     * Persists correlated logs, assigns threat level, broadcasts to P2P.
     */
    fun correlate(report: Report) {
        scope.launch {
            try {
                val reportTime = System.currentTimeMillis()
                val cutoff = reportTime - CORRELATION_WINDOW_BACK_MS

                // Immediate backward pass - snapshot first to avoid concurrent modification
                val snapshotBack = synchronized(ringBuffer) { ringBuffer.toList() }
                val backwardCorrelated = snapshotBack.filter { log ->
                    val lat = log.lat ?: return@filter false
                    val lng = log.lng ?: return@filter false
                    log.timestamp in cutoff..reportTime &&
                    haversineMeters(report.latitude, report.longitude, lat, lng) <= CORRELATION_RADIUS_M
                }

                AppLog.i("CorrelationEngine", "Report ${report.type}: ${backwardCorrelated.size} backward correlated logs")

                val savedId = reportsRepository.insert(report).toInt()
                backwardCorrelated.forEach { scanLogRepository.insert(it.copy(correlatedReportId = savedId)) }

                val threat = computeThreatLevel(report, backwardCorrelated)
                p2pManager.broadcastReport(P2PReportBundle(report, backwardCorrelated, threat, reportTime))

                // Forward 30-second pass - snapshot first to avoid concurrent modification
                kotlinx.coroutines.delay(CORRELATION_WINDOW_AHEAD_MS)
                val snapshotFwd = synchronized(ringBuffer) { ringBuffer.toList() }
                val forwardCorrelated = snapshotFwd.filter { log ->
                    val lat = log.lat ?: return@filter false
                    val lng = log.lng ?: return@filter false
                    log.timestamp > reportTime &&
                    log.timestamp <= reportTime + CORRELATION_WINDOW_AHEAD_MS &&
                    haversineMeters(report.latitude, report.longitude, lat, lng) <= CORRELATION_RADIUS_M
                }
                if (forwardCorrelated.isNotEmpty()) {
                    AppLog.i("CorrelationEngine", "Report ${report.type}: ${forwardCorrelated.size} forward correlated logs")
                    forwardCorrelated.forEach { scanLogRepository.insert(it.copy(correlatedReportId = savedId)) }
                    // Re-broadcast with updated evidence if threat level changed
                    val updatedThreat = computeThreatLevel(report, backwardCorrelated + forwardCorrelated)
                    if (updatedThreat != threat) {
                        p2pManager.broadcastReport(P2PReportBundle(report, backwardCorrelated + forwardCorrelated, updatedThreat, reportTime))
                    }
                }
            } catch (e: Exception) {
                AppLog.e("CorrelationEngine", "Correlation failed", e)
            }
        }
    }

    fun computeThreatLevel(report: Report, correlatedLogs: List<ScanLog>): ThreatLevel {
        var score = 0

        // Base score from report type
        score += when (report.type) {
            "POLICE", "CHECKPOINT" -> 3
            "ALPR"                 -> 2
            "TRACKER"              -> 2
            "SURVEILLANCE"         -> 1
            else                   -> 1
        }

        // Bonus for correlated BLE trackers
        if (correlatedLogs.any { it.isTracker }) score += 2

        // Bonus for volume of correlated signals
        score += (correlatedLogs.size / 5).coerceAtMost(3)

        return when {
            score >= 6 -> ThreatLevel.HIGH
            score >= 3 -> ThreatLevel.MEDIUM
            else       -> ThreatLevel.LOW
        }
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

enum class ThreatLevel { LOW, MEDIUM, HIGH }

data class P2PReportBundle(
    val report: Report,
    val correlatedLogs: List<ScanLog>,
    val threatLevel: ThreatLevel,
    val timestamp: Long
)
