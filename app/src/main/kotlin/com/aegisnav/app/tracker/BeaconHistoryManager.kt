package com.aegisnav.app.tracker

import com.aegisnav.app.data.dao.ScanLogDao
import com.aegisnav.app.data.repository.IgnoreListRepository
import com.aegisnav.app.di.ApplicationScope
import com.aegisnav.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Phase 3 — Features 3.6, 3.7, 3.8, 3.13.
 *
 * Persists ALL non-ignored BLE sightings to [beacon_sightings] (Room).
 *
 * Every [EVAL_INTERVAL_MS] (5 minutes) it checks whether any device has been
 * seen at 3+ distinct GPS clusters over a 2+ hour span — if so, it emits the
 * MAC on [suspiciousDevices] for follow-up alerting.
 *
 * Enhanced risk scoring uses per-type weights:
 *   AirTag=3.0, SmartTag=2.5, Tile=2.0, Apple FindMy=2.0, generic=1.0–1.5.
 *
 * RSSI quality formula (Feature 3.14):
 *   quality% = min(max(2*(rssi+100), 0), 100)
 *
 * Data pruning (Feature 3.13):
 *   - Raw scan_logs: 7 days  (via [ScanLogDao.deleteOlderThan])
 *   - beacon_sightings (non-confirmed): 30 days
 *   - beacon_sightings (confirmed tracker): 90 days
 */
@Singleton
class BeaconHistoryManager @Inject constructor(
    private val beaconSightingDao: BeaconSightingDao,
    private val scanLogDao: ScanLogDao,
    private val ignoreListRepository: IgnoreListRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BeaconHistoryManager"

        /** Periodic evaluation interval (Feature 3.6). */
        const val EVAL_INTERVAL_MS = 5 * 60_000L

        /** Periodic pruning interval. */
        private const val PRUNE_INTERVAL_MS = 6 * 60 * 60_000L  // every 6 hours

        /** Minimum distinct GPS clusters to qualify as a tracking device. */
        const val MIN_LOCATIONS = 3

        /** Minimum observation time span to qualify. */
        const val MIN_DURATION_MS = 2 * 60 * 60_000L  // 2 hours

        /** Greedy clustering radius in metres. */
        const val CLUSTER_RADIUS_M = 60.0

        /** Minimum sightings per cluster to count as a distinct location. */
        const val MIN_SIGHTINGS_PER_CLUSTER = 1

        // Pruning windows (Feature 3.13)
        const val PRUNE_RAW_DAYS          = 7L
        const val PRUNE_NON_CONFIRMED_DAYS = 30L
        const val PRUNE_CONFIRMED_DAYS    = 90L

        /** Threshold sighting count before GATT identification is requested. */
        const val GATT_THRESHOLD_SIGHTINGS = 10
    }

        @Volatile private var ignoreSet: Set<String> = emptySet()

    /**
     * Emits (mac, trackerType) when a device meets the 3+ locations / 2+ hour threshold.
     * Consumers should subscribe and trigger alert notifications.
     */
    private val _suspiciousDevices = MutableSharedFlow<SuspiciousDeviceEvent>(extraBufferCapacity = 16)
    val suspiciousDevices: SharedFlow<SuspiciousDeviceEvent> = _suspiciousDevices

    /** Emits a MAC when GATT identification is requested (sighting count ≥ threshold). */
    private val _gattRequests = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val gattRequests: SharedFlow<String> = _gattRequests

    data class SuspiciousDeviceEvent(
        val mac: String,
        val trackerType: TrackerType,
        val locationCount: Int,
        val durationMs: Long,
        val riskScore: Float
    )

    init {
        // Refresh ignore list periodically
        scope.launch {
            while (true) {
                try {
                    ignoreSet = ignoreListRepository.getAllAddresses().toSet()
                } catch (e: Exception) {
                    AppLog.w(TAG, "Ignore list refresh error: ${e.message}")
                }
                delay(60_000L)
            }
        }

        // Periodic evaluator (Feature 3.6)
        scope.launch {
            while (true) {
                delay(EVAL_INTERVAL_MS)
                try { evaluateAll() } catch (e: Exception) {
                    AppLog.e(TAG, "Evaluation error: ${e.message}")
                }
            }
        }

        // Periodic data pruning (Feature 3.13)
        scope.launch {
            while (true) {
                delay(PRUNE_INTERVAL_MS)
                try { prune() } catch (e: Exception) {
                    AppLog.e(TAG, "Prune error: ${e.message}")
                }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Record a BLE sighting.  Only called for non-ignored devices.
     *
     * @param mac         Device MAC (may be virtual MAC after correlation)
     * @param rssi        Signal strength in dBm
     * @param lat         User GPS latitude (null if unavailable)
     * @param lng         User GPS longitude (null if unavailable)
     * @param trackerType Classified tracker type
     */
    fun onBleSighting(
        mac: String,
        rssi: Int,
        lat: Double?,
        lng: Double?,
        trackerType: TrackerType
    ) {
        if (mac in ignoreSet) return
        val riskScore = computeRiskScore(trackerType, rssi)
        scope.launch {
            try {
                beaconSightingDao.insert(
                    BeaconSighting(
                        mac         = mac,
                        timestamp   = System.currentTimeMillis(),
                        rssi        = rssi,
                        lat         = lat,
                        lng         = lng,
                        trackerType = trackerType.name,
                        riskScore   = riskScore
                    )
                )
                // GATT threshold check
                val count = beaconSightingDao.countForMac(mac)
                if (count == GATT_THRESHOLD_SIGHTINGS) {
                    AppLog.d(TAG, "GATT threshold reached for $mac ($count sightings)")
                    _gattRequests.tryEmit(mac)
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Insert error for $mac: ${e.message}")
            }
        }
    }

    /**
     * Phase 4 — Update the tracker type for the most-recent beacon sighting for [mac].
     *
     * Called after GATT identification resolves a previously-UNKNOWN tracker type.
     * No-op if there are no sightings for the MAC or the new type is UNKNOWN.
     */
    fun updateTrackerType(mac: String, trackerType: TrackerType) {
        if (trackerType == TrackerType.UNKNOWN) return
        scope.launch {
            try {
                val sightings = beaconSightingDao.getForMac(mac)
                val latest = sightings.maxByOrNull { it.timestamp } ?: return@launch
                if (latest.trackerType == trackerType.name) return@launch
                beaconSightingDao.update(latest.copy(trackerType = trackerType.name))
                AppLog.i(TAG, "updateTrackerType mac=$mac → ${trackerType.name}")
            } catch (e: Exception) {
                AppLog.e(TAG, "updateTrackerType error for $mac: ${e.message}")
            }
        }
    }

    /**
     * Feature 3.7 — Enhanced risk scoring.
     *
     * Score = typeWeight × rssiQualityFraction
     *   typeWeight: AirTag=3.0, SmartTag=2.5, Tile=2.0, Apple=2.0, generic=1.0–1.5
     *   rssiQualityFraction: quadratic RSSI quality / 100
     *
     * Result clamped to [0, 10].
     */
    fun computeRiskScore(trackerType: TrackerType, rssi: Int): Float {
        val quality = rssiQualityPercent(rssi) / 100f
        return (trackerType.riskWeight * quality).coerceIn(0f, 10f)
    }

    /**
     * Feature 3.14 — RSSI signal quality percentage.
     *
     * Formula: quality = min(max(2*(rssi+100), 0), 100)
     *
     * Typical values:
     *   rssi = -100 → 0%
     *   rssi = -75  → 50%
     *   rssi = -50  → 100%
     *   rssi = -40  → 100% (clamped)
     */
    fun rssiQualityPercent(rssi: Int): Int = (2 * (rssi + 100)).coerceIn(0, 100)

    // ── Periodic evaluator ────────────────────────────────────────────────────

    private suspend fun evaluateAll() {
        val lookback = System.currentTimeMillis() - MIN_DURATION_MS - EVAL_INTERVAL_MS
        val macs = beaconSightingDao.getDistinctMacsSince(lookback)
        AppLog.d(TAG, "Evaluating ${macs.size} distinct MAC(s)")
        for (mac in macs) {
            if (mac in ignoreSet) continue
            evaluateMac(mac)
        }
    }

    private suspend fun evaluateMac(mac: String) {
        val sightings = beaconSightingDao.getForMac(mac)
        if (sightings.size < MIN_LOCATIONS) return

        val withLocation = sightings.filter { it.lat != null && it.lng != null }
        if (withLocation.size < MIN_LOCATIONS) return

        val firstTs = withLocation.minOf { it.timestamp }
        val lastTs  = withLocation.maxOf { it.timestamp }
        if (lastTs - firstTs < MIN_DURATION_MS) return

        val clusters = clusterLocations(withLocation)
        if (clusters.size < MIN_LOCATIONS) return

        // Mark all sightings for this MAC as confirmed
        beaconSightingDao.markConfirmed(mac)

        val trackerType = withLocation.lastOrNull()?.trackerType
            ?.let { runCatching { TrackerType.valueOf(it) }.getOrDefault(TrackerType.UNKNOWN) }
            ?: TrackerType.UNKNOWN

        val avgRisk = withLocation.map { it.riskScore }.average().toFloat()

        AppLog.i(
            TAG, "Suspicious device $mac: ${clusters.size} locations " +
                "over ${(lastTs - firstTs) / 60_000L} min, type=$trackerType, risk=${"%.2f".format(avgRisk)}"
        )

        _suspiciousDevices.tryEmit(
            SuspiciousDeviceEvent(
                mac          = mac,
                trackerType  = trackerType,
                locationCount = clusters.size,
                durationMs   = lastTs - firstTs,
                riskScore    = avgRisk
            )
        )
    }

    // ── Pruning ───────────────────────────────────────────────────────────────

    /**
     * Prune stale data on all three retention windows (Feature 3.13):
     *   - scan_logs (raw): 7 days
     *   - beacon_sightings non-confirmed: 30 days
     *   - beacon_sightings confirmed: 90 days
     */
    suspend fun prune() {
        val now = System.currentTimeMillis()
        val rawCutoff  = now - PRUNE_RAW_DAYS          * 86_400_000L
        val corrCutoff = now - PRUNE_NON_CONFIRMED_DAYS * 86_400_000L
        val confCutoff = now - PRUNE_CONFIRMED_DAYS    * 86_400_000L

        scanLogDao.deleteOlderThan(rawCutoff)
        beaconSightingDao.pruneNonConfirmed(corrCutoff)
        beaconSightingDao.pruneConfirmed(confCutoff)

        AppLog.d(
            TAG,
            "Prune complete: raw<${PRUNE_RAW_DAYS}d, " +
            "corr<${PRUNE_NON_CONFIRMED_DAYS}d, conf<${PRUNE_CONFIRMED_DAYS}d"
        )
    }

    // ── Clustering ────────────────────────────────────────────────────────────

    private fun clusterLocations(sightings: List<BeaconSighting>): List<Pair<Double, Double>> {
        val centroids = mutableListOf<Pair<Double, Double>>()
        for (s in sightings) {
            val lat = s.lat ?: continue
            val lng = s.lng ?: continue
            if (centroids.none { haversine(it.first, it.second, lat, lng) < CLUSTER_RADIUS_M }) {
                centroids.add(lat to lng)
            }
        }
        return centroids
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2).pow(2)
        return r * 2 * Math.atan2(sqrt(a), sqrt(1 - a))
    }
}
