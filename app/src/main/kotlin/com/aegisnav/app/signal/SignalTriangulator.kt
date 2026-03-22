package com.aegisnav.app.signal

import com.aegisnav.app.util.AppLog
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Signal triangulator — time-series single-device trilateration.
 *
 * Collects (observer_lat, observer_lon, estimated_distance) tuples for a single
 * device MAC as the user moves through space.  Once [MIN_OBSERVATIONS] distinct
 * positions are available, weighted least-squares trilateration estimates the
 * device's probable physical location.
 *
 * Quality gate: GDOP (Geometric Dilution of Precision) must be ≤ [GDOP_THRESHOLD]
 * (default 6.0).  Poor geometry is automatically discarded.
 *
 * The estimated position is further smoothed by a 2D Kalman filter (two
 * independent 1-D filters on lat/lon encoded as integers in µdeg to avoid
 * floating-point precision issues in the KalmanFilter internals).
 */
@Singleton
class SignalTriangulator @Inject constructor() {

    companion object {
        private const val TAG = "SignalTriangulator"

        const val MIN_OBSERVATIONS  = 3      // minimum distinct points needed
        const val MAX_OBSERVATIONS  = 20     // ring buffer size per MAC
        const val GDOP_THRESHOLD    = 6.0    // discard if GDOP > this
        private const val OBS_EXPIRY_MS = 5 * 60_000L   // expire observations older than 5 min

        // Kalman parameters for position smoothing (in µdeg units)
        private const val POS_KALMAN_Q = 2.0    // process noise (device may move ~2m/step)
        private const val POS_KALMAN_R = 25.0   // measurement noise (~5m std dev)
    }

    // ── Public data model ─────────────────────────────────────────────────────

    data class Observation(
        val observerLat: Double,
        val observerLon: Double,
        val estimatedDistanceM: Double,
        val timestamp: Long
    )

    data class TriangulationResult(
        val mac: String,
        /** Kalman-smoothed estimated device position. */
        val estimatedLat: Double,
        val estimatedLon: Double,
        /** Uncertainty radius in metres (GDOP-inflated RMS residual). */
        val radiusMeters: Double,
        val gdop: Double,
        val timestamp: Long,
        val observationCount: Int,
        /**
         * Threat category for map icon selection.
         * "POLICE" = law enforcement equipment match
         * "ALPR"   = position within 50 m of an ALPR camera
         * null     = generic device (not rendered on map)
         */
        val deviceCategory: String? = null,
        /** Wall-clock time (ms) when this result was first created. Used for 1-hour TTL. */
        val createdAtMs: Long = System.currentTimeMillis()
    )

    // ── Internal state ────────────────────────────────────────────────────────

    private val observations = ConcurrentHashMap<String, ArrayDeque<Observation>>()
    private val latFilters   = ConcurrentHashMap<String, KalmanFilter>()
    private val lonFilters   = ConcurrentHashMap<String, KalmanFilter>()
    private val _results     = ConcurrentHashMap<String, TriangulationResult>()

    /** Live snapshot of all valid triangulation results keyed by device MAC. */
    val currentResults: Map<String, TriangulationResult> get() = _results

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Record a new observation for [mac].
     *
     * @param mac                  Device MAC address
     * @param observerLat          User's GPS latitude when the observation was made
     * @param observerLon          User's GPS longitude
     * @param estimatedDistanceM   RSSI-derived estimated distance in metres
     */
    fun addObservation(mac: String, observerLat: Double, observerLon: Double, estimatedDistanceM: Double) {
        if (estimatedDistanceM <= 0) return
        val now = System.currentTimeMillis()
        val obs = Observation(observerLat, observerLon, estimatedDistanceM, now)

        val deque = observations.computeIfAbsent(mac) { ArrayDeque(MAX_OBSERVATIONS) }
        synchronized(deque) {
            deque.addLast(obs)
            if (deque.size > MAX_OBSERVATIONS) deque.removeFirst()
            // Prune expired observations
            val cutoff = now - OBS_EXPIRY_MS
            while (deque.isNotEmpty() && deque.first().timestamp < cutoff) deque.removeFirst()
        }

        tryTriangulate(mac)
    }

    /**
     * Update the [TriangulationResult.deviceCategory] for a known MAC.
     *
     * Called by ScanService after determining whether a device maps to a police
     * sighting ("POLICE"), an ALPR camera location ("ALPR"), or neither (null).
     * No-op if no result exists for [mac].
     */
    fun setDeviceCategory(mac: String, category: String?) {
        _results[mac]?.let { existing ->
            _results[mac] = existing.copy(deviceCategory = category)
        }
    }

    /**
     * Remove triangulation results that are older than [maxAgeMs] milliseconds.
     * Called periodically (every 5 min) by ScanService to enforce the 1-hour TTL.
     */
    fun pruneExpired(maxAgeMs: Long = 3_600_000L) {
        val now = System.currentTimeMillis()
        val expired = _results.entries
            .filter { (_, result) -> now - result.createdAtMs > maxAgeMs }
            .map { it.key }
        expired.forEach { mac ->
            _results.remove(mac)
            AppLog.d(TAG, "Pruned expired triangulation result for $mac")
        }
    }

    /**
     * Remove a specific device's triangulation result (user dismissed it from the map).
     * Retains the raw observation history but removes it from [currentResults].
     */
    fun dismissDevice(mac: String) {
        _results.remove(mac)
        AppLog.d(TAG, "Dismissed triangulation result for $mac")
    }

    /** Remove all state for a device (e.g. after it leaves the area). */
    fun clearDevice(mac: String) {
        observations.remove(mac)
        latFilters.remove(mac)
        lonFilters.remove(mac)
        _results.remove(mac)
    }

    /** Clear all accumulated state. */
    fun clearAll() {
        observations.clear()
        latFilters.clear()
        lonFilters.clear()
        _results.clear()
    }

    // ── Trilateration pipeline ────────────────────────────────────────────────

    private fun tryTriangulate(mac: String) {
        val obsList = synchronized(observations[mac] ?: return) {
            (observations[mac] ?: return).toList()
        }
        if (obsList.size < MIN_OBSERVATIONS) return

        // Convert to local East-North (metres) using centroid as origin
        val originLat = obsList.map { it.observerLat }.average()
        val originLon = obsList.map { it.observerLon }.average()
        val mPerLat   = 110574.0
        val mPerLon   = 110574.0 * cos(Math.toRadians(originLat))

        val localPoints = obsList.map { o ->
            val ex = (o.observerLon - originLon) * mPerLon
            val ey = (o.observerLat - originLat) * mPerLat
            Triple(ex, ey, o.estimatedDistanceM)
        }

        // Trilaterate
        val (estX, estY, residuals) = trilaterate(localPoints) ?: return

        // GDOP quality gate
        val gdop = calculateGdop(localPoints.map { it.first to it.second }, estX, estY)
        if (gdop > GDOP_THRESHOLD) {
            AppLog.d(TAG, "mac=$mac GDOP=${"%.2f".format(gdop)} > $GDOP_THRESHOLD → discarded")
            return
        }

        // Convert local metres back to WGS84
        val rawEstLat = originLat + estY / mPerLat
        val rawEstLon = originLon + estX / mPerLon

        // Kalman-smooth position (work in µdeg to keep values comfortably sized)
        val latFilter = latFilters.computeIfAbsent(mac) {
            KalmanFilter(POS_KALMAN_Q, POS_KALMAN_R, rawEstLat * 1e6)
        }
        val lonFilter = lonFilters.computeIfAbsent(mac) {
            KalmanFilter(POS_KALMAN_Q, POS_KALMAN_R, rawEstLon * 1e6)
        }
        val filtLat = latFilter.update(rawEstLat * 1e6) / 1e6
        val filtLon = lonFilter.update(rawEstLon * 1e6) / 1e6

        // Uncertainty radius = GDOP-inflated RMS residual
        val rmsResidual = sqrt(residuals.sumOf { it * it } / residuals.size)
        val radiusM     = (rmsResidual * gdop).coerceIn(5.0, 250.0)

        val result = TriangulationResult(
            mac              = mac,
            estimatedLat     = filtLat,
            estimatedLon     = filtLon,
            radiusMeters     = radiusM,
            gdop             = gdop,
            timestamp        = System.currentTimeMillis(),
            observationCount = obsList.size
        )
        _results[mac] = result

        AppLog.d(
            TAG, "Triangulated $mac → (${"%.5f".format(filtLat)}, ${"%.5f".format(filtLon)}) " +
                "±${radiusM.toInt()}m GDOP=${"%.2f".format(gdop)} n=${obsList.size}"
        )
    }

    // ── WLS Trilateration ─────────────────────────────────────────────────────

    /**
     * Iterative weighted least-squares trilateration.
     *
     * Returns (estX, estY, residuals) in local ENU metres, or null if the
     * geometry is degenerate.
     */
    private fun trilaterate(
        points: List<Triple<Double, Double, Double>>  // (x, y, dist) metres
    ): Triple<Double, Double, List<Double>>? {
        if (points.isEmpty()) return null

        // Initial guess: distance-weighted centroid (closer = higher weight)
        val totalW = points.sumOf { 1.0 / it.third.coerceAtLeast(1.0) }
        var estX = points.sumOf { it.first  / it.third.coerceAtLeast(1.0) } / totalW
        var estY = points.sumOf { it.second / it.third.coerceAtLeast(1.0) } / totalW

        repeat(10) { iter ->
            points.size
            var h00 = 0.0; var h01 = 0.0; var h11 = 0.0
            var b0  = 0.0; var b1  = 0.0

            for ((xi, yi, di) in points) {
                val dx = estX - xi
                val dy = estY - yi
                val d  = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01)
                val hx = dx / d
                val hy = dy / d
                val residual = di - d
                val w = 1.0 / di.coerceAtLeast(0.1)   // weight inversely by declared distance

                h00 += hx * hx * w
                h01 += hx * hy * w
                h11 += hy * hy * w
                b0  += hx * residual * w
                b1  += hy * residual * w
            }

            // Solve 2×2 normal equations
            val det = h00 * h11 - h01 * h01
            if (abs(det) < 1e-10) return null

            val dx = (h11 * b0 - h01 * b1) / det
            val dy = (h00 * b1 - h01 * b0) / det
            estX += dx
            estY += dy
            if (abs(dx) < 0.001 && abs(dy) < 0.001) return@repeat
        }

        val residuals = points.map { (xi, yi, di) ->
            val d = sqrt((estX - xi).pow(2) + (estY - yi).pow(2))
            di - d
        }
        return Triple(estX, estY, residuals)
    }

    // ── GDOP calculation ──────────────────────────────────────────────────────

    /**
     * 2D Geometric Dilution of Precision.
     *
     * GDOP = sqrt(trace((H^T H)^-1)) where H is the unit-vector direction
     * matrix from each observer to the estimated device position.
     */
    private fun calculateGdop(
        observers: List<Pair<Double, Double>>,  // (x, y) in local metres
        estX: Double, estY: Double
    ): Double {
        var h00 = 0.0; var h01 = 0.0; var h11 = 0.0

        for ((xi, yi) in observers) {
            val dx = estX - xi
            val dy = estY - yi
            val d  = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01)
            val hx = dx / d
            val hy = dy / d
            h00 += hx * hx
            h01 += hx * hy
            h11 += hy * hy
        }

        val det = h00 * h11 - h01 * h01
        if (abs(det) < 1e-10) return Double.MAX_VALUE

        // trace((H^T H)^-1) = (h00 + h11) / det
        return sqrt(abs((h00 + h11) / det))
    }
}
