package com.aegisnav.app.police

import android.content.Context
import com.aegisnav.app.util.AppLog
import com.aegisnav.app.baseline.BaselineEngine
import com.aegisnav.app.data.model.ScanLog
import com.aegisnav.app.data.repository.IgnoreListRepository
import com.aegisnav.app.di.ApplicationScope
import com.aegisnav.app.signal.RssiDistanceEstimator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PoliceDetector - passive offline detection of law enforcement equipment via BLE/WiFi.
 *
 * Detection vectors:
 *   - BLE advertised 16-bit service UUIDs (Axon, TASER, MOTIVE)
 *   - WiFi OUI prefixes with tiered confidence (100%, 75%, 50%, 25%)
 *   - BLE advertised device name patterns (Axon, APX, TASER, DigitalAlly)
 *   - WiFi SSID patterns (Axon sync networks, CradlePoint, MDT/CAD patterns)
 *
 * All signatures are hot-configurable via assets/police_signatures.json.
 *
 * Alert threshold: 0.5 — any signal ≥50% triggers alone; two 25% signals trigger together.
 */
@Singleton
class PoliceDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val policeSightingDao: PoliceSightingDao,
    private val rssiDistanceEstimator: RssiDistanceEstimator,
    private val baselineEngine: BaselineEngine,
    private val ignoreListRepository: IgnoreListRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "PoliceDetector"
        private const val SIGNATURES_ASSET = "police_signatures.json"

        const val ALERT_THRESHOLD = 0.5f

        private const val COOLDOWN_MS = 5 * 60 * 1000L  // 5 min per device
        private const val PRUNE_INTERVAL_MS = 60_000L   // prune expired cells every 60 s
    }

    // BLE service UUID → (label, confidence)
    private var bleServiceUuids: Map<String, Pair<String, Float>> = emptyMap()
    // Short form (4 hex chars) for matching against shortened UUIDs
    private var bleServiceUuidsShort: Map<String, Pair<String, Float>> = emptyMap()

    // WiFi OUI tiers: OUI → (label, confidence)
    private var wifiOuiMap: Map<String, Pair<String, Float>> = emptyMap()

    private var bleNamePatterns: List<String> = emptyList()
    private var wifiSsidPatterns: List<String> = emptyList()
    private var wifiSsidLabels: Map<String, String> = emptyMap()

        private val lastSightingTime = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Phase 3 — Feature 3.9: cached ignore list applied to all detections
    @Volatile private var ignoreSet: Set<String> = emptySet()

    init {
        scope.launch {
            while (true) {
                try {
                    ignoreSet = ignoreListRepository.getAllAddresses().toSet()
                } catch (e: Exception) {
                    AppLog.w(TAG, "Ignore list refresh: ${e.message}")
                }
                kotlinx.coroutines.delay(60_000L)
            }
        }
    }
    /** Last observed RSSI per device address — used for distance estimation in [emit]. */
    private val lastSeenRssi = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private val _sightings = MutableStateFlow<PoliceSighting?>(null)
    val sightings: StateFlow<PoliceSighting?> = _sightings

    // ── 50m grid cell-based alert consolidation ──────────────────────────────
    // One alert per cell. Multiple devices in same cell → consolidated sighting (updated in DB).
    // Same device in same cell within 2 min → fully suppressed.
    // New distinct category in same cell → correlated confidence boost.
    private data class GridCell(val latBucket: Long, val lonBucket: Long)
    private data class CellState(
        val categories: MutableSet<String>,        // distinct categories seen
        val signals: MutableList<String>,           // all matched signals across detections
        var bestConfidence: Float,                  // highest single-detection confidence
        var lastTimestamp: Long,                    // most recent detection time
        var lat: Double,                            // representative lat (from best detection)
        var lon: Double,                            // representative lon
        val deviceMacs: MutableSet<String>,         // distinct MAC addresses consolidated
        var sightingId: String? = null              // DB row id for updates
    )
    private val gridCells = java.util.concurrent.ConcurrentHashMap<GridCell, CellState>()

    /** Time window for grid correlation — cells older than this reset. */
    private val GRID_WINDOW_MS = 5 * 60 * 1000L  // 5 minutes

    /** Suppress re-alert from same device in same cell if seen within this window. */
    private val SAME_DEVICE_SUPPRESS_MS = 2 * 60 * 1000L  // 2 minutes

    /** Confidence boost per additional distinct category in the same 50m cell. */
    private val GRID_BOOST_PER_CATEGORY = 0.25f

    /** Tracks when the last periodic grid-cell prune ran (epoch ms). */
    private var lastPruneMs = 0L

    private val GRID_SIZE_M = 50.0
    private val LAT_STEP = GRID_SIZE_M / 110574.0  // degrees per 50m latitude

    init { loadSignatures() }

    // ── Signature loading ────────────────────────────────────────────────────

    private fun loadSignatures() {
        try {
            context.assets.open(SIGNATURES_ASSET).use { stream ->
                val json = JSONObject(stream.bufferedReader().readText())

                // BLE service UUIDs (full 128-bit form)
                bleServiceUuids = parseUuidConfidenceMap(json.optJSONObject("ble_service_uuids"))
                bleServiceUuidsShort = parseUuidConfidenceMap(json.optJSONObject("ble_service_uuids_short"))

                // WiFi OUI tiers → merge into single map with confidence
                wifiOuiMap = buildMap {
                    putAll(parseOuiTier(json.optJSONObject("wifi_oui_100"), 1.0f))
                    putAll(parseOuiTier(json.optJSONObject("wifi_oui_75"), 0.75f))
                    putAll(parseOuiTier(json.optJSONObject("wifi_oui_50"), 0.50f))
                    putAll(parseOuiTier(json.optJSONObject("wifi_oui_25"), 0.25f))
                }

                bleNamePatterns = parseStringArray(json.optJSONArray("ble_device_name_patterns"))
                wifiSsidPatterns = parseStringArray(json.optJSONArray("wifi_ssid_patterns"))
                wifiSsidLabels = parseStringStringMap(json.optJSONObject("wifi_ssid_labels"))

                AppLog.i(TAG, "Loaded signatures: ${bleServiceUuids.size} BLE UUIDs, " +
                    "${wifiOuiMap.size} WiFi OUIs, ${bleNamePatterns.size} BLE names, " +
                    "${wifiSsidPatterns.size} SSID patterns")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to load police_signatures.json: ${e.message}")
        }
    }

    private fun parseUuidConfidenceMap(obj: JSONObject?): Map<String, Pair<String, Float>> {
        obj ?: return emptyMap()
        return obj.keys().asSequence().associate { key ->
            val entry = obj.getJSONObject(key)
            key.lowercase() to Pair(entry.getString("label"), entry.getDouble("confidence").toFloat())
        }
    }

    private fun parseOuiTier(obj: JSONObject?, confidence: Float): Map<String, Pair<String, Float>> {
        obj ?: return emptyMap()
        return obj.keys().asSequence().associate { key ->
            key.uppercase() to Pair(obj.getString(key), confidence)
        }
    }

    private fun parseStringArray(arr: JSONArray?): List<String> {
        arr ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun parseStringStringMap(obj: JSONObject?): Map<String, String> {
        obj ?: return emptyMap()
        return obj.keys().asSequence().associateWith { obj.getString(it) }
    }

    // ── BLE scan ingestion ───────────────────────────────────────────────────

    fun onBleScanResult(log: ScanLog) {
        // Phase 3 — Feature 3.9: skip ignored devices
        if (log.deviceAddress in ignoreSet) return

        val lat = log.lat ?: return
        val lon = log.lng ?: return

        // Phase 2B: record device in baseline for anomaly detection (2.4)
        baselineEngine.onDeviceObserved(log.deviceAddress, lat, lon)

        val matchedSignals = mutableListOf<Pair<String, Float>>()

        // 1. BLE advertised service UUIDs (highest priority for police equipment)
        val uuids = log.serviceUuids
        if (!uuids.isNullOrBlank()) {
            val uuidList = uuids.split(",").map { it.trim().lowercase() }
            for (uuid in uuidList) {
                // Try full 128-bit match
                bleServiceUuids[uuid]?.let { (label, conf) ->
                    matchedSignals.add("ble_uuid:$label:$uuid" to conf)
                }
                // Try short 16-bit match (extract from 128-bit: chars 4-8 of standard BLE UUID)
                if (uuid.length >= 8) {
                    val short16 = uuid.substring(4, 8)
                    bleServiceUuidsShort[short16]?.let { (label, conf) ->
                        // Avoid double-counting if full match already added
                        if (matchedSignals.none { it.first.contains(uuid) }) {
                            matchedSignals.add("ble_uuid_short:$label:$short16" to conf)
                        }
                    }
                }
            }
        }

        // 2. OUI prefix on BLE MAC (some police BLE devices use identifiable OUIs)
        val macUpper = log.deviceAddress.uppercase()
        val oui = macUpper.take(8)  // "XX:XX:XX"
        wifiOuiMap[oui]?.let { (label, conf) ->
            matchedSignals.add("oui:$label:$oui" to conf)
        }

        // 3. BLE device name patterns
        val name = log.deviceName ?: ""
        if (name.isNotBlank()) {
            bleNamePatterns.firstOrNull { name.contains(it, ignoreCase = true) }?.let { matched ->
                matchedSignals.add("ble_name:$matched" to 0.5f)
            }
        }

        if (matchedSignals.isEmpty()) return

        // Phase 2B: cache RSSI for distance estimation
        lastSeenRssi[log.deviceAddress] = log.rssi

        val confidence = matchedSignals.sumOf { it.second.toDouble() }.toFloat().coerceAtMost(1.0f)
        val category = inferCategory(matchedSignals.map { it.first })
        emit(log.deviceAddress, lat, lon, log.timestamp, matchedSignals.map { it.first }, confidence, category, isBle = true)
    }

    // ── WiFi scan ingestion ──────────────────────────────────────────────────

    fun onWifiScanResult(log: ScanLog, ssid: String?) {
        // Phase 3 — Feature 3.9: skip ignored devices
        if (log.deviceAddress in ignoreSet) return

        val lat = log.lat ?: return
        val lon = log.lng ?: return

        // Phase 2B: record device in baseline for anomaly detection (2.4)
        baselineEngine.onDeviceObserved(log.deviceAddress, lat, lon)

        val matchedSignals = mutableListOf<Pair<String, Float>>()

        // 1. SSID pattern match (high confidence)
        if (!ssid.isNullOrBlank()) {
            wifiSsidPatterns.firstOrNull { ssid.contains(it, ignoreCase = true) }?.let { matched ->
                val label = wifiSsidLabels[matched] ?: "UNKNOWN_LE"
                matchedSignals.add("wifi_ssid:$label:$matched" to 0.5f)
            }
        }

        // 2. OUI on BSSID (tiered confidence from wifi_oui_100/75/50/25)
        val bssidUpper = log.deviceAddress.uppercase()
        val oui = bssidUpper.take(8)  // "XX:XX:XX"
        wifiOuiMap[oui]?.let { (label, conf) ->
            matchedSignals.add("wifi_oui:$label:$oui" to conf)
        }

        if (matchedSignals.isEmpty()) return

        // Phase 2B: cache RSSI for distance estimation
        lastSeenRssi[log.deviceAddress] = log.rssi

        val confidence = matchedSignals.sumOf { it.second.toDouble() }.toFloat().coerceAtMost(1.0f)
        val category = inferCategory(matchedSignals.map { it.first })
        emit(log.deviceAddress, lat, lon, log.timestamp, matchedSignals.map { it.first }, confidence, category, isBle = false)
    }

    // ── Grid cell resolution ─────────────────────────────────────────────────

    private fun cellFor(lat: Double, lon: Double): GridCell {
        val lonStep = GRID_SIZE_M / (110574.0 * kotlin.math.cos(Math.toRadians(lat)))
        return GridCell((lat / LAT_STEP).toLong(), (lon / lonStep).toLong())
    }

    // ── Evaluation + emit ────────────────────────────────────────────────────

    /**
     * Cell-based alert consolidation logic:
     *  - One sighting per 50m cell. First detection → insert sighting in DB.
     *  - Same device again in same cell within 2 min → fully suppressed.
     *  - New device in same cell → update existing DB row (increment deviceCount, append MAC).
     *  - NEW distinct category in same cell → update with correlated confidence boost.
     *  - Detection outside all existing cells → new sighting inserted.
     */
    private fun emit(
        deviceAddress: String, lat: Double, lon: Double, timestamp: Long,
        signals: List<String>, baseConfidence: Float, category: String,
        isBle: Boolean = true
    ) {
        if (baseConfidence < ALERT_THRESHOLD) return

        val deviceId = deviceAddress.uppercase()
        val now = System.currentTimeMillis()

        // Per-device cooldown (same MAC/BSSID) — still used to avoid hammering from single device
        if (now - (lastSightingTime[deviceId] ?: 0L) < COOLDOWN_MS) return
        lastSightingTime[deviceId] = now

        // Periodic prune of expired cells (time-based, avoids non-atomic size-check race)
        if (now - lastPruneMs > PRUNE_INTERVAL_MS) {
            gridCells.entries.removeAll { now - it.value.lastTimestamp > GRID_WINDOW_MS }
            lastPruneMs = now
        }

        val cell = cellFor(lat, lon)
        val state = gridCells[cell]

        if (state != null && (now - state.lastTimestamp) < GRID_WINDOW_MS) {
            // Cell exists and is still active — synchronize on state to prevent concurrent mutation
            synchronized(state) {
                val deviceAlreadyInCell = deviceId in state.deviceMacs
                if (deviceAlreadyInCell && (now - state.lastTimestamp) < SAME_DEVICE_SUPPRESS_MS) {
                    // Same device, same cell, within 2 min → fully suppress
                    AppLog.d(TAG, "Suppressed: $deviceId already in cell (${cell.latBucket},${cell.lonBucket}) within 2 min")
                    return
                }

                val isNewDevice = !deviceAlreadyInCell
                val isNewCategory = category !in state.categories

                if (!isNewDevice && !isNewCategory) {
                    // Known device, known category — suppress
                    AppLog.d(TAG, "Suppressed: $category already alerted in cell (${cell.latBucket},${cell.lonBucket})")
                    return
                }

                // Update cell state
                state.categories.add(category)
                state.signals.addAll(signals)
                state.lastTimestamp = now
                if (isNewDevice) {
                    state.deviceMacs.add(deviceId)
                }
                if (baseConfidence > state.bestConfidence) {
                    state.bestConfidence = baseConfidence
                    state.lat = lat
                    state.lon = lon
                }

                val boost = (state.categories.size - 1) * GRID_BOOST_PER_CATEGORY
                val updatedConfidence = (state.bestConfidence + boost).coerceAtMost(1.0f)
                val allSignals = state.signals.toList() + if (state.categories.size > 1)
                    listOf("grid_corr:${state.categories.joinToString("+")}") else emptyList()

                // Phase 2B: distance estimation (2.5)
                val rssi = lastSeenRssi[deviceAddress] ?: -70
                val distM = rssiDistanceEstimator.estimate(deviceAddress, rssi, isBle)

                val matchedJson = try { JSONArray(allSignals).toString() } catch (e: org.json.JSONException) { AppLog.w(TAG, "JSONArray serialization failed: ${e.message}"); "[]" }
                val macsStr = state.deviceMacs.joinToString(",")

                val existingId = state.sightingId
                if (existingId != null) {
                    // Update consolidated sighting in DB
                    val updatedSighting = PoliceSighting(
                        id = existingId,
                        lat = state.lat,
                        lon = state.lon,
                        timestamp = state.lastTimestamp,
                        confidence = updatedConfidence,
                        matchedSignals = matchedJson,
                        detectionCategory = if (state.categories.size > 1) "CORRELATED" else state.categories.first(),
                        estimatedDistanceMeters = distM,
                        deviceCount = state.deviceMacs.size,
                        deviceMacs = macsStr,
                        verdictDeadlineMs = System.currentTimeMillis() + 15 * 60_000L
                    )
                    AppLog.i(TAG, "Updated consolidated alert: cell=(${cell.latBucket},${cell.lonBucket}) " +
                        "devices=${state.deviceMacs.size} categories=${state.categories} confidence=$updatedConfidence")
                    _sightings.value = updatedSighting
                    scope.launch { policeSightingDao.update(updatedSighting) }
                } else {
                    // No stored id yet (edge case) — insert a new one
                    val sighting = PoliceSighting(
                        id = UUID.randomUUID().toString(),
                        lat = state.lat,
                        lon = state.lon,
                        timestamp = now,
                        confidence = updatedConfidence,
                        matchedSignals = matchedJson,
                        detectionCategory = if (state.categories.size > 1) "CORRELATED" else state.categories.first(),
                        estimatedDistanceMeters = distM,
                        deviceCount = state.deviceMacs.size,
                        deviceMacs = macsStr,
                        verdictDeadlineMs = System.currentTimeMillis() + 15 * 60_000L
                    )
                    state.sightingId = sighting.id
                    AppLog.i(TAG, "New consolidated alert (no prior id): cell=(${cell.latBucket},${cell.lonBucket}) " +
                        "confidence=$updatedConfidence")
                    _sightings.value = sighting
                    scope.launch { policeSightingDao.insert(sighting) }
                }
            }

        } else {
            // New cell or expired → first detection, insert one sighting
            val sightingId = UUID.randomUUID().toString()
            gridCells[cell] = CellState(
                categories = mutableSetOf(category),
                signals = signals.toMutableList(),
                bestConfidence = baseConfidence,
                lastTimestamp = now,
                lat = lat,
                lon = lon,
                deviceMacs = mutableSetOf(deviceId),
                sightingId = sightingId
            )

            // Phase 2B: distance estimation (2.5)
            val rssi = lastSeenRssi[deviceAddress] ?: -70
            val distM = rssiDistanceEstimator.estimate(deviceAddress, rssi, isBle)

            val matchedJson = try { JSONArray(signals).toString() } catch (e: org.json.JSONException) { AppLog.w(TAG, "JSONArray serialization failed: ${e.message}"); "[]" }
            val sighting = PoliceSighting(
                id = sightingId,
                lat = lat,
                lon = lon,
                timestamp = timestamp,
                confidence = baseConfidence,
                matchedSignals = matchedJson,
                detectionCategory = category,
                estimatedDistanceMeters = distM,
                deviceCount = 1,
                deviceMacs = deviceId,
                verdictDeadlineMs = System.currentTimeMillis() + 15 * 60_000L
            )

            AppLog.i(TAG, "New cell alert: category=$category confidence=$baseConfidence " +
                "cell=(${cell.latBucket},${cell.lonBucket}) signals=$signals")
            _sightings.value = sighting
            scope.launch { policeSightingDao.insert(sighting) }
        }
    }

    /**
     * Returns true if [mac] has been positively matched against law enforcement signatures
     * within the current session (i.e. it has an entry in [lastSightingTime]).
     *
     * Used by ScanService to assign "POLICE" category to triangulation results.
     */
    fun isKnownPoliceMac(mac: String): Boolean = lastSightingTime.containsKey(mac)

    private fun inferCategory(signals: List<String>): String {
        val all = signals.joinToString(" ").uppercase()
        return when {
            "AXON" in all            -> "AXON"
            "TASER" in all           -> "TASER"
            "DIGITAL_ALLY" in all    -> "DIGITAL_ALLY"
            "WATCHGUARD" in all      -> "WATCHGUARD"
            "KENWOOD" in all         -> "KENWOOD"
            "MOTIVE" in all          -> "MOTIVE"
            "CRADLEPOINT" in all     -> "CRADLEPOINT"
            "MOTOROLA" in all        -> "MOTOROLA_SOLUTIONS"
            "CELLULAR_MDT" in all    -> "CELLULAR_MDT"
            else                     -> "UNKNOWN_LE"
        }
    }
}
