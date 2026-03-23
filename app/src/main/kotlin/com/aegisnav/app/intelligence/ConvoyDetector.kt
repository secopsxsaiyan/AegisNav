package com.aegisnav.app.intelligence

import com.aegisnav.app.data.model.ScanLog
import com.aegisnav.app.util.AppLog
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Coordinated Movement (Convoy) Detector — Phase 4.11 (CYT-NG Intelligence).
 *
 * Detects multiple devices moving in the same direction at the same speed —
 * the signature of a vehicle convoy or coordinated mobile surveillance team.
 *
 * **Algorithm:**
 *
 * 1. For each device, compute velocity vectors from consecutive located sightings.
 *    A vector has: bearing (0–360°), speed (m/s), and start timestamp.
 *
 * 2. A vector is valid if:
 *    - The displacement distance is ≥ [MIN_VECTOR_DIST_M] (20 m).
 *    - The time between sightings is ≤ [MAX_VECTOR_INTERVAL_MS] (5 minutes).
 *
 * 3. Two devices' vector sequences are correlated over the last [VECTOR_WINDOW_MS]
 *    (15 minutes) if:
 *    - They share ≥ [MIN_CORRELATED_VECTORS] (3) temporally aligned vectors.
 *    - Each aligned pair has bearing difference ≤ [MAX_BEARING_DIFF_DEG] (30°).
 *    - Each aligned pair has speed ratio in [1/MAX_SPEED_RATIO, MAX_SPEED_RATIO] (0.5–2.0×).
 *
 * 4. A [ConvoyAlert] is emitted when ≥2 devices form a correlated movement group.
 */
@Singleton
class ConvoyDetector @Inject constructor() {

    companion object {
        private const val TAG = "ConvoyDetector"

        /** Minimum displacement to compute a valid velocity vector. */
        const val MIN_VECTOR_DIST_M = 20.0

        /** Maximum time between two sightings used to form a vector. */
        const val MAX_VECTOR_INTERVAL_MS = 5 * 60_000L     // 5 minutes

        /** Analysis window: vectors older than this are discarded. */
        const val VECTOR_WINDOW_MS = 15 * 60_000L          // 15 minutes

        /** Minimum number of correlated sequential vectors required to flag a convoy. */
        const val MIN_CORRELATED_VECTORS = 3

        /** Maximum bearing difference (degrees) between two "same direction" vectors. */
        const val MAX_BEARING_DIFF_DEG = 30.0

        /** Maximum speed ratio between two "same speed" vectors. */
        const val MAX_SPEED_RATIO = 2.0

        /** Minimum team size for convoy alert. */
        const val MIN_CONVOY_SIZE = 2

        /** Cap on per-device vector history. */
        private const val MAX_VECTORS_PER_DEVICE = 50
    }

    data class VelocityVector(
        val bearingDeg: Double,      // 0–360° clockwise from north
        val speedMps: Double,        // metres per second
        val timestampMs: Long        // when this vector was computed (end of displacement)
    )

    data class ConvoyAlert(
        val deviceGroup: Set<String>,
        val correlatedVectors: Int,
        val avgBearingDeg: Double,
        val avgSpeedMps: Double,
        val timestamp: Long
    )

    /** Per-device: last located sighting (for vector computation). */
    private val lastLocated = ConcurrentHashMap<String, ScanLog>()

    /** Per-device: vector history. */
    private val vectorHistory = ConcurrentHashMap<String, ArrayDeque<VelocityVector>>()

    /**
     * Feed a new scan result. Returns a [ConvoyAlert] if a convoy is detected, otherwise null.
     *
     * **Speed gate:** If [userSpeedMps] is ≤ 1.5 m/s the user is stationary; convoy analysis
     * is skipped immediately to prevent false positives from parked nearby devices.
     *
     * **Per-device speed:** Velocity vectors use speed computed from consecutive position
     * fixes for each device (haversine distance / time delta), NOT the user's GPS speed.
     * This prevents false convoys from co-observed stationary devices while driving.
     * [userSpeedMps] is only used for the stationary gate.
     *
     * @param log        The incoming BLE/WiFi scan observation.
     * @param userSpeedMps GPS speed of the user in metres per second (0 if unavailable). Used only for stationary gate.
     * @return A [ConvoyAlert] if a convoy is confirmed, otherwise null.
     */
    fun onScanResult(log: ScanLog, userSpeedMps: Float = 0f): ConvoyAlert? {
        if (userSpeedMps <= 1.5f) return null

        val lat = log.lat ?: return null
        val lon = log.lng ?: return null
        val mac = log.deviceAddress
        val now = log.timestamp

        val prev = lastLocated[mac]
        lastLocated[mac] = log

        if (prev != null) {
            val prevLat = prev.lat ?: return null
            val prevLon = prev.lng ?: return null
            val dtMs = now - prev.timestamp

            if (dtMs in 1..MAX_VECTOR_INTERVAL_MS) {
                val dist = haversineMeters(prevLat, prevLon, lat, lon)
                if (dist >= MIN_VECTOR_DIST_M) {
                    val bearing = computeBearing(prevLat, prevLon, lat, lon)
                    // Per-device speed from consecutive position fixes — avoids false convoys
                    // where all co-observed devices inherit the user's GPS speed.
                    val speed = dist / (dtMs / 1000.0)
                    val vector = VelocityVector(bearing, speed, now)

                    val history = vectorHistory.computeIfAbsent(mac) { ArrayDeque(MAX_VECTORS_PER_DEVICE) }
                    synchronized(history) {
                        history.addLast(vector)
                        if (history.size > MAX_VECTORS_PER_DEVICE) history.removeFirst()
                        pruneOldVectors(history, now)
                    }
                    AppLog.d(TAG, "Vector mac=$mac bearing=${bearing.toInt()}° speed=${speed.toInt()}m/s")
                }
            }
        }

        // Check for convoy among all devices
        return detectConvoy(now)
    }

    /**
     * Detect a convoy among all devices with sufficient vector history.
     * Returns the first confirmed alert found, or null.
     */
    fun detectConvoy(nowMs: Long): ConvoyAlert? {
        val activeMacs = vectorHistory.entries
            .filter { (_, hist) -> synchronized(hist) { hist.size >= MIN_CORRELATED_VECTORS } }
            .map { it.key }

        if (activeMacs.size < MIN_CONVOY_SIZE) return null

        // Try all pairs of devices
        for (i in activeMacs.indices) {
            for (j in i + 1 until activeMacs.size) {
                val macA = activeMacs[i]
                val macB = activeMacs[j]
                val corr = correlatedVectorCount(macA, macB, nowMs)
                if (corr >= MIN_CORRELATED_VECTORS) {
                    // Assemble convoy group
                    val group = findConvoyGroup(macA, macB, activeMacs, nowMs)
                    if (group.size >= MIN_CONVOY_SIZE) {
                        val stats = avgMovement(group, nowMs)
                        val alert = ConvoyAlert(
                            deviceGroup        = group,
                            correlatedVectors  = corr,
                            avgBearingDeg      = stats.first,
                            avgSpeedMps        = stats.second,
                            timestamp          = nowMs
                        )
                        AppLog.i(TAG, "ConvoyAlert group=$group vectors=$corr " +
                                "bearing=${stats.first.toInt()}° speed=${stats.second.toInt()}m/s")
                        return alert
                    }
                }
            }
        }
        return null
    }

    // ── Exposed for testing ───────────────────────────────────────────────────

    /** How many temporally-aligned, direction-correlated vectors do [macA] and [macB] share? */
    fun correlatedVectorCount(macA: String, macB: String, nowMs: Long): Int {
        val histA = vectorHistory[macA]?.let { synchronized(it) { it.toList() } } ?: return 0
        val histB = vectorHistory[macB]?.let { synchronized(it) { it.toList() } } ?: return 0

        var count = 0
        for (vA in histA) {
            // Find a contemporaneous vector in B (within half the max interval)
            val vB = histB.minByOrNull { abs(it.timestampMs - vA.timestampMs) } ?: continue
            val timeDiff = abs(vB.timestampMs - vA.timestampMs)
            if (timeDiff > MAX_VECTOR_INTERVAL_MS / 2) continue

            val bearingDiff = bearingDifference(vA.bearingDeg, vB.bearingDeg)
            if (bearingDiff > MAX_BEARING_DIFF_DEG) continue

            val speedRatio = if (vB.speedMps > 0) vA.speedMps / vB.speedMps else Double.MAX_VALUE
            if (speedRatio > MAX_SPEED_RATIO || speedRatio < 1.0 / MAX_SPEED_RATIO) continue

            count++
        }
        return count
    }

    /** Manually inject a vector (for testing). */
    fun injectVector(mac: String, vector: VelocityVector) {
        val history = vectorHistory.computeIfAbsent(mac) { ArrayDeque(MAX_VECTORS_PER_DEVICE) }
        synchronized(history) { history.addLast(vector) }
    }

    /** Clear all state. */
    fun clear() {
        lastLocated.clear()
        vectorHistory.clear()
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun findConvoyGroup(
        seedA: String, seedB: String,
        candidates: List<String>,
        nowMs: Long
    ): Set<String> {
        val group = mutableSetOf(seedA, seedB)
        for (mac in candidates) {
            if (mac == seedA || mac == seedB) continue
            val corrWithA = correlatedVectorCount(seedA, mac, nowMs)
            if (corrWithA >= MIN_CORRELATED_VECTORS) group.add(mac)
        }
        return group
    }

    private fun avgMovement(macs: Set<String>, nowMs: Long): Pair<Double, Double> {
        val bearings = mutableListOf<Double>()
        val speeds   = mutableListOf<Double>()
        for (mac in macs) {
            val hist = vectorHistory[mac]?.let { synchronized(it) { it.toList() } } ?: continue
            val recent = hist.filter { (nowMs - it.timestampMs) <= VECTOR_WINDOW_MS }
            bearings.addAll(recent.map { it.bearingDeg })
            speeds.addAll(recent.map { it.speedMps })
        }
        val avgBearing = if (bearings.isEmpty()) 0.0 else bearings.average()
        val avgSpeed   = if (speeds.isEmpty()) 0.0 else speeds.average()
        return avgBearing to avgSpeed
    }

    private fun pruneOldVectors(history: ArrayDeque<VelocityVector>, nowMs: Long) {
        val cutoff = nowMs - VECTOR_WINDOW_MS
        while (history.isNotEmpty() && history.first().timestampMs < cutoff) {
            history.removeFirst()
        }
    }

    /** Angular difference between two bearings, in [0, 180]. */
    private fun bearingDifference(a: Double, b: Double): Double {
        val diff = abs(a - b) % 360.0
        return if (diff > 180.0) 360.0 - diff else diff
    }

    private fun computeBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360.0) % 360.0
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
