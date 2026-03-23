package com.aegisnav.app.intelligence

import com.aegisnav.app.data.model.ScanLog
import com.aegisnav.app.util.AppLog
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Coordinated Surveillance Detector — Phase 4.4 (CYT-NG Intelligence).
 *
 * Detects groups of ≥2 devices that appear at the same locations within a tight
 * time window, across ≥3 distinct locations — the signature of organized team surveillance.
 *
 * **Algorithm:**
 *
 * 1. Maintains a sliding 30-minute window of location events per device.
 * 2. On each new sighting, checks if any other recently-seen device was at the same
 *    ≈25 m grid cell within [COLOCATE_WINDOW_MS] (2 minutes).
 * 3. Two devices that co-locate at ≥[MIN_SHARED_LOCATIONS] (3) distinct cells with
 *    a time correlation score > [CORRELATION_THRESHOLD] (0.7) are flagged as a group.
 * 4. Threat score multiplier: 1 + 0.5 × (teamSize − 1).
 *
 * @see CoordinatedAlert emitted when a group is confirmed.
 */
@Singleton
class CoordinatedSurveillanceDetector @Inject constructor() {

    companion object {
        private const val TAG = "CoordinatedSurveillanceDetector"

        /** Time window for co-location check. */
        const val COLOCATE_WINDOW_MS = 2 * 60_000L        // 2 minutes

        /** Maximum observation window kept in memory per device. */
        const val OBSERVATION_WINDOW_MS = 30 * 60_000L    // 30 minutes

        /** Minimum distinct locations at which the group must co-appear. */
        const val MIN_SHARED_LOCATIONS = 3

        /** Minimum Pearson-like correlation between two devices' location timelines. */
        const val CORRELATION_THRESHOLD = 0.70

        /** Minimum group size to trigger an alert. */
        const val MIN_TEAM_SIZE = 2

        /** Grid cell size in degrees (≈25 m). */
        private const val GRID_DEG = 0.00025
    }

    data class CoordinatedAlert(
        val deviceGroup: Set<String>,
        val sharedCells: Set<String>,
        val correlationScore: Double,
        val threatMultiplier: Double,
        val timestamp: Long
    )

    /** Per-device chronological list of (gridCell, timestamp) observations. */
    private val deviceHistory = ConcurrentHashMap<String, ArrayDeque<CellSighting>>()

    private data class CellSighting(val cellId: String, val timestampMs: Long)

    /**
     * Feed a new scan result into the detector.
     *
     * **Speed gate:** If [userSpeedMps] is ≤ 1.5 m/s the user is considered stationary
     * (parked, on foot, etc.) and coordinated-surveillance analysis is skipped immediately.
     * This prevents false positives caused by neighbouring devices that happen to share
     * grid cells while the user is not moving. The 1.5 m/s threshold mirrors the course-up
     * gate used in MainActivity (`if (loc.speed > 1.5f)`).
     *
     * @param log        The incoming BLE/WiFi scan observation.
     * @param userSpeedMps GPS speed of the user in metres per second (0 if unavailable).
     * @return A [CoordinatedAlert] if a coordinated group is confirmed, otherwise null.
     */
    fun onScanResult(log: ScanLog, userSpeedMps: Float = 0f): CoordinatedAlert? {
        if (userSpeedMps <= 1.5f) return null
        val lat = log.lat ?: return null
        val lon = log.lng ?: return null
        val mac = log.deviceAddress
        val now = log.timestamp
        val cellId = gridCell(lat, lon)

        // Record this sighting
        val history = deviceHistory.computeIfAbsent(mac) { ArrayDeque() }
        synchronized(history) {
            history.addLast(CellSighting(cellId, now))
            pruneOld(history, now)
        }

        // Look for other devices at the same cell within COLOCATE_WINDOW_MS
        val cellWindow = ConcurrentHashMap.newKeySet<String>()   // other MACs co-located here
        for ((otherMac, otherHistory) in deviceHistory) {
            if (otherMac == mac) continue
            val colocated = synchronized(otherHistory) {
                otherHistory.any { s ->
                    s.cellId == cellId && abs(s.timestampMs - now) <= COLOCATE_WINDOW_MS
                }
            }
            if (colocated) cellWindow.add(otherMac)
        }

        if (cellWindow.isEmpty()) return null

        // Check full group correlation across all candidate partners
        val candidates = cellWindow.toMutableSet().also { it.add(mac) }
        return evaluateGroup(candidates, now)
    }

    private fun evaluateGroup(macs: Set<String>, nowMs: Long): CoordinatedAlert? {
        if (macs.size < MIN_TEAM_SIZE) return null

        // Build (cellId → set of MACs that visited it) within OBSERVATION_WINDOW_MS
        val cellToMacs = mutableMapOf<String, MutableSet<String>>()
        for (mac in macs) {
            val hist = deviceHistory[mac] ?: continue
            synchronized(hist) {
                for (cs in hist) {
                    cellToMacs.getOrPut(cs.cellId) { mutableSetOf() }.add(mac)
                }
            }
        }

        // Shared cells: visited by ≥2 devices in the group
        val sharedCells = cellToMacs.filter { (_, macSet) -> macSet.size >= MIN_TEAM_SIZE }
            .keys.toSet()

        if (sharedCells.size < MIN_SHARED_LOCATIONS) return null

        val correlation = computeGroupCorrelation(macs, sharedCells)
        if (correlation < CORRELATION_THRESHOLD) return null

        val teamSize = macs.size
        val multiplier = 1.0 + 0.5 * (teamSize - 1)

        AppLog.i(TAG, "CoordinatedAlert group=$macs sharedCells=${sharedCells.size} " +
                "corr=$correlation multiplier=$multiplier")

        return CoordinatedAlert(
            deviceGroup      = macs,
            sharedCells      = sharedCells,
            correlationScore = correlation,
            threatMultiplier = multiplier,
            timestamp        = nowMs
        )
    }

    /**
     * Compute a group correlation score based on shared cell visit ratio.
     *
     * Score = (shared cells visited by ALL group members) / (total distinct cells).
     */
    private fun computeGroupCorrelation(macs: Set<String>, sharedCells: Set<String>): Double {
        val allCells = mutableSetOf<String>()
        for (mac in macs) {
            val hist = deviceHistory[mac] ?: continue
            synchronized(hist) { hist.forEach { allCells.add(it.cellId) } }
        }
        if (allCells.isEmpty()) return 0.0

        // Cells visited by every member of the group
        val universalCells = sharedCells.filter { cell ->
            macs.all { mac ->
                val hist = deviceHistory[mac] ?: return@all false
                synchronized(hist) { hist.any { it.cellId == cell } }
            }
        }.toSet()

        return universalCells.size.toDouble() / allCells.size
    }

    private fun pruneOld(history: ArrayDeque<CellSighting>, nowMs: Long) {
        val cutoff = nowMs - OBSERVATION_WINDOW_MS
        while (history.isNotEmpty() && history.first().timestampMs < cutoff) {
            history.removeFirst()
        }
    }

    private fun gridCell(lat: Double, lon: Double): String {
        val latBucket = (lat / GRID_DEG).toLong()
        val lonBucket = (lon / GRID_DEG).toLong()
        return "$latBucket:$lonBucket"
    }

    /** Clear all in-memory state (e.g. when scanning stops). */
    fun clear() {
        deviceHistory.clear()
    }
}
