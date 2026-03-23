package com.aegisnav.app.signal

import com.aegisnav.app.util.AppLog
import com.aegisnav.app.util.GeoUtils
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
        private const val OPPORTUNISTIC_PRUNE_INTERVAL_MS = 5 * 60_000L  // prune at most every 5 min

        // Minimum baseline distance between observations for a valid triangulation
        const val MIN_BASELINE_M = 100.0

        // Position history constants for stationary/moving classification
        private const val POS_HISTORY_MAX = 10
        private const val POS_HISTORY_MAX_AGE_MS = 10 * 60_000L  // 10 minutes
        private const val STATIONARY_DRIFT_THRESHOLD_M = 20.0

        // RSSI stability scoring thresholds (dBm²)
        private const val RSSI_STABLE_VARIANCE_THRESHOLD = 5.0   // below this = stable
        private const val RSSI_NOISY_VARIANCE_THRESHOLD  = 15.0  // above this = noisy

        // Observation diversity requirements
        private const val MIN_DISTINCT_POSITIONS = 3          // must have observations from 3+ grid cells
        private const val POSITION_GRID_SIZE_M   = 50.0       // cluster observations into 50m cells
        private const val MIN_TIME_SPAN_MS       = 2 * 60_000L // observations must span at least 2 minutes
    }

    // ── Public data model ─────────────────────────────────────────────────────

    enum class ConfidenceTier { LOW, MEDIUM, HIGH }

    data class Observation(
        val observerLat: Double,
        val observerLon: Double,
        val estimatedDistanceM: Double,
        val timestamp: Long,
        val rssi: Int? = null
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
        val createdAtMs: Long = System.currentTimeMillis(),
        /** Maximum distance (metres) between any two observer positions used in triangulation. */
        val maxBaselineM: Double = 0.0,
        /** Confidence tier based on observation count, baseline, and GDOP. */
        val confidenceTier: ConfidenceTier = ConfidenceTier.LOW,
        /** Maximum positional drift (metres) between oldest and newest tracked position. */
        val positionDriftMeters: Double = 0.0,
        /** True if the device appears to be stationary (drift < 20m). */
        val isStationary: Boolean = true,
        /** RSSI variance across all observations with non-null RSSI (dBm²). */
        val rssiVariance: Double = 0.0,
        /** True if rssiVariance < RSSI_STABLE_VARIANCE_THRESHOLD. */
        val rssiStable: Boolean = false,
        /** Number of distinct 50m grid cells covered by observer positions. */
        val distinctPositions: Int = 0,
        /** Time span (ms) between oldest and newest observation. */
        val timeSpanMs: Long = 0
    )

    // ── Internal state ────────────────────────────────────────────────────────

    private val observations   = ConcurrentHashMap<String, ArrayDeque<Observation>>()
    private val latFilters     = ConcurrentHashMap<String, KalmanFilter>()
    private val lonFilters     = ConcurrentHashMap<String, KalmanFilter>()
    private val _results       = ConcurrentHashMap<String, TriangulationResult>()

    /**
     * Position history for stationary/moving classification.
     * Key = MAC, Value = deque of (timestamp, (lat, lon)) pairs.
     */
    private val positionHistory = ConcurrentHashMap<String, ArrayDeque<Pair<Long, Pair<Double, Double>>>>()

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
    /** Throttle for opportunistic pruning inside [addObservation]. */
    @Volatile private var lastOpportunisticPruneMs = 0L

    fun addObservation(
        mac: String,
        observerLat: Double,
        observerLon: Double,
        estimatedDistanceM: Double,
        rssi: Int? = null
    ) {
        addObservationAt(mac, observerLat, observerLon, estimatedDistanceM, rssi, System.currentTimeMillis())
    }

    /**
     * Internal entry point that accepts an explicit timestamp.
     * Exposed as [internal] so unit tests can inject fake timestamps to satisfy
     * the [MIN_TIME_SPAN_MS] diversity gate without real delays.
     */
    internal fun addObservationAt(
        mac: String,
        observerLat: Double,
        observerLon: Double,
        estimatedDistanceM: Double,
        rssi: Int? = null,
        timestampMs: Long
    ) {
        if (estimatedDistanceM <= 0) return
        val now = timestampMs

        // Opportunistic prune: remove stale results every 5 min during active scanning.
        // This ensures ghost markers are cleaned even if ScanService restarts.
        if (now - lastOpportunisticPruneMs > OPPORTUNISTIC_PRUNE_INTERVAL_MS) {
            lastOpportunisticPruneMs = now
            pruneExpired()
        }

        val obs = Observation(observerLat, observerLon, estimatedDistanceM, now, rssi)

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
        positionHistory.remove(mac)
    }

    /** Clear all accumulated state. */
    fun clearAll() {
        observations.clear()
        latFilters.clear()
        lonFilters.clear()
        _results.clear()
        positionHistory.clear()
    }

    // ── Trilateration pipeline ────────────────────────────────────────────────

    private fun tryTriangulate(mac: String) {
        val obsList = synchronized(observations[mac] ?: return) {
            (observations[mac] ?: return).toList()
        }
        if (obsList.size < MIN_OBSERVATIONS) return

        // ── Observation diversity gate (must come BEFORE baseline check) ──────
        // Bucket each observation into a 50m grid cell and count unique cells.
        val metersPerDegLat = 110574.0
        val centLat = obsList.map { it.observerLat }.average()
        val metersPerDegLon = 110574.0 * cos(Math.toRadians(centLat))
        val distinctPositions = obsList.map { o ->
            val cellX = floor(o.observerLon * metersPerDegLon / POSITION_GRID_SIZE_M).toLong()
            val cellY = floor(o.observerLat * metersPerDegLat / POSITION_GRID_SIZE_M).toLong()
            Pair(cellX, cellY)
        }.toSet().size

        val timeSpanMs = obsList.last().timestamp - obsList.first().timestamp

        if (distinctPositions < MIN_DISTINCT_POSITIONS || timeSpanMs < MIN_TIME_SPAN_MS) {
            AppLog.d(
                TAG,
                "mac=$mac diversity fail: positions=$distinctPositions timeSpan=${timeSpanMs}ms → skipped"
            )
            return
        }

        // Baseline check: maximum distance between any two observer positions
        val baseline = maxBaselineMeters(obsList)
        if (baseline < MIN_BASELINE_M) {
            AppLog.d(TAG, "mac=$mac baseline=${"%.1f".format(baseline)}m < $MIN_BASELINE_M → discarded")
            return
        }

        // ── RSSI stability scoring ─────────────────────────────────────────────
        val rssiValues = obsList.mapNotNull { it.rssi }
        val (rssiVariance, rssiStable) = if (rssiValues.size >= 2) {
            val mean     = rssiValues.map { it.toDouble() }.average()
            val variance = rssiValues.map { (it - mean).pow(2) }.average()
            variance to (variance < RSSI_STABLE_VARIANCE_THRESHOLD)
        } else {
            0.0 to false
        }

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

        // Velocity-aware weights: observations closer to the user's current position
        // (newest observation's observer location) are weighted more heavily.
        val newestObs = obsList.last()
        val velocityWeights = obsList.map { o ->
            val userDistFromObs = GeoUtils.haversineMeters(
                o.observerLat, o.observerLon,
                newestObs.observerLat, newestObs.observerLon
            )
            1.0 / (1.0 + userDistFromObs / 500.0)
        }

        // Trilaterate with velocity-aware weights
        val (estX, estY, residuals) = trilaterate(localPoints, velocityWeights) ?: return

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

        // ── Confidence tier ───────────────────────────────────────────────────
        val baseTier = when {
            obsList.size >= 8 && baseline >= 500.0 && gdop < 2.0 -> ConfidenceTier.HIGH
            obsList.size >= 5 && baseline >= 200.0 && gdop < 5.0 -> ConfidenceTier.MEDIUM
            else -> ConfidenceTier.LOW
        }
        // Boost MEDIUM → HIGH when RSSI is stable and geometry is close to HIGH criteria
        val confidenceTier = if (baseTier == ConfidenceTier.MEDIUM && rssiStable &&
            obsList.size >= 6 && baseline >= 300.0 && gdop < 4.0
        ) {
            ConfidenceTier.HIGH
        } else {
            baseTier
        }

        // ── Position history / stationary classification ───────────────────────
        val now = System.currentTimeMillis()
        val history = positionHistory.computeIfAbsent(mac) { ArrayDeque(POS_HISTORY_MAX) }
        synchronized(history) {
            history.addLast(Pair(now, Pair(filtLat, filtLon)))
            // Trim to max size
            while (history.size > POS_HISTORY_MAX) history.removeFirst()
            // Prune entries older than 10 minutes
            val ageCutoff = now - POS_HISTORY_MAX_AGE_MS
            while (history.isNotEmpty() && history.first().first < ageCutoff) history.removeFirst()
        }

        val (positionDrift, isStationary) = synchronized(history) {
            if (history.size < 2) {
                Pair(0.0, true)
            } else {
                val oldest = history.first().second
                val newest = history.last().second
                val drift = GeoUtils.haversineMeters(oldest.first, oldest.second, newest.first, newest.second)
                Pair(drift, drift < STATIONARY_DRIFT_THRESHOLD_M)
            }
        }

        val result = TriangulationResult(
            mac                 = mac,
            estimatedLat        = filtLat,
            estimatedLon        = filtLon,
            radiusMeters        = radiusM,
            gdop                = gdop,
            timestamp           = now,
            observationCount    = obsList.size,
            maxBaselineM        = baseline,
            confidenceTier      = confidenceTier,
            positionDriftMeters = positionDrift,
            isStationary        = isStationary,
            rssiVariance        = rssiVariance,
            rssiStable          = rssiStable,
            distinctPositions   = distinctPositions,
            timeSpanMs          = timeSpanMs
        )
        _results[mac] = result

        AppLog.d(
            TAG, "Triangulated $mac → (${"%.5f".format(filtLat)}, ${"%.5f".format(filtLon)}) " +
                "±${radiusM.toInt()}m GDOP=${"%.2f".format(gdop)} n=${obsList.size} " +
                "baseline=${"%.0f".format(baseline)}m tier=$confidenceTier " +
                "drift=${"%.1f".format(positionDrift)}m stationary=$isStationary " +
                "rssiVar=${"%.2f".format(rssiVariance)} rssiStable=$rssiStable " +
                "cells=$distinctPositions span=${timeSpanMs}ms"
        )
    }

    // ── Baseline helper ───────────────────────────────────────────────────────

    /**
     * Returns the maximum haversine distance (metres) between any two observer
     * positions in [observations].
     */
    private fun maxBaselineMeters(observations: List<Observation>): Double {
        var maxDist = 0.0
        for (i in observations.indices) {
            for (j in i + 1 until observations.size) {
                val d = GeoUtils.haversineMeters(
                    observations[i].observerLat, observations[i].observerLon,
                    observations[j].observerLat, observations[j].observerLon
                )
                if (d > maxDist) maxDist = d
            }
        }
        return maxDist
    }

    // ── WLS Trilateration ─────────────────────────────────────────────────────

    /**
     * Iterative weighted least-squares trilateration.
     *
     * [velocityWeights] is a per-observation multiplier (0..1] that down-weights
     * observations taken far from the user's current position.  Combined with
     * the existing inverse-distance weight for numerical stability.
     *
     * Returns (estX, estY, residuals) in local ENU metres, or null if the
     * geometry is degenerate.
     */
    private fun trilaterate(
        points: List<Triple<Double, Double, Double>>,  // (x, y, dist) metres
        velocityWeights: List<Double> = List(points.size) { 1.0 }
    ): Triple<Double, Double, List<Double>>? {
        if (points.isEmpty()) return null

        // Initial guess: distance-weighted centroid (closer = higher weight)
        val totalW = points.sumOf { 1.0 / it.third.coerceAtLeast(1.0) }
        var estX = points.sumOf { it.first  / it.third.coerceAtLeast(1.0) } / totalW
        var estY = points.sumOf { it.second / it.third.coerceAtLeast(1.0) } / totalW

        repeat(10) { iter ->
            var h00 = 0.0; var h01 = 0.0; var h11 = 0.0
            var b0  = 0.0; var b1  = 0.0

            for (idx in points.indices) {
                val (xi, yi, di) = points[idx]
                val vw = velocityWeights.getOrElse(idx) { 1.0 }
                val dx = estX - xi
                val dy = estY - yi
                val d  = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01)
                val hx = dx / d
                val hy = dy / d
                val residual = di - d
                // Combined weight: inverse declared distance × velocity proximity weight
                val w = (1.0 / di.coerceAtLeast(0.1)) * vw

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
