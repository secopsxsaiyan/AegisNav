package com.aegisnav.app.correlation

import com.aegisnav.app.data.model.ScanLog
import com.aegisnav.app.util.AppLog
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WiFi↔BLE Cross-Correlation Engine — Phase 2A item 2.3.
 *
 * Builds a temporal co-occurrence matrix across scan cycles. When a BLE MAC and a
 * WiFi BSSID consistently appear together within [CO_OCCURRENCE_WINDOW_MS], they are
 * declared as belonging to the same physical device and fed to [MacCorrelationEngine].
 *
 * **Tracker detection integration:**
 *   A WiFi access point that co-occurs with a BLE tracker strengthens the tracker alert
 *   because the WiFi SSID/OUI provides additional identification signal.
 *
 * **Cop detection integration:**
 *   CradlePoint/Peplink WiFi SSID co-occurring with Axon BLE UUID → boosted confidence.
 *   The cross-link allows [MacCorrelationEngine] to unify their tracking histories.
 *
 * Once a pair is confirmed (≥ [LINK_THRESHOLD] co-occurrences), [MacCorrelationEngine.linkDevices]
 * is called and the pair is permanently recorded in the local maps ([bleToBssid], [bssidToBle]).
 */
@Singleton
class CrossCorrelationEngine @Inject constructor(
    private val macCorrelationEngine: MacCorrelationEngine
) {
    companion object {
        private const val TAG = "CrossCorrelationEngine"

        /**
         * BLE and WiFi sightings must fall within this window (milliseconds) to count
         * as a co-occurrence event. Matches the ROTATION_WINDOW_MS convention.
         */
        const val CO_OCCURRENCE_WINDOW_MS = 2_000L

        /**
         * A (BLE MAC, WiFi BSSID) pair must co-occur at least this many times to be
         * declared as the same physical device.
         */
        const val LINK_THRESHOLD = 3

        /**
         * Co-occurrence counters older than this are pruned on the next scan cycle.
         * Keeps memory bounded without losing meaningful co-occurrence history.
         */
        const val CO_OCCURRENCE_TTL_MS = 10 * 60_000L  // 10 minutes

        /** Hard cap on tracked pairs to prevent unbounded memory growth. */
        private const val MAX_PAIRS = 2_000
    }

    // ── Internal state ────────────────────────────────────────────────────────

    /** BLE recent sightings: MAC (uppercase) → timestamped location */
    private val recentBle = ConcurrentHashMap<String, TimedLocation>()

    /** WiFi recent sightings: BSSID (uppercase) → timestamped location */
    private val recentWifi = ConcurrentHashMap<String, TimedLocation>()

    /**
     * Co-occurrence count per (BLE MAC, WiFi BSSID) pair.
     * Each entry also records [CoOccurrenceEntry.lastIncrementTime] to prevent double-counting
     * when both `onBleResult` and `onWifiResult` fire for the same physical encounter.
     */
    private val coOccurrenceMatrix = ConcurrentHashMap<CoOccurrencePair, CoOccurrenceEntry>()

    /** Pairs that have already been confirmed and reported to MacCorrelationEngine. */
    private val confirmedLinks = ConcurrentHashMap.newKeySet<CoOccurrencePair>()

    /** BLE MAC → confirmed linked WiFi BSSIDs */
    private val bleToBssid = ConcurrentHashMap<String, MutableSet<String>>()

    /** WiFi BSSID → confirmed linked BLE MACs */
    private val bssidToBle = ConcurrentHashMap<String, MutableSet<String>>()

    private data class TimedLocation(val timestamp: Long, val lat: Double?, val lon: Double?)
    private data class CoOccurrencePair(val bleMac: String, val wifiBssid: String)

    /**
     * [count] — number of distinct co-occurrence events confirmed for this pair.
     * [lastSeen] — most recent time either device of this pair was observed (for TTL pruning).
     * [lastIncrementTime] — when the count was last incremented. Used to rate-limit counting:
     *   a BLE+WiFi pair appearing together counts as ONE event per [CO_OCCURRENCE_WINDOW_MS],
     *   regardless of how many individual BLE and WiFi callbacks fire in that window.
     */
    private data class CoOccurrenceEntry(
        val count: Int,
        val lastSeen: Long,
        val lastIncrementTime: Long
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Feed a BLE scan result. Records the sighting and checks for co-occurrence
     * with all WiFi devices observed within [CO_OCCURRENCE_WINDOW_MS].
     */
    fun onBleResult(log: ScanLog) {
        val mac = log.deviceAddress.uppercase()
        val now = log.timestamp
        pruneStale(now)
        recentBle[mac] = TimedLocation(now, log.lat, log.lng)
        checkCoOccurrencesForBle(mac, now)
    }

    /**
     * Feed a WiFi scan result. Records the sighting and checks for co-occurrence
     * with all BLE devices observed within [CO_OCCURRENCE_WINDOW_MS].
     */
    fun onWifiResult(log: ScanLog) {
        val bssid = log.deviceAddress.uppercase()
        val now = log.timestamp
        pruneStale(now)
        recentWifi[bssid] = TimedLocation(now, log.lat, log.lng)
        checkCoOccurrencesForWifi(bssid, now)
    }

    /**
     * Returns confirmed WiFi BSSIDs linked to the given BLE MAC.
     * Returns an empty set if none confirmed yet.
     */
    fun getLinkedWifiForBle(bleMac: String): Set<String> =
        bleToBssid[bleMac.uppercase()]?.toSet() ?: emptySet()

    /**
     * Returns confirmed BLE MACs linked to the given WiFi BSSID.
     * Returns an empty set if none confirmed yet.
     */
    fun getLinkedBleForWifi(wifiBssid: String): Set<String> =
        bssidToBle[wifiBssid.uppercase()]?.toSet() ?: emptySet()

    /** Returns true if the BLE MAC has at least one confirmed WiFi cross-correlation link. */
    fun hasLinkedWifi(bleMac: String): Boolean = getLinkedWifiForBle(bleMac).isNotEmpty()

    /** Returns true if the WiFi BSSID has at least one confirmed BLE cross-correlation link. */
    fun hasLinkedBle(wifiBssid: String): Boolean = getLinkedBleForWifi(wifiBssid).isNotEmpty()

    /**
     * Returns the current co-occurrence count for a given (BLE MAC, WiFi BSSID) pair.
     * Returns 0 if the pair has not been observed or has been confirmed and removed.
     * Useful for tests and diagnostics.
     */
    fun getCoOccurrenceCount(bleMac: String, wifiBssid: String): Int {
        val pair = CoOccurrencePair(bleMac.uppercase(), wifiBssid.uppercase())
        if (pair in confirmedLinks) return LINK_THRESHOLD  // already confirmed
        return coOccurrenceMatrix[pair]?.count ?: 0
    }

    /**
     * Clears all state. Call when scanning stops.
     */
    fun clear() {
        recentBle.clear()
        recentWifi.clear()
        coOccurrenceMatrix.clear()
        confirmedLinks.clear()
        bleToBssid.clear()
        bssidToBle.clear()
        AppLog.d(TAG, "State cleared")
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun checkCoOccurrencesForBle(bleMac: String, now: Long) {
        val window = now - CO_OCCURRENCE_WINDOW_MS
        for ((bssid, entry) in recentWifi) {
            if (entry.timestamp < window) continue
            incrementAndCheck(CoOccurrencePair(bleMac, bssid), now)
        }
    }

    private fun checkCoOccurrencesForWifi(bssid: String, now: Long) {
        val window = now - CO_OCCURRENCE_WINDOW_MS
        for ((bleMac, entry) in recentBle) {
            if (entry.timestamp < window) continue
            incrementAndCheck(CoOccurrencePair(bleMac, bssid), now)
        }
    }

    private fun incrementAndCheck(pair: CoOccurrencePair, now: Long) {
        // Skip already-confirmed pairs
        if (pair in confirmedLinks) return

        // Memory safety: hard cap on tracked pairs
        if (coOccurrenceMatrix.size >= MAX_PAIRS) {
            AppLog.w(TAG, "Co-occurrence matrix at capacity ($MAX_PAIRS), dropping new pair")
            return
        }

        val current = coOccurrenceMatrix[pair]

        // Rate-limit: when both BLE and WiFi fire within the same CO_OCCURRENCE_WINDOW_MS,
        // they represent the SAME physical encounter — count it only once.
        if (current != null && now - current.lastIncrementTime < CO_OCCURRENCE_WINDOW_MS) {
            // Update lastSeen for TTL purposes but don't increment
            coOccurrenceMatrix[pair] = current.copy(lastSeen = now)
            return
        }

        val newCount = (current?.count ?: 0) + 1
        coOccurrenceMatrix[pair] = CoOccurrenceEntry(newCount, now, now)

        if (newCount >= LINK_THRESHOLD) {
            // Threshold reached — confirm the link
            confirmedLinks.add(pair)
            coOccurrenceMatrix.remove(pair)

            // Record in local lookup maps
            bleToBssid.getOrPut(pair.bleMac) { ConcurrentHashMap.newKeySet() }.add(pair.wifiBssid)
            bssidToBle.getOrPut(pair.wifiBssid) { ConcurrentHashMap.newKeySet() }.add(pair.bleMac)

            // Notify MacCorrelationEngine to unify the device identities
            macCorrelationEngine.linkDevices(pair.bleMac, pair.wifiBssid)

            AppLog.i(
                TAG,
                "Cross-correlation confirmed: BLE ${pair.bleMac} ↔ WiFi ${pair.wifiBssid} " +
                    "($newCount co-occurrences)"
            )
        }
    }

    private fun pruneStale(now: Long) {
        val cutoff = now - CO_OCCURRENCE_TTL_MS
        recentBle.entries.removeAll { it.value.timestamp < cutoff }
        recentWifi.entries.removeAll { it.value.timestamp < cutoff }
        coOccurrenceMatrix.entries.removeAll { it.value.lastSeen < cutoff }
    }
}
