package com.aegisnav.app.watchlist

import com.aegisnav.app.crash.CrashReporter
import com.aegisnav.app.data.dao.WatchlistDao
import com.aegisnav.app.data.model.ThreatEvent
import com.aegisnav.app.data.repository.ThreatEventRepository
import com.aegisnav.app.util.AlertTtsManager
import com.aegisnav.app.util.TtsCategory
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages "always alert" watchlist: when a watched MAC is seen during scanning,
 * fires a TTS alert and creates/updates a WATCHLIST ThreatEvent.
 *
 * Grouping policy:
 * - First sighting (or after 15-min window expires): TTS + new ThreatEvent with threat level HIGH
 * - Within 15-min window: sighting appended to pendingSightings map only; no TTS
 *
 * Cache: watchlist MAC set is cached and refreshed every 60 seconds.
 */
@Singleton
class WatchlistAlertManager @Inject constructor(
    private val watchlistDao: WatchlistDao,
    private val threatEventRepository: ThreatEventRepository,
    private val alertTtsManager: AlertTtsManager,
    private val crashReporter: CrashReporter
) {

    data class SightingInfo(
        val lat: Double,
        val lon: Double,
        val rssi: Int,
        val timestamp: Long
    )

    // Per-MAC: timestamp of when the current 15-min alert window started
    private val lastAlertTimestamp = ConcurrentHashMap<String, Long>()

    // Per-MAC: accumulated sightings within the current 15-min window
    private val pendingSightings = ConcurrentHashMap<String, MutableList<SightingInfo>>()

    // Per-MAC: DB id of the current open ThreatEvent for this window
    private val openEventMac = ConcurrentHashMap<String, Long>()

    // Cached watchlist: mac → WatchlistEntry label
    @Volatile private var cachedWatchlist: Map<String, String> = emptyMap()
    @Volatile private var lastCacheRefreshMs: Long = 0L
    private val CACHE_TTL_MS = 60_000L

    private val WINDOW_MS = 15 * 60_000L

    // Sentry throttle state
    private val lastSentryMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun sentryCaptureThrottled(key: String, cooldownMs: Long = 300_000L, message: String) {
        val now = System.currentTimeMillis()
        val last = lastSentryMs[key] ?: 0L
        if (now - last >= cooldownMs) {
            lastSentryMs[key] = now
            crashReporter.captureMessage(message)
        }
    }

    /**
     * Call this for each scanned device. Checks the watchlist and fires alerts as needed.
     */
    suspend fun onDeviceSeen(mac: String, lat: Double, lon: Double, rssi: Int, timestamp: Long) {
        // Refresh watchlist cache if stale
        val now = System.currentTimeMillis()
        if (now - lastCacheRefreshMs >= CACHE_TTL_MS) {
            refreshWatchlist()
        }

        val label = cachedWatchlist[mac] ?: return  // not in watchlist — no-op

        val lastAlert = lastAlertTimestamp[mac]
        val sighting = SightingInfo(lat, lon, rssi, timestamp)

        if (lastAlert == null || (now - lastAlert) >= WINDOW_MS) {
            // New alert window: fire TTS + create new ThreatEvent
            lastAlertTimestamp[mac] = now
            val sightingList = mutableListOf(sighting)
            pendingSightings[mac] = sightingList

            sentryCaptureThrottled("watchlist_${mac.take(8)}", 300_000L,
                "Watchlist match: mac=${mac.take(8)}XX:XX")
            alertTtsManager.speakIfEnabled(
                "Watched device detected",
                TtsCategory.TRACKER,
                "watchlist_$mac"
            )

            val detailJson = buildDetailJson(mac, label, sightingList)
            val event = ThreatEvent(
                type = "WATCHLIST",
                mac = mac,
                timestamp = now,
                detailJson = detailJson
            )
            threatEventRepository.insert(event)
        } else {
            // Within window: accumulate sighting (no TTS)
            val list = pendingSightings.getOrPut(mac) { mutableListOf() }
            list.add(sighting)
        }
    }

    /**
     * Reload watchlist from DAO into the in-memory cache.
     */
    suspend fun refreshWatchlist() {
        val entries = watchlistDao.getAllMacs()
        // We need labels too — but getAllMacs() returns List<String>
        // Build a map by calling getByMac for each, or use a different query approach.
        // Since we need labels, we reload full entries via a flow snapshot.
        // Use a direct approach: cache mac→label.
        val fullEntries = entries.mapNotNull { mac -> watchlistDao.getByMac(mac) }
        cachedWatchlist = fullEntries.associate { it.mac to it.label }
        lastCacheRefreshMs = System.currentTimeMillis()
    }

    private fun buildDetailJson(mac: String, label: String, sightings: List<SightingInfo>): String {
        val sightingsArr = JSONArray()
        sightings.forEach { s ->
            sightingsArr.put(JSONObject().apply {
                put("lat", s.lat)
                put("lon", s.lon)
                put("rssi", s.rssi)
                put("timestamp", s.timestamp)
            })
        }
        return JSONObject().apply {
            put("mac", mac)
            put("watchlistLabel", label)
            put("sightings", sightingsArr)
        }.toString()
    }
}
