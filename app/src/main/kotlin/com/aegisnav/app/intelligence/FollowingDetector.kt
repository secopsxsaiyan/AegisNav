package com.aegisnav.app.intelligence

import com.aegisnav.app.data.dao.ScanLogDao
import com.aegisnav.app.di.ApplicationScope
import com.aegisnav.app.data.model.ScanLog
import com.aegisnav.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Multi-Location Following Detector — Phase 4.2 (CYT-NG Intelligence).
 *
 * Detects devices that follow the user across multiple geofence zones by querying
 * the [beacon_sightings] database (mapped to scan_logs) for cross-session presence.
 *
 * **Detection logic:**
 *
 * 1. *Multi-location* — device seen at ≥2 distinct 0.001° geofence zones within 24 hours.
 *
 * 2. *Active mobile following* — the device disappears from zone A and reappears at zone B
 *    within [TRANSITION_WINDOW_MS] (30 min), where zones are >[FOLLOWING_MIN_DIST_M] (1 km) apart.
 *
 * Results are persisted in the [following_events] table and emitted via [events] SharedFlow.
 *
 * Call [runAnalysis] periodically (e.g. every 5 minutes) to check the last 24 hours of data.
 */
@Singleton
class FollowingDetector @Inject constructor(
    private val scanLogDao: ScanLogDao,
    private val followingEventDao: FollowingEventDao,
    @ApplicationScope private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "FollowingDetector"

        /** Analysis window: look back this far for multi-location evidence. */
        const val ANALYSIS_WINDOW_MS = 24 * 60 * 60_000L      // 24 hours

        /** A device must appear in ≥2 distinct zones within the window to be flagged. */
        const val MIN_ZONES_FOR_FOLLOWING = 2

        /** Transition window: device appears at B within this many ms of leaving A. */
        const val TRANSITION_WINDOW_MS = 30 * 60_000L         // 30 minutes

        /** Minimum inter-zone distance (metres) required for mobile-following flag. */
        const val FOLLOWING_MIN_DIST_M = 1_000.0              // 1 km

        /** Zone bucket precision (degrees) — matches BaselineEngine (≈100 m). */
        private const val ZONE_PRECISION = 3                   // 0.001° ≈ 111 m
    }

        private val _events = MutableSharedFlow<FollowingEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<FollowingEvent> = _events

    /**
     * Run a full following-analysis pass over the last 24 hours of scan_logs.
     * Emits a [FollowingEvent] for each newly discovered following pattern.
     *
     * Should be called periodically (e.g. every 5 minutes) by ScanService.
     */
    fun runAnalysis() {
        scope.launch {
            try {
                val since = System.currentTimeMillis() - ANALYSIS_WINDOW_MS
                val sightings = scanLogDao.getLocatedSightingsSince(since)
                if (sightings.isEmpty()) return@launch

                val byMac = sightings.groupBy { it.deviceAddress }
                AppLog.d(TAG, "runAnalysis: ${sightings.size} sightings across ${byMac.size} MACs")

                for ((mac, macSightings) in byMac) {
                    analyse(mac, macSightings)
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "runAnalysis error: ${e.message}")
            }
        }
    }

    /**
     * Analyse a single device's sightings for following patterns.
     * Exposed internal for unit testing (suspend version accepting pre-fetched data).
     */
    suspend fun analyseDevice(mac: String, sightings: List<ScanLog>): List<FollowingEvent> {
        return analyse(mac, sightings)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun analyse(mac: String, sightings: List<ScanLog>): List<FollowingEvent> {
        val sorted = sightings.sortedBy { it.timestamp }

        // Bucket sightings by zone ID
        val zoneTimelines = mutableMapOf<String, MutableList<ScanLog>>()
        for (s in sorted) {
            val lat = s.lat ?: continue
            val lng = s.lng ?: continue
            val zoneId = zoneIdFor(lat, lng)
            zoneTimelines.getOrPut(zoneId) { mutableListOf() }.add(s)
        }

        val uniqueZones = zoneTimelines.keys.toList()
        if (uniqueZones.size < MIN_ZONES_FOR_FOLLOWING) return emptyList()

        val emitted = mutableListOf<FollowingEvent>()

        // Check all zone pairs
        for (i in uniqueZones.indices) {
            for (j in i + 1 until uniqueZones.size) {
                val zoneA = uniqueZones[i]
                val zoneB = uniqueZones[j]
                val sightingsA = zoneTimelines[zoneA] ?: continue
                val sightingsB = zoneTimelines[zoneB] ?: continue

                val dist = zoneCentroidDistance(zoneA, zoneB)

                // Multi-location: device present in both zones within 24h
                val event = buildMultiLocationEvent(mac, zoneA, zoneB, sightingsA, sightingsB, dist)
                    ?: continue

                // Upgrade to MOBILE_FOLLOWING if transition was fast
                val finalEvent = if (dist >= FOLLOWING_MIN_DIST_M &&
                    event.transitionMinutes <= TRANSITION_WINDOW_MS / 60_000.0
                ) {
                    event.copy(followingType = FollowingEvent.TYPE_MOBILE_FOLLOWING)
                } else {
                    event
                }

                followingEventDao.insert(finalEvent)
                _events.tryEmit(finalEvent)
                emitted.add(finalEvent)
                AppLog.i(TAG, "FollowingEvent mac=$mac type=${finalEvent.followingType} " +
                        "dist=${dist.toInt()}m transition=${finalEvent.transitionMinutes.toInt()}min")
            }
        }
        return emitted
    }

    private fun buildMultiLocationEvent(
        mac: String,
        zoneA: String,
        zoneB: String,
        sightingsA: List<ScanLog>,
        sightingsB: List<ScanLog>,
        distMeters: Double
    ): FollowingEvent? {
        val lastAtA  = sightingsA.maxOf { it.timestamp }
        val firstAtA = sightingsA.minOf { it.timestamp }
        val firstAtB = sightingsB.minOf { it.timestamp }

        // B must be observed after A started
        if (firstAtB <= firstAtA) return null

        val transitionMs = firstAtB - lastAtA
        val transitionMin = transitionMs / 60_000.0

        return FollowingEvent(
            id                = UUID.randomUUID().toString(),
            mac               = mac,
            firstZoneId       = zoneA,
            secondZoneId      = zoneB,
            firstSeenAtA      = firstAtA,
            lastSeenAtA       = lastAtA,
            firstSeenAtB      = firstAtB,
            distanceMeters    = distMeters,
            transitionMinutes = transitionMin,
            followingType     = FollowingEvent.TYPE_MULTI_LOCATION,
            timestamp         = System.currentTimeMillis()
        )
    }

    // ── Zone utilities ────────────────────────────────────────────────────────

    /** Convert lat/lon to 0.001°-precision zone ID string. */
    fun zoneIdFor(lat: Double, lon: Double): String {
        val latRound = Math.round(lat * 1000) / 1000.0
        val lonRound = Math.round(lon * 1000) / 1000.0
        return "$latRound:$lonRound"
    }

    /** Parse zone centroid from zone ID and return haversine distance. */
    private fun zoneCentroidDistance(zoneA: String, zoneB: String): Double {
        val (latA, lonA) = parsezone(zoneA)
        val (latB, lonB) = parsezone(zoneB)
        return haversineMeters(latA, lonA, latB, lonB)
    }

    private fun parsezone(zoneId: String): Pair<Double, Double> {
        val parts = zoneId.split(":")
        return parts[0].toDouble() to parts[1].toDouble()
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
