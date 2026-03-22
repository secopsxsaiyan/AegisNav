package com.aegisnav.app.baseline

import com.aegisnav.app.di.ApplicationScope
import com.aegisnav.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Baseline Environment Learning engine — Feature 2.4.
 *
 * Builds a "normal" device profile per 100 m geofence zone by observing which
 * devices appear at each location and at what hours of the day.
 *
 * **Learning period:** [LEARNING_PERIOD_MS] (default 1 hour per the v0.5 answers).
 * After the zone's learning period expires, new devices or devices appearing at
 * unusual hours are flagged as anomalies.
 *
 * **Zones:** 0.001° lat/lon buckets ≈ 110 m × 110 m at the equator.
 *
 * **Anomaly types:**
 *   - [ANOMALY_NEW_DEVICE]    — MAC never seen in this zone before
 *   - [ANOMALY_UNUSUAL_TIME]  — device present outside its typical ±2 h window
 *
 * **Note:** This is separate from the Privacy Wizard 30-second scan — the Wizard
 * populates the ignore list, the Baseline Engine populates [baseline_devices].
 */
@Singleton
class BaselineEngine @Inject constructor(
    private val baselineDao: BaselineDao,
    @ApplicationScope private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BaselineEngine"

        /** 1-hour learning window (from roadmap answers "2.4=1hr baseline"). */
        const val LEARNING_PERIOD_MS: Long = 60 * 60_000L

        /** Unusual-time tolerance: device must have zero observations within ±N hours. */
        private const val UNUSUAL_HOUR_RADIUS = 2

        /** Min total observations before unusual-time checks are meaningful. */
        private const val MIN_OBS_FOR_UNUSUAL_CHECK = 5

        const val ANOMALY_NEW_DEVICE   = "NEW_DEVICE"
        const val ANOMALY_UNUSUAL_TIME = "UNUSUAL_TIME"
    }

    // ── Zone state ─────────────────────────────────────────────────────────────

    enum class ZoneStatus { LEARNING, BASELINE_ACTIVE }

    /** Epoch-ms when each zone was first observed (in-memory; survives restarts via DB firstSeen). */
    private val zoneFirstSeen = ConcurrentHashMap<String, Long>()

    private val _zoneStatus = MutableStateFlow<Map<String, ZoneStatus>>(emptyMap())

    /** Observable map from zoneId → [ZoneStatus]. UI uses this for the indicator chip. */
    val zoneStatus: StateFlow<Map<String, ZoneStatus>> = _zoneStatus.asStateFlow()

        // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Record a device observation at the user's current GPS position.
     *
     * Upserts the device profile in the DB (frequency, last seen, hour counts).
     * If the zone's learning period has elapsed, fires [onAnomaly] for new devices
     * or devices at unusual hours.
     *
     * This is a fire-and-forget call — DB work is done on the IO coroutine scope
     * and [onAnomaly] is invoked on the IO thread (safe to dispatch to UI via post).
     *
     * @param mac       Device MAC address / BSSID
     * @param userLat   User's current latitude
     * @param userLon   User's current longitude
     * @param onAnomaly Called with (mac, zoneId, anomalyType) when an anomaly is found
     */
    fun onDeviceObserved(
        mac: String,
        userLat: Double,
        userLon: Double,
        onAnomaly: ((mac: String, zoneId: String, anomalyType: String) -> Unit)? = null
    ) {
        val zoneId = zoneIdFor(userLat, userLon)
        val now    = System.currentTimeMillis()
        val hour   = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        scope.launch {
            try {
                val existing = baselineDao.get(mac, zoneId)
                val active   = isZoneActive(zoneId, now)

                if (existing == null) {
                    // First observation of this device in this zone
                    val hours = IntArray(24).also { it[hour] = 1 }
                    baselineDao.upsert(
                        BaselineDevice(
                            mac              = mac,
                            zoneId           = zoneId,
                            firstSeen        = now,
                            lastSeen         = now,
                            frequency        = 1,
                            typicalHoursJson = JSONArray(hours.toTypedArray()).toString()
                        )
                    )
                    // Prime the zone start-time entry
                    zoneFirstSeen.computeIfAbsent(zoneId) { now }

                    if (active && onAnomaly != null) {
                        AppLog.i(TAG, "ANOMALY $ANOMALY_NEW_DEVICE mac=$mac zone=$zoneId")
                        onAnomaly(mac, zoneId, ANOMALY_NEW_DEVICE)
                    }
                } else {
                    // Update existing profile
                    val hours = parseHours(existing.typicalHoursJson)
                    hours[hour] = (hours[hour] + 1).coerceAtMost(9999)
                    baselineDao.upsert(
                        existing.copy(
                            lastSeen         = now,
                            frequency        = existing.frequency + 1,
                            typicalHoursJson = JSONArray(hours.toTypedArray()).toString()
                        )
                    )
                    // Ensure zone start-time is registered (in case engine restarted)
                    zoneFirstSeen.computeIfAbsent(zoneId) { existing.firstSeen }

                    if (active && onAnomaly != null && isUnusualTime(hours, hour)) {
                        AppLog.i(TAG, "ANOMALY $ANOMALY_UNUSUAL_TIME mac=$mac zone=$zoneId hour=$hour")
                        onAnomaly(mac, zoneId, ANOMALY_UNUSUAL_TIME)
                    }
                }

                refreshZoneStatus(zoneId, now)
            } catch (e: Exception) {
                AppLog.e(TAG, "onDeviceObserved error: ${e.message}")
            }
        }
    }

    /**
     * Returns the current [ZoneStatus] for the zone containing [lat]/[lon].
     * Call this on the main thread — reads from the in-memory [zoneFirstSeen] map.
     */
    fun statusAt(lat: Double, lon: Double): ZoneStatus {
        val zoneId = zoneIdFor(lat, lon)
        return if (isZoneActive(zoneId, System.currentTimeMillis()))
            ZoneStatus.BASELINE_ACTIVE else ZoneStatus.LEARNING
    }

    /**
     * Calculates the zone identifier for the given GPS coordinates.
     * Exposed for testing and for callers that need to correlate results.
     */
    fun zoneIdFor(lat: Double, lon: Double): String =
        "${"%.3f".format(lat)}:${"%.3f".format(lon)}"

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun isZoneActive(zoneId: String, now: Long): Boolean {
        val start = zoneFirstSeen[zoneId] ?: return false
        return (now - start) >= LEARNING_PERIOD_MS
    }

    private fun refreshZoneStatus(zoneId: String, now: Long) {
        val status = if (isZoneActive(zoneId, now)) ZoneStatus.BASELINE_ACTIVE else ZoneStatus.LEARNING
        val prev   = _zoneStatus.value
        if (prev[zoneId] != status) {
            _zoneStatus.value = prev + (zoneId to status)
        }
    }

    /**
     * Returns true when [currentHour] is "unusual" — i.e. there are no observations
     * in the [UNUSUAL_HOUR_RADIUS]-hour window around [currentHour].
     */
    private fun isUnusualTime(hourCounts: IntArray, currentHour: Int): Boolean {
        if (hourCounts.sum() < MIN_OBS_FOR_UNUSUAL_CHECK) return false
        for (delta in -UNUSUAL_HOUR_RADIUS..UNUSUAL_HOUR_RADIUS) {
            val h = ((currentHour + delta) + 24) % 24
            if (hourCounts[h] > 0) return false
        }
        return true
    }

    private fun parseHours(json: String): IntArray = try {
        val arr = JSONArray(json)
        IntArray(24) { i -> arr.optInt(i, 0) }
    } catch (e: org.json.JSONException) {
        AppLog.w(TAG, "parseHours JSON parse failed: ${e.message}")
        IntArray(24)
    }
}
