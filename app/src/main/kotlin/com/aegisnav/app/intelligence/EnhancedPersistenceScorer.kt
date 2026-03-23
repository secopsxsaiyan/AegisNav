package com.aegisnav.app.intelligence

import com.aegisnav.app.data.model.ScanLog
import com.aegisnav.app.util.AppLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Enhanced Persistence Scorer — Phase 4.1 (CYT-NG Intelligence).
 *
 * Replaces the legacy [MacPersistenceScorer] with a normalized 0.0–1.0 score
 * incorporating three new signal dimensions:
 *
 *  1. **Frequency weight** — sightingsPerHour / expectedSightingsPerHour (baseline 4/hr).
 *     Rewards devices that appear more often than a typical background device.
 *
 *  2. **Recency bias** — sightings in the last 5 minutes are counted twice (2× weight)
 *     to prioritise devices that are still nearby.
 *
 *  3. **Duration-in-zone** — for cop detections only, the continuous time the device
 *     has been present inside a ≈100 m zone amplifies the score (longer stakeout = higher score).
 *
 * **Thresholds:**
 *   - Tracker: score ≥ [TRACKER_THRESHOLD] (0.6)
 *   - Cop:     score ≥ [COP_THRESHOLD]     (0.4)
 */
@Singleton
class EnhancedPersistenceScorer @Inject constructor() {

    companion object {
        private const val TAG = "EnhancedPersistenceScorer"

        /** Tracker detection threshold (0.0–1.0). */
        const val TRACKER_THRESHOLD = 0.60

        /** Cop/surveillance detection threshold (0.0–1.0). */
        const val COP_THRESHOLD = 0.40

        /** Recency window: sightings younger than this get 2× weight. */
        private const val RECENCY_WINDOW_MS = 5 * 60_000L   // 5 minutes

        /** Expected background rate in sightings-per-hour for a random device. */
        private const val EXPECTED_SIGHTINGS_PER_HOUR = 4.0

        /** Cap for the frequency weight component (prevents a single noisy device dominating). */
        private const val MAX_FREQ_WEIGHT = 3.0

        /** Stakeout stale threshold: gaps > this between sightings break a stakeout sequence. */
        private const val STAKEOUT_GAP_MS = 15 * 60_000L   // 15 minutes

        /** Cap for the duration-in-zone component (in hours). */
        private const val MAX_ZONE_DURATION_HOURS = 4.0
    }

    enum class DeviceType { TRACKER, COP }

    /**
     * Compute a normalized persistence score for [sightings].
     *
     * @param sightings   Ordered (ascending timestamp) list of scan events for a single MAC.
     * @param deviceType  Whether this device is being evaluated as a tracker or a cop signal.
     * @param nowMs       Current epoch-ms (injectable for testing; defaults to System time).
     * @return Score in [0.0, 1.0].
     */
    fun score(
        sightings: List<ScanLog>,
        deviceType: DeviceType,
        nowMs: Long = System.currentTimeMillis()
    ): Double {
        if (sightings.isEmpty()) return 0.0

        // ── Base count with recency bias ──────────────────────────────────────
        val weightedCount = sightings.sumOf { s ->
            if ((nowMs - s.timestamp) <= RECENCY_WINDOW_MS) 2.0 else 1.0
        }
        val totalSightings = weightedCount   // weighted sightings

        // ── Observation window ────────────────────────────────────────────────
        val minTs = sightings.minOf { it.timestamp }
        val maxTs = sightings.maxOf { it.timestamp }
        val observationHours = maxOf((maxTs - minTs).toDouble() / 3_600_000.0, 1.0 / 60.0)

        // ── Frequency weight ──────────────────────────────────────────────────
        val sightingsPerHour = totalSightings / observationHours
        val freqWeight = minOf(sightingsPerHour / EXPECTED_SIGHTINGS_PER_HOUR, MAX_FREQ_WEIGHT)

        // ── Raw score before normalization ────────────────────────────────────
        val rawScore = totalSightings * freqWeight

        // ── Duration-in-zone bonus (cop only) ────────────────────────────────
        val zoneBonus = if (deviceType == DeviceType.COP) {
            computeZoneDurationBonus(sightings)
        } else {
            0.0
        }

        // ── Location variance guard ───────────────────────────────────────────
        val locationMultiplier = when {
            deviceType == DeviceType.COP -> {
                // Cops stay in a zone; suppress if device has travelled far (likely not surveillance)
                val spread = locationSpreadMeters(sightings)
                if (spread < 500.0) 1.0 else 0.5   // very mobile → reduce cop score
            }
            else -> {
                // Trackers need movement; no movement → reduce score
                val spread = locationSpreadMeters(sightings)
                if (spread > 50.0) 1.0 else 0.3
            }
        }

        // ── Combine and normalize to [0, 1] ──────────────────────────────────
        // Empirical ceiling: a device with 20 weighted sightings and freqWeight=3 → rawScore=60;
        // We normalise against a ceiling of 60 + 1.0 zone bonus ceiling.
        val ceiling = 60.0 + MAX_ZONE_DURATION_HOURS
        val combined = (rawScore + zoneBonus) * locationMultiplier
        val normalized = minOf(combined / ceiling, 1.0)

        AppLog.d(TAG, "score mac=? type=$deviceType sightings=${sightings.size} " +
                "raw=$rawScore bonus=$zoneBonus loc=$locationMultiplier → $normalized")

        return normalized
    }

    /** Returns true if [score] meets the threshold for [deviceType]. */
    fun meetsThreshold(score: Double, deviceType: DeviceType): Boolean = when (deviceType) {
        DeviceType.TRACKER -> score >= TRACKER_THRESHOLD
        DeviceType.COP     -> score >= COP_THRESHOLD
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Computes a zone-duration bonus in [0, MAX_ZONE_DURATION_HOURS] based on the
     * longest continuous stakeout run (consecutive sightings with gaps ≤ STAKEOUT_GAP_MS).
     */
    private fun computeZoneDurationBonus(sightings: List<ScanLog>): Double {
        if (sightings.size < 2) return 0.0
        val sorted = sightings.sortedBy { it.timestamp }
        var longestRunMs = 0L
        var runStart = sorted.first().timestamp

        for (i in 1 until sorted.size) {
            val gap = sorted[i].timestamp - sorted[i - 1].timestamp
            if (gap > STAKEOUT_GAP_MS) {
                longestRunMs = maxOf(longestRunMs, sorted[i - 1].timestamp - runStart)
                runStart = sorted[i].timestamp
            }
        }
        longestRunMs = maxOf(longestRunMs, sorted.last().timestamp - runStart)

        val durationHours = minOf(longestRunMs.toDouble() / 3_600_000.0, MAX_ZONE_DURATION_HOURS)
        return durationHours
    }

    /**
     * Approximate location spread using mean distance from centroid (metres).
     * Returns 0 if fewer than 2 sightings have location data.
     */
    private fun locationSpreadMeters(sightings: List<ScanLog>): Double {
        val coords = sightings.mapNotNull { s ->
            val la = s.lat ?: return@mapNotNull null
            val lo = s.lng ?: return@mapNotNull null
            la to lo
        }
        if (coords.size < 2) return 0.0
        val meanLat = coords.sumOf { it.first } / coords.size
        val meanLon = coords.sumOf { it.second } / coords.size
        return coords.sumOf { (lat, lon) -> haversineMeters(meanLat, meanLon, lat, lon) } /
                coords.size
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
