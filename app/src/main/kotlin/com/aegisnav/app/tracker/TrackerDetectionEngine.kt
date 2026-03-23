package com.aegisnav.app.tracker

import android.content.Context
import com.aegisnav.app.util.AppLog
import com.aegisnav.app.data.model.ScanLog
import com.aegisnav.app.data.repository.IgnoreListRepository
import com.aegisnav.app.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Anti-tracking detection engine (AirGuard / CYT-NG style).
 *
 * Triggers a [TrackerAlert] when a BLE or WiFi device MAC is observed via
 * either of two detection paths, within a 30s–30 minute window:
 *
 * - **Path A (cluster-based):** ≥2 distinct GPS location clusters (30m radius),
 *   each with ≥1 sighting, with max spread ≥100m between cluster centroids.
 * - **Path B (continuous following):** ≥3 distinct sighting locations spanning
 *   ≥100m total route distance over ≥30 seconds.
 *
 * Features:
 * - RSSI floor: skips sightings with rssi < MIN_RSSI (-90 dBm)
 * - Location clustering: greedy centroid clustering (CLUSTER_RADIUS_M=30m)
 * - Stationary suppression: no alert if user has been continuously stationary
 *   (no movement >50m between consecutive location updates) for >10 min
 * - Alert cooldown: 5 min between alerts per MAC
 * - Infrastructure OUI: emits alert with LOW confidence instead of skipping
 */
@Singleton
class TrackerDetectionEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ignoreListRepository: IgnoreListRepository,
    @ApplicationScope private val appScope: CoroutineScope
) {
    companion object {
        private const val TAG = "TrackerDetectionEngine"

        const val MIN_STOP_COUNT           = 2
        const val MIN_ROUTE_SPAN_M         = 100.0
        const val MIN_RSSI                 = -90
        const val WINDOW_MIN_MS            = 30_000L             // 30 seconds minimum observation SPAN
        const val WINDOW_MAX_MS            = 30 * 60_000L        // 30 min
        const val MAX_SIGHTINGS            = 50
        const val CLUSTER_RADIUS_M         = 30.0
        const val MIN_SIGHTINGS_PER_STOP   = 1                   // 1 sighting qualifies a location stop
        const val STATIONARY_WINDOW_MS     = 10 * 60_000L        // 10 min
        const val STATIONARY_MAX_STEP_M    = 50.0                // max step size to count as stationary
        const val ALERT_COOLDOWN_MS        = 5 * 60_000L         // 5 min
        // Path B thresholds
        const val PATH_B_MIN_LOCATIONS     = 3
        const val PATH_B_MIN_SPAN_M        = 100.0
        const val PATH_B_MIN_TIME_MS       = 30_000L             // 30 seconds
        private const val ENGINE_PREFS     = "tracker_engine_prefs"
    }

    // per-MAC sighting history (in-memory only)
    private val macSightings = ConcurrentHashMap<String, ArrayDeque<Sighting>>()

    // ignore-list cache, refreshed on each check
    @Volatile private var ignoreSet: Set<String> = emptySet()

    // user GPS ring buffer for stationary suppression
    data class UserLocation(val lat: Double, val lon: Double, val timestamp: Long)
    private val userLocations = ArrayDeque<UserLocation>()
    private val userLocationLock = Any()

    // per-MAC alert cooldowns
    private val alertCooldowns = ConcurrentHashMap<String, Long>()

    private var engineJob = SupervisorJob(appScope.coroutineContext[kotlinx.coroutines.Job])
    private var scope = CoroutineScope(appScope.coroutineContext + engineJob)

    // consumers can collect tracker alerts here
    private val _alerts = MutableSharedFlow<TrackerAlert>(extraBufferCapacity = 16)
    val alerts: SharedFlow<TrackerAlert> = _alerts

    init {
        // Refresh ignore list periodically
        scope.launch {
            while (true) {
                try {
                    ignoreSet = ignoreListRepository.getAllAddresses().toSet()
                } catch (e: Exception) {
                    AppLog.e(TAG, "Ignore list refresh error: ${e.message}")
                }
                kotlinx.coroutines.delay(60_000L)
            }
        }
    }

    /**
     * Called at the start of a scan session.
     * Ensures the coroutine scope is alive (guard against stop/start cycles).
     */
    fun onScanSessionStart() {
        ensureScopeActive()
    }

    /**
     * Called at the end of a scan session. Nothing to flush currently.
     */
    fun onScanSessionEnd() {
        // session ended
    }

    /**
     * Update the user's current GPS position. Used for stationary suppression.
     */
    fun updateUserLocation(lat: Double, lon: Double) {
        val now = System.currentTimeMillis()
        synchronized(userLocationLock) {
            userLocations.addLast(UserLocation(lat, lon, now))
            // Keep only last STATIONARY_WINDOW_MS
            val cutoff = now - STATIONARY_WINDOW_MS
            while (userLocations.isNotEmpty() && userLocations.first().timestamp < cutoff) {
                userLocations.removeFirst()
            }
        }
    }

    /**
     * Feed a new scan entry to the engine.
     */
    fun onScanResult(log: ScanLog) {
        val mac = log.deviceAddress
        val lat = log.lat ?: return
        val lon = log.lng ?: return

        // Skip ignored MACs (user-explicit ignores)
        if (mac in ignoreSet) return

        // Infrastructure OUI check — don't skip, but flag for LOW confidence
        val isInfraOui = OuiLookup.isInfrastructureVendor(context, mac)

        // RSSI floor
        if (log.rssi < MIN_RSSI) return

        val sighting = Sighting(
            timestamp = log.timestamp,
            lat       = lat,
            lon       = lon,
            rssi      = log.rssi,
            ssid      = log.ssid
        )

        val history = macSightings.computeIfAbsent(mac) { ArrayDeque(MAX_SIGHTINGS) }
        synchronized(history) {
            history.addLast(sighting)
            if (history.size > MAX_SIGHTINGS) history.removeFirst()
        }

        scope.launch { evaluate(mac, history, isInfraOui) }
    }

    // ── Evaluation ────────────────────────────────────────────────────────────

    private fun evaluate(mac: String, history: ArrayDeque<Sighting>, isInfraOui: Boolean) {
        val now = System.currentTimeMillis()

        // Collect all sightings within the max window (up to 30 min old)
        val windowSightings = synchronized(history) {
            history.filter { s -> (now - s.timestamp) <= WINDOW_MAX_MS }
        }

        if (windowSightings.isEmpty()) return

        // Require the observation SPAN to be at least WINDOW_MIN_MS (30 s).
        val spanMs = windowSightings.maxOf { it.timestamp } - windowSightings.minOf { it.timestamp }
        if (spanMs < WINDOW_MIN_MS) return

        // ── Stationary suppression ────────────────────────────────────────────
        // Only suppress if the user has been CONTINUOUSLY stationary (no single step
        // > STATIONARY_MAX_STEP_M between consecutive location updates) for the full
        // STATIONARY_WINDOW_MS. Any movement > 50m in the last 10 min overrides suppression.
        val userLocs = synchronized(userLocationLock) { userLocations.toList() }
        if (userLocs.size >= 2) {
            val isContinuouslyStationary = isUserContinuouslyStationary(userLocs)
            if (isContinuouslyStationary) {
                AppLog.d(TAG, "Stationary suppression: user has not moved >50m in last 10 min — suppressed for $mac")
                return
            }
        }

        // ── Path A: cluster-based detection ──────────────────────────────────
        val stops = clusterIntoStops(windowSightings)
        val qualifyingStops = stops.filter { it.sightings.size >= MIN_SIGHTINGS_PER_STOP }
        val centroids = qualifyingStops.map { it.centroidLat to it.centroidLon }
        val clusterSpread = if (centroids.size >= 2) maxPairwiseDistancePoints(centroids) else 0.0
        val pathAFired = qualifyingStops.size >= MIN_STOP_COUNT && clusterSpread >= MIN_ROUTE_SPAN_M

        // ── Path B: continuous following detection ────────────────────────────
        // Device seen at 3+ distinct location updates, spanning ≥100m, over ≥30s.
        val pathBFired = run {
            if (windowSightings.size < PATH_B_MIN_LOCATIONS) return@run false
            val timeSpan = windowSightings.maxOf { it.timestamp } - windowSightings.minOf { it.timestamp }
            if (timeSpan < PATH_B_MIN_TIME_MS) return@run false
            val allPoints = windowSightings.map { it.lat to it.lon }
            val routeSpan = maxPairwiseDistancePoints(allPoints)
            routeSpan >= PATH_B_MIN_SPAN_M
        }

        if (!pathAFired && !pathBFired) return

        AppLog.d(TAG, "Detection triggered for $mac — pathA=$pathAFired pathB=$pathBFired infraOui=$isInfraOui")

        // Alert cooldown
        val lastAlertAt = alertCooldowns[mac] ?: 0L
        if (now - lastAlertAt < ALERT_COOLDOWN_MS) {
            AppLog.d(TAG, "Alert suppressed by cooldown for $mac")
            return
        }
        alertCooldowns[mac] = now

        val allSightings = if (pathAFired) qualifyingStops.flatMap { it.sightings } else windowSightings
        val maxSpread = if (pathAFired) clusterSpread
                        else maxPairwiseDistancePoints(windowSightings.map { it.lat to it.lon })
        val manufacturer = OuiLookup.lookup(context, mac)
        val confidence = if (isInfraOui) "LOW" else "HIGH"

        val alert = TrackerAlert(
            mac              = mac,
            manufacturer     = manufacturer,
            sightings        = allSightings,
            firstSeen        = allSightings.minOf { it.timestamp },
            lastSeen         = allSightings.maxOf { it.timestamp },
            rssiTrend        = allSightings.map { it.rssi },
            stopCount        = qualifyingStops.size,
            maxSpreadMeters  = maxSpread,
            confidence       = confidence
        )

        AppLog.i(TAG, "TrackerAlert: mac=$mac spread=${maxSpread.toInt()}m stops=${qualifyingStops.size} confidence=$confidence pathA=$pathAFired pathB=$pathBFired")
        _alerts.tryEmit(alert)
    }

    // ── Stationary detection ──────────────────────────────────────────────────

    /**
     * Returns true only if the user has been continuously stationary:
     * NO consecutive pair of location updates has a step > STATIONARY_MAX_STEP_M.
     * If any single step exceeds the threshold, the user is considered moving.
     * Requires at least 2 location samples covering the full STATIONARY_WINDOW_MS.
     */
    private fun isUserContinuouslyStationary(locs: List<UserLocation>): Boolean {
        if (locs.size < 2) return false
        val windowStart = locs.first().timestamp
        val windowEnd = locs.last().timestamp
        if ((windowEnd - windowStart) < STATIONARY_WINDOW_MS) return false

        for (i in 0 until locs.size - 1) {
            val step = haversineMeters(locs[i].lat, locs[i].lon, locs[i + 1].lat, locs[i + 1].lon)
            if (step > STATIONARY_MAX_STEP_M) return false
        }
        return true
    }

    // ── Clustering ────────────────────────────────────────────────────────────

    data class Stop(
        val centroidLat: Double,
        val centroidLon: Double,
        val sightings: List<Sighting>
    )

    /**
     * Greedy centroid clustering. Each sighting is assigned to the first existing cluster
     * whose centroid is within CLUSTER_RADIUS_M; otherwise a new cluster is created.
     * After all sightings are assigned, centroids are recalculated.
     */
    private fun clusterIntoStops(sightings: List<Sighting>): List<Stop> {
        data class MutableCluster(
            var centroidLat: Double,
            var centroidLon: Double,
            val sightings: MutableList<Sighting> = mutableListOf()
        )

        val clusters = mutableListOf<MutableCluster>()

        for (s in sightings) {
            val cluster = clusters.firstOrNull { c ->
                haversineMeters(c.centroidLat, c.centroidLon, s.lat, s.lon) <= CLUSTER_RADIUS_M
            }
            if (cluster != null) {
                cluster.sightings.add(s)
                // Recompute centroid
                cluster.centroidLat = cluster.sightings.sumOf { it.lat } / cluster.sightings.size
                cluster.centroidLon = cluster.sightings.sumOf { it.lon } / cluster.sightings.size
            } else {
                clusters.add(MutableCluster(s.lat, s.lon, mutableListOf(s)))
            }
        }

        return clusters.map { Stop(it.centroidLat, it.centroidLon, it.sightings.toList()) }
    }

    // ── Distance utilities ────────────────────────────────────────────────────

    private fun maxPairwiseDistancePoints(points: List<Pair<Double, Double>>): Double {
        var max = 0.0
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                val d = haversineMeters(points[i].first, points[i].second, points[j].first, points[j].second)
                if (d > max) max = d
            }
        }
        return max
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Clears all in-memory sighting history (called when scanning stops).
     */
    fun clear() {
        macSightings.clear()
        alertCooldowns.clear()
        synchronized(userLocationLock) { userLocations.clear() }
    }

    /**
     * Called at the start of a new scan session. Resurrects the coroutine scope
     * if it was previously cancelled (e.g. process-level shutdown).
     * DO NOT call shutdown() from ScanService.onDestroy() - the engine is a
     * @Singleton and must outlive the service.
     */
    fun ensureScopeActive() {
        if (!engineJob.isActive) {
            AppLog.w(TAG, "Engine scope was cancelled - recreating for new scan session")
            engineJob = SupervisorJob(appScope.coroutineContext[kotlinx.coroutines.Job])
            scope = CoroutineScope(appScope.coroutineContext + engineJob)
        }
    }

    /**
     * Permanently cancels the engine. Only for process-level shutdown.
     * Do not call from ScanService.onDestroy().
     */
    fun shutdown() {
        engineJob.cancel()
    }
}
