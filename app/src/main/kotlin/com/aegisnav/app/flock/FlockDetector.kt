package com.aegisnav.app.flock

import android.content.Context
import com.aegisnav.app.crash.CrashReporter
import com.aegisnav.app.util.AppLog
import com.aegisnav.app.data.model.ScanLog
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
 * FlockDetector - passive offline detection of Flock Safety ALPR infrastructure.
 *
 * Flock Safety deploys ALPR cameras and LPR nodes nationwide. These devices may
 * emit identifiable BLE and WiFi RF signatures. This detector processes the same
 * ring buffer entries as TrackerDetectionEngine, looking for Flock Safety signatures
 * as configured in assets/flock_signatures.json.
 *
 * IMPORTANT: Exact Flock Safety RF signatures are not publicly confirmed. The
 * detection system is designed to be updated via flock_signatures.json as signatures
 * are identified in the field without requiring a code change.
 *
 * Confidence scoring: each independent signal type match += 0.25.
 * Alert threshold: confidence >= 0.5 (at least 2 independent signal type matches).
 *
 * Emits [FlockSighting] events via [sightings] StateFlow when threshold is met.
 */
@Singleton
class FlockDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val flockSightingDao: FlockSightingDao,
    @com.aegisnav.app.di.ApplicationScope private val scope: CoroutineScope,
    private val crashReporter: CrashReporter
) {
    companion object {
        private const val TAG = "FlockDetector"
        private const val SIGNATURES_ASSET = "flock_signatures.json"

        /** Confidence threshold to emit a FlockSighting event */
        const val ALERT_THRESHOLD = 0.5f

        /** Confidence per high-confidence signal (direct OUI, name, mfg ID, Raven UUID) */
        const val CONFIDENCE_HIGH = 0.5f
        /** Confidence per low-confidence signal (contract manufacturer OUI) */
        const val CONFIDENCE_LOW = 0.25f
    }

    // Loaded from flock_signatures.json - configurable without a code change
    private var ouiPrefixesHigh: List<String> = emptyList()   // direct Flock Safety registrations
    private var ouiPrefixesLow: List<String> = emptyList()    // contract manufacturers (Liteon, USI)
    private var ouiSoundThinking: List<String> = emptyList()  // SoundThinking/ShotSpotter
    private var bleDeviceNamePatterns: List<String> = emptyList()
    private var bleManufacturerPrefixes: List<String> = emptyList()
    private var bleServiceUuidsRaven: Set<String> = emptySet()
    private var wifiSsidPatterns: List<String> = emptyList()

    // scope injected via @ApplicationScope (SupervisorJob + Dispatchers.IO)

    private val _sightings = MutableStateFlow<FlockSighting?>(null)
    val sightings: StateFlow<FlockSighting?> = _sightings

    // Per-device deduplication cooldown to prevent DB spam
    private val lastSightingTime = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val SIGHTING_COOLDOWN_MS = 5 * 60 * 1000L  // 5 minutes

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

    init {
        loadSignatures()
    }

    // ── Signature loading ────────────────────────────────────────────────────

    private fun loadSignatures() {
        try {
            context.assets.open(SIGNATURES_ASSET).use { stream ->
                val json = JSONObject(stream.bufferedReader().readText())
                ouiPrefixesHigh = parseStringArray(json.optJSONArray("oui_prefixes_high"))
                    .map { it.uppercase() }
                ouiPrefixesLow = parseStringArray(json.optJSONArray("oui_prefixes_low"))
                    .map { it.uppercase() }
                ouiSoundThinking = parseStringArray(json.optJSONArray("oui_soundthinking"))
                    .map { it.uppercase() }
                bleDeviceNamePatterns = parseStringArray(json.optJSONArray("ble_device_name_patterns"))
                bleManufacturerPrefixes = parseStringArray(json.optJSONArray("ble_manufacturer_prefixes"))
                    .map { it.lowercase() }
                bleServiceUuidsRaven = parseStringArray(json.optJSONArray("ble_service_uuids_raven"))
                    .map { it.lowercase() }.toSet()
                wifiSsidPatterns = parseStringArray(json.optJSONArray("wifi_ssid_patterns"))
                AppLog.i(TAG, "Loaded signatures: " +
                    "${ouiPrefixesHigh.size} OUI-high, ${ouiPrefixesLow.size} OUI-low, " +
                    "${ouiSoundThinking.size} SoundThinking, " +
                    "${bleDeviceNamePatterns.size} names, " +
                    "${bleManufacturerPrefixes.size} mfg prefixes, " +
                    "${bleServiceUuidsRaven.size} Raven UUIDs, " +
                    "${wifiSsidPatterns.size} SSID patterns")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to load flock_signatures.json: ${e.message}")
        }
    }

    private fun parseStringArray(arr: JSONArray?): List<String> {
        arr ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    // ── Scan ingestion ────────────────────────────────────────────────────────

    /**
     * Process a BLE scan log entry. Called by ScanService for every BLE result.
     * All processing is in-memory; disk write only occurs when threshold is met.
     *
     * Confidence scoring:
     *   High-confidence signals (direct OUI, device name, mfg company ID, Raven UUID): +0.5 each
     *   Low-confidence signals (contract mfr OUI): +0.25 each
     *   Alert threshold: 0.5 — any single high-conf match triggers; two low-conf matches trigger
     */
    fun onBleScanResult(log: ScanLog) {
        val lat = log.lat ?: return
        val lon = log.lng ?: return
        val matchedSignals = mutableListOf<Pair<String, Float>>() // signal label → confidence

        val macUpper = log.deviceAddress.uppercase()
        val oui = macUpper.take(8) // "AA:BB:CC"

        // ── High-confidence Flock Safety OUI prefixes (direct IEEE registrations) ──────
        if (ouiPrefixesHigh.isNotEmpty() && ouiPrefixesHigh.any { oui.startsWith(it) }) {
            matchedSignals.add("oui_high:$oui" to 0.5f)
        }

        // ── Low-confidence contract manufacturer OUIs (Liteon, USI — also ship other devices) ──
        if (matchedSignals.isEmpty() && ouiPrefixesLow.isNotEmpty() &&
            ouiPrefixesLow.any { oui.startsWith(it) }) {
            matchedSignals.add("oui_low:$oui" to 0.25f)
        }

        // ── SoundThinking / ShotSpotter OUI ──────────────────────────────────
        if (ouiSoundThinking.isNotEmpty() && ouiSoundThinking.any { oui.startsWith(it) }) {
            matchedSignals.add("oui_soundthinking:$oui" to 0.5f)
        }

        // ── BLE advertised device name patterns ──────────────────────────────
        val name = log.deviceName ?: ""
        if (name.isNotBlank() && bleDeviceNamePatterns.isNotEmpty()) {
            val matched = bleDeviceNamePatterns.firstOrNull {
                name.contains(it, ignoreCase = true)
            }
            if (matched != null) {
                matchedSignals.add("ble_name:$matched" to 0.5f)
            }
        }

        // ── BLE manufacturer company ID (XUNTONG 0x09C8 → hex prefix "c809") ──────────
        // ScanService stores "c809..." prefix when XUNTONG manufacturer data is present.
        val mfgHex = log.manufacturerDataHex?.lowercase() ?: ""
        if (mfgHex.isNotEmpty() && bleManufacturerPrefixes.isNotEmpty()) {
            val matched = bleManufacturerPrefixes.firstOrNull { mfgHex.startsWith(it) }
            if (matched != null) {
                matchedSignals.add("ble_mfg_id:$matched" to 0.5f)
            }
        }

        // ── Raven gunshot detector service UUIDs (custom UUIDs: 3100–3500 range) ──────
        // ScanService now passes advertised service UUIDs from ScanResult.scanRecord?.serviceUuids.
        // Only custom Raven UUIDs (not standard BT SIG UUIDs) are matched to avoid false positives.
        val serviceUuidList = log.serviceUuids
            ?.split(",")
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        if (serviceUuidList.isNotEmpty() && bleServiceUuidsRaven.isNotEmpty()) {
            val matched = serviceUuidList.firstOrNull { it in bleServiceUuidsRaven }
            if (matched != null) {
                matchedSignals.add("raven_uuid:$matched" to 0.5f)
            }
        }

        if (matchedSignals.isEmpty()) return

        val totalConfidence = matchedSignals.sumOf { it.second.toDouble() }.toFloat()
            .coerceAtMost(1.0f)
        val labels = matchedSignals.map { it.first }
        evaluateAndEmit(log.deviceAddress, lat, lon, log.timestamp, labels, totalConfidence)
    }

    /**
     * Process a WiFi scan log entry. Called by ScanService for every WiFi result.
     * Uses the device address field as BSSID and checks for Flock SSID patterns
     * stored in the extra field - see ScanService WiFi processing.
     */
    fun onWifiScanResult(log: ScanLog, ssid: String?) {
        val lat = log.lat ?: return
        val lon = log.lng ?: return
        val matchedSignals = mutableListOf<Pair<String, Float>>()

        // ── WiFi SSID pattern check ───────────────────────────────────────────
        if (!ssid.isNullOrBlank() && wifiSsidPatterns.isNotEmpty()) {
            val matched = wifiSsidPatterns.firstOrNull { ssid.contains(it, ignoreCase = true) }
            if (matched != null) {
                matchedSignals.add("wifi_ssid:$matched" to 0.5f)
            }
        }

        // ── OUI prefix checks on BSSID ────────────────────────────────────────
        val bssidUpper = log.deviceAddress.uppercase()
        val oui = bssidUpper.take(8)
        if (ouiPrefixesHigh.isNotEmpty() && ouiPrefixesHigh.any { oui.startsWith(it) }) {
            matchedSignals.add("oui_high:$oui" to 0.5f)
        } else if (ouiPrefixesLow.isNotEmpty() && ouiPrefixesLow.any { oui.startsWith(it) }) {
            matchedSignals.add("oui_low:$oui" to 0.25f)
        }
        if (ouiSoundThinking.isNotEmpty() && ouiSoundThinking.any { oui.startsWith(it) }) {
            matchedSignals.add("oui_soundthinking:$oui" to 0.5f)
        }

        if (matchedSignals.isEmpty()) return

        val totalConfidence = matchedSignals.sumOf { it.second.toDouble() }.toFloat()
            .coerceAtMost(1.0f)
        val labels = matchedSignals.map { it.first }
        evaluateAndEmit(log.deviceAddress, lat, lon, log.timestamp, labels, totalConfidence)
    }

    // ── Evaluation ────────────────────────────────────────────────────────────

    private fun evaluateAndEmit(deviceAddress: String, lat: Double, lon: Double, timestamp: Long,
                               matchedSignals: List<String>, confidence: Float) {
        if (confidence < ALERT_THRESHOLD) return

        // Per-device deduplication: key on device MAC address so each physical device
        // has its own cooldown bucket regardless of which signals matched.
        val deviceId = deviceAddress.uppercase()
        val now = System.currentTimeMillis()
        val lastSeen = lastSightingTime[deviceId] ?: 0L
        if (now - lastSeen < SIGHTING_COOLDOWN_MS) return
        lastSightingTime[deviceId] = now

        val matchedJson = buildMatchedSignalsJson(matchedSignals)
        val sighting = FlockSighting(
            id = UUID.randomUUID().toString(),
            lat = lat,
            lon = lon,
            timestamp = timestamp,
            confidence = confidence,
            matchedSignals = matchedJson,
            reported = false
        )

        AppLog.i(TAG, "FlockSighting detected: confidence=$confidence signals=$matchedSignals")
        _sightings.value = sighting
        sentryCaptureThrottled("flock_${deviceAddress.take(8)}", 300_000L,
            "Flock detection: mac=${deviceAddress.take(8)}XX:XX confidence=${sighting.confidence} type=${sighting.matchedSignals}")

        scope.launch {
            flockSightingDao.insert(sighting)
        }
    }

    private fun buildMatchedSignalsJson(signals: List<String>): String {
        return try {
            JSONArray(signals).toString()
        } catch (e: org.json.JSONException) { AppLog.w(TAG, "buildMatchedSignalsJson failed: ${e.message}"); "[]" }
    }
}
