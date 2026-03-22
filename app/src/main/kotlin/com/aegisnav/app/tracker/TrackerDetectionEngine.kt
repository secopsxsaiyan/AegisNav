package com.aegisnav.app.tracker

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.aegisnav.app.security.SecureDataStore
import androidx.datastore.preferences.core.edit
import com.aegisnav.app.security.editBlocking
import kotlinx.coroutines.flow.first
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
 * Triggers a [TrackerAlert] when a BLE or WiFi device MAC is observed at
 * 4+ distinct GPS location clusters each qualifying with ≥2 sightings,
 * with max spread ≥250m across cluster centroids, within a 5–30 minute window.
 *
 * Features:
 * - RSSI floor: skips sightings with rssi < MIN_RSSI (-85 dBm)
 * - Location clustering: greedy centroid clustering (CLUSTER_RADIUS_M=60m)
 * - Stationary suppression: no alert if user hasn't moved >100m in last 10 min
 * - Alert cooldown: 5 min between alerts per MAC
 * - Companion device suggestion: tracks devices seen at session start across sessions
 */
@Singleton
class TrackerDetectionEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ignoreListRepository: IgnoreListRepository,
    @ApplicationScope private val appScope: CoroutineScope
) {
    companion object {
        private const val TAG = "TrackerDetectionEngine"

        const val MIN_STOP_COUNT           = 4
        const val MIN_ROUTE_SPAN_M         = 250.0
        const val MIN_RSSI                 = -85
        const val WINDOW_MIN_MS            = 5  * 60_000L   // 5 min — minimum observation SPAN required
        const val WINDOW_MAX_MS            = 30 * 60_000L   // 30 min
        const val MAX_SIGHTINGS            = 50
        const val CLUSTER_RADIUS_M         = 60.0
        const val MIN_SIGHTINGS_PER_STOP   = 1              // 1 sighting qualifies a location stop
        const val STATIONARY_WINDOW_MS     = 10 * 60_000L   // 10 min
        const val STATIONARY_THRESHOLD_M   = 100.0
        const val ALERT_COOLDOWN_MS        = 5 * 60_000L    // 5 min
        const val SESSION_START_WINDOW_MS  = 60_000L        // 60 s
        const val COMPANION_SESSION_THRESHOLD = 3
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

    // session-start tracking for companion device suggestion
    @Volatile private var sessionStartTime: Long = 0L

    private var engineJob = SupervisorJob(appScope.coroutineContext[kotlinx.coroutines.Job])
    private var scope = CoroutineScope(appScope.coroutineContext + engineJob)

    private val dataStore: DataStore<Preferences> by lazy {
        SecureDataStore.get(context, ENGINE_PREFS)
    }

    // consumers can collect tracker alerts here
    private val _alerts = MutableSharedFlow<TrackerAlert>(extraBufferCapacity = 16)
    val alerts: SharedFlow<TrackerAlert> = _alerts

    // companion device suggestions
    private val _suggestedIgnore = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val suggestedIgnore: SharedFlow<String> = _suggestedIgnore

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
     * Called at the start of a scan session to track companion devices.
     * Also ensures the coroutine scope is alive (guard against stop/start cycles).
     */
    fun onScanSessionStart() {
        ensureScopeActive()
        sessionStartTime = System.currentTimeMillis()
    }

    /**
     * Called at the end of a scan session. Nothing to flush currently.
     */
    fun onScanSessionEnd() {
        // session ended - sessionStartTime stays set until next session starts
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

        // Skip ignored MACs
        if (mac in ignoreSet) return

        // Skip infrastructure OUIs
        if (OuiLookup.isInfrastructureVendor(context, mac)) return

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

        // Companion device tracking: devices seen in first 60s of session
        val sessionStart = sessionStartTime
        if (sessionStart > 0L && (log.timestamp - sessionStart) <= SESSION_START_WINDOW_MS) {
            scope.launch { trackCompanionDevice(mac) }
        }

        scope.launch { evaluate(mac, history) }
    }

    // ── Companion device logic ─────────────────────────────────────────────

    private suspend fun trackCompanionDevice(mac: String) {
        val prefKey = intPreferencesKey("companion_$mac")
        val count = (dataStore.data.first()[prefKey] ?: 0) + 1
        dataStore.editBlocking { this[prefKey] = count }
        if (count >= COMPANION_SESSION_THRESHOLD) {
            _suggestedIgnore.tryEmit(mac)
        }
    }

    /**
     * Clears companion session data from DataStore.
     */
    fun clearCompanionData() {
        scope.launch { dataStore.edit { it.clear() } }
    }

    // ── Evaluation ────────────────────────────────────────────────────────────

    private fun evaluate(mac: String, history: ArrayDeque<Sighting>) {
        val now = System.currentTimeMillis()

        // Collect all sightings within the max window (up to 30 min old)
        val windowSightings = synchronized(history) {
            history.filter { s -> (now - s.timestamp) <= WINDOW_MAX_MS }
        }

        if (windowSightings.isEmpty()) return

        // Require the observation SPAN to be at least WINDOW_MIN_MS (5 min).
        // This prevents alerts from brief encounters without discarding recent sightings.
        val spanMs = windowSightings.maxOf { it.timestamp } - windowSightings.minOf { it.timestamp }
        if (spanMs < WINDOW_MIN_MS) return

        // Cluster sightings into stops
        val stops = clusterIntoStops(windowSightings)

        // Filter to qualifying stops (≥ MIN_SIGHTINGS_PER_STOP sightings)
        val qualifyingStops = stops.filter { it.sightings.size >= MIN_SIGHTINGS_PER_STOP }

        if (qualifyingStops.size < MIN_STOP_COUNT) return

        // Check max pairwise distance between stop centroids
        val centroids = qualifyingStops.map { it.centroidLat to it.centroidLon }
        val maxSpread = maxPairwiseDistancePoints(centroids)
        if (maxSpread < MIN_ROUTE_SPAN_M) return

        // Stationary suppression: skip if user hasn't moved enough in the last 10 min.
        // Exception: if the device spread already meets MIN_ROUTE_SPAN_M, the device has
        // demonstrably followed the user across distance — alert regardless (covers traffic scenarios
        // where both user and device are moving slowly together).
        val userLocs = synchronized(userLocationLock) { userLocations.toList() }
        if (userLocs.size >= 2) {
            val userSpread = maxPairwiseDistancePoints(userLocs.map { it.lat to it.lon })
            if (userSpread < STATIONARY_THRESHOLD_M && maxSpread < MIN_ROUTE_SPAN_M * 1.5) {
                AppLog.d(TAG, "Stationary suppression: user spread=${userSpread.toInt()}m, device spread=${maxSpread.toInt()}m — suppressed")
                return
            }
        }

        // Alert cooldown
        val lastAlertAt = alertCooldowns[mac] ?: 0L
        if (now - lastAlertAt < ALERT_COOLDOWN_MS) {
            AppLog.d(TAG, "Alert suppressed by cooldown for $mac")
            return
        }
        alertCooldowns[mac] = now

        val allSightings = qualifyingStops.flatMap { it.sightings }
        val manufacturer = OuiLookup.lookup(context, mac)
        val alert = TrackerAlert(
            mac              = mac,
            manufacturer     = manufacturer,
            sightings        = allSightings,
            firstSeen        = allSightings.minOf { it.timestamp },
            lastSeen         = allSightings.maxOf { it.timestamp },
            rssiTrend        = allSightings.map { it.rssi },
            stopCount        = qualifyingStops.size,
            maxSpreadMeters  = maxSpread
        )

        AppLog.i(TAG, "TrackerAlert: mac=$mac spread=${maxSpread.toInt()}m stops=${qualifyingStops.size}")
        _alerts.tryEmit(alert)
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
