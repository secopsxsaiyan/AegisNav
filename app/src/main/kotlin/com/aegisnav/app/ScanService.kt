package com.aegisnav.app

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import com.aegisnav.app.baseline.BaselineEngine
import com.aegisnav.app.intelligence.ConvoyDetector
import com.aegisnav.app.intelligence.CoordinatedSurveillanceDetector
import com.aegisnav.app.intelligence.EnhancedPersistenceScorer
import com.aegisnav.app.intelligence.FollowingDetector
import com.aegisnav.app.intelligence.MultiWindowAnalyzer
import com.aegisnav.app.intelligence.SsidPatternAnalyzer
import com.aegisnav.app.crash.CrashReporter
import com.aegisnav.app.data.dao.ALPRBlocklistDao
import com.aegisnav.app.signal.SignalTriangulator
import com.aegisnav.app.util.AppLog
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aegisnav.app.correlation.CorrelationEngine
import com.aegisnav.app.correlation.CrossCorrelationEngine
import com.aegisnav.app.correlation.MacCorrelationEngine
import com.aegisnav.app.data.model.ScanLog
import com.aegisnav.app.data.model.ThreatEvent
import com.aegisnav.app.data.repository.IgnoreListRepository
import com.aegisnav.app.data.repository.ThreatEventRepository
import com.aegisnav.app.flock.FlockDetector
import com.aegisnav.app.flock.FlockReportingCoordinator
import com.aegisnav.app.police.PoliceDetector
import com.aegisnav.app.police.PoliceReportingCoordinator
import com.aegisnav.app.tracker.AlertDeduplicationManager
import com.aegisnav.app.tracker.BeaconHistoryManager
import com.aegisnav.app.tracker.OuiLookup
import com.aegisnav.app.tracker.TrackerDetectionEngine
import com.aegisnav.app.tracker.TrackerAlert
import com.aegisnav.app.tracker.TrackerType
import com.aegisnav.app.tracker.TrackerTypeClassifier
import com.aegisnav.app.signal.RssiDistanceEstimator
import com.aegisnav.app.util.AlertTtsManager
import com.aegisnav.app.AppLifecycleTracker
import com.aegisnav.app.scan.BackgroundScanReceiver
import com.aegisnav.app.scan.BluetoothStateMonitor
import com.aegisnav.app.scan.OpportunisticScanner
import com.aegisnav.app.scan.ScanOrchestrator
import androidx.work.WorkManager
import com.aegisnav.app.watchlist.WatchlistAlertManager
import com.aegisnav.app.security.SecureDataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.first

import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

@AndroidEntryPoint
class ScanService : Service() {

    companion object {
        /** Weak reference to the running instance for settings callbacks. */
        @Volatile var instance: ScanService? = null
            private set

        const val CHANNEL_ID = "SCAN_CHANNEL"
        const val NOTIF_ID_FOREGROUND = 1
        const val NOTIF_ID_ALERT = 2
        const val NOTIF_ID_TRACKER = 3
        const val NOTIF_ID_MAC_LEAK = 4
        const val NOTIF_ID_CONVOY = 5
        const val NOTIF_ID_COORDINATED = 6

        // ── Feature 2.7: Intelligent scan timing ─────────────────────────────
        /** WiFi scan interval when no threats detected (idle / power-save). */
        const val WIFI_SCAN_INTERVAL_IDLE_MS       = 45_000L
        /** WiFi scan interval when a suspicious device is present. */
        const val WIFI_SCAN_INTERVAL_SUSPICIOUS_MS = 10_000L
        /** WiFi scan interval during active tracker/police alert. */
        const val WIFI_SCAN_INTERVAL_ALERT_MS      = 10_000L
        /** Legacy name — resolves to idle value; runtime uses [currentWifiInterval]. */
        const val WIFI_SCAN_INTERVAL_MS            = WIFI_SCAN_INTERVAL_IDLE_MS

        /** Transition back to IDLE if no new threats in this window. */
        const val THREAT_IDLE_TIMEOUT_MS           = 5 * 60_000L
        /** Burst BLE scan window after a tracker/police alert fires. */
        const val BLE_BURST_DURATION_MS            = 60_000L

        const val IGNORE_LIST_REFRESH_MS = 5 * 60_000L

        /** Minimum gap between batched detection notifications of the same type. */
        const val NOTIFICATION_COOLDOWN_MS = 60_000L
    }

    // ── Feature 2.7: threat-state tracking ───────────────────────────────────
    private enum class ThreatState { IDLE, SUSPICIOUS, ALERT }

    @Volatile private var threatState: ThreatState = ThreatState.IDLE
    @Volatile private var lastThreatDetectedMs: Long = 0L
    @Volatile private var bleBurstUntilMs: Long = 0L
    @Volatile private var currentBleScanMode: Int = ScanSettings.SCAN_MODE_LOW_POWER

    /** Returns the WiFi scan interval appropriate for the current [threatState]. */
    private val currentWifiInterval: Long
        get() = when (threatState) {
            ThreatState.IDLE       -> WIFI_SCAN_INTERVAL_IDLE_MS
            ThreatState.SUSPICIOUS -> WIFI_SCAN_INTERVAL_SUSPICIOUS_MS
            ThreatState.ALERT      -> WIFI_SCAN_INTERVAL_ALERT_MS
        }

    /** User preference for scan intensity on API 36+ (read from DataStore). */
    @Volatile private var userScanIntensity: String = "balanced" // "balanced" or "low_latency"

    /** Called from MainActivity when the user changes the scan intensity toggle. */
    fun setScanIntensity(intensity: String) {
        userScanIntensity = intensity
        AppLog.i("ScanService", "Scan intensity changed to: $intensity")
        sentryCaptureThrottled("intensity", 60_000L, "Scan intensity changed: $intensity (API ${Build.VERSION.SDK_INT})")
        restartBleIfModeChanged()
    }

    /** Returns the BLE scan mode appropriate for the current [threatState] / burst window.
     *  Android 16 (API 36) aggressively throttles LOW_POWER callbacks (~19x fewer than API 35).
     *  User can choose BALANCED (battery saver) or LOW_LATENCY (maximum detection) on API 36+.
     */
    private val desiredBleScanMode: Int
        get() = if (System.currentTimeMillis() < bleBurstUntilMs ||
                    threatState != ThreatState.IDLE) {
            ScanSettings.SCAN_MODE_LOW_LATENCY
        } else if (Build.VERSION.SDK_INT >= 36) {
            if (userScanIntensity == "low_latency") ScanSettings.SCAN_MODE_LOW_LATENCY
            else ScanSettings.SCAN_MODE_BALANCED
        } else {
            ScanSettings.SCAN_MODE_LOW_POWER
        }

    @Inject lateinit var scanState: ScanState
    @Inject lateinit var correlationEngine: CorrelationEngine
    @Inject lateinit var macCorrelationEngine: MacCorrelationEngine
    @Inject lateinit var crossCorrelationEngine: CrossCorrelationEngine
    @Inject lateinit var ignoreListRepository: IgnoreListRepository
    @Inject lateinit var trackerDetectionEngine: TrackerDetectionEngine
    @Inject lateinit var threatEventRepository: ThreatEventRepository
    @Inject lateinit var flockDetector: FlockDetector
    @Inject lateinit var flockReportingCoordinator: FlockReportingCoordinator
    @Inject lateinit var policeDetector: PoliceDetector
    @Inject lateinit var policeReportingCoordinator: PoliceReportingCoordinator
    @Inject lateinit var alertTtsManager: AlertTtsManager
    @Inject lateinit var baselineEngine: BaselineEngine
    @Inject lateinit var signalTriangulator: SignalTriangulator
    @Inject lateinit var rssiDistanceEstimator: RssiDistanceEstimator
    @Inject lateinit var trackerTypeClassifier: TrackerTypeClassifier
    @Inject lateinit var beaconHistoryManager: BeaconHistoryManager
    @Inject lateinit var alertDeduplicationManager: AlertDeduplicationManager
    @Inject lateinit var scanOrchestrator: ScanOrchestrator
    @Inject lateinit var officerUnitDao: com.aegisnav.app.police.OfficerUnitDao
    @Inject lateinit var opportunisticScanner: OpportunisticScanner
    @Inject lateinit var alprBlocklistDao: ALPRBlocklistDao
    @Inject lateinit var watchlistAlertManager: WatchlistAlertManager

    // ── Phase 4: CYT-NG Intelligence engines ──────────────────────────────────
    @Inject lateinit var enhancedPersistenceScorer: EnhancedPersistenceScorer
    @Inject lateinit var followingDetector: FollowingDetector
    @Inject lateinit var coordinatedSurveillanceDetector: CoordinatedSurveillanceDetector
    @Inject lateinit var multiWindowAnalyzer: MultiWindowAnalyzer
    @Inject lateinit var ssidPatternAnalyzer: SsidPatternAnalyzer
    @Inject lateinit var convoyDetector: ConvoyDetector
    @Inject lateinit var secureLogger: com.aegisnav.app.util.SecureLogger
    @Inject lateinit var crashReporter: CrashReporter

    // Cooldown timestamps (ms) for TTS alerts to prevent spam
    @Volatile private var lastTrackerTtsMs = 0L
    private val TTS_COOLDOWN_MS = 5 * 60_000L  // 5 minutes

    // ── Convoy / Coordinated cooldowns ────────────────────────────────────────
    private val CONVOY_TTS_COOLDOWN_MS = 10 * 60_000L         // 10 min TTS cooldown
    private val COORDINATED_TTS_COOLDOWN_MS = 10 * 60_000L    // 10 min TTS cooldown
    private val CONVOY_DB_DEDUP_MS = 5 * 60_000L              // 5 min DB dedup per group
    private val COORDINATED_DB_DEDUP_MS = 5 * 60_000L         // 5 min DB dedup per group

    @Volatile private var lastConvoyTtsMs = 0L
    @Volatile private var lastCoordinatedTtsMs = 0L
    // Group dedup: sorted MAC group key → last DB insert timestamp
    private val convoyGroupLastInsert = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val coordinatedGroupLastInsert = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // Per-event-type Sentry rate limiting: key → last send timestamp (ms)
    private val lastSentryEventTime = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ── Navigation grace period ────────────────────────────────────────────────
    /** When navigation starts, heavy detection engines skip for 2s to reduce jank. */
    @Volatile var navStartGracePeriodUntilMs = 0L

    /** Called when navigation starts — suppresses heavy BLE engines for 2 seconds. */
    fun onNavigationStarted() {
        navStartGracePeriodUntilMs = System.currentTimeMillis() + 2000L
        AppLog.d("ScanService", "Navigation grace period started — heavy engines suppressed for 2s")
    }

    // ── Scan diagnostics ──────────────────────────────────────────────────────
    private var bleForegroundCallbackCount = 0L
    private var bleBackgroundCallbackCount = 0L
    private var wifiCallbackCount = 0L
    private var scanDiagStartMs = 0L

    /**
     * Reports scan health diagnostics to both local log and GlitchTip/Sentry.
     * Called periodically (every 5 min) from the WiFi scan loop.
     * If BLE foreground callbacks are zero after 5 min, something is wrong.
     */
    private fun reportScanDiagnostics() {
        val uptimeMs = System.currentTimeMillis() - scanDiagStartMs
        val uptimeMin = uptimeMs / 60_000
        val budget = scanOrchestrator.availableBudget()
        val pending = scanOrchestrator.pendingCount()
        val msg = "ScanDiag: uptime=${uptimeMin}m bleFG=$bleForegroundCallbackCount " +
            "bleBG=$bleBackgroundCallbackCount wifi=$wifiCallbackCount " +
            "budget=$budget/${ ScanOrchestrator.MAX_SCANS_PER_WINDOW} pending=$pending " +
            "API=${Build.VERSION.SDK_INT} device=${Build.MODEL}"
        AppLog.i("ScanService", msg)

        // Report all scan diagnostics to GlitchTip as info events (throttled: 4 min per key)
        val scanDiagNow = System.currentTimeMillis()
        val scanDiagLast = lastSentryEventTime["scandiag"] ?: 0L
        if (scanDiagNow - scanDiagLast >= 240_000L) {
            lastSentryEventTime["scandiag"] = scanDiagNow
            crashReporter.captureEvent("INFO", msg, mapOf(
                "scan.ble_fg_count" to bleForegroundCallbackCount.toString(),
                "scan.wifi_count" to wifiCallbackCount.toString(),
                "scan.uptime_min" to uptimeMin.toString(),
                "scan.budget" to "$budget/${ScanOrchestrator.MAX_SCANS_PER_WINDOW}",
                "scan.intensity" to userScanIntensity,
                "device.api" to Build.VERSION.SDK_INT.toString(),
                "device.model" to Build.MODEL
            ))

            // Escalate to WARNING if BLE foreground scan produced zero results after 5+ min
            if (uptimeMin >= 5 && bleForegroundCallbackCount == 0L) {
                crashReporter.captureEvent("WARNING", "BLE foreground scan zero results after ${uptimeMin}m", mapOf(
                    "scan.ble_fg_count" to "0",
                    "scan.ble_bg_count" to bleBackgroundCallbackCount.toString(),
                    "scan.wifi_count" to wifiCallbackCount.toString(),
                    "scan.budget" to "$budget/${ScanOrchestrator.MAX_SCANS_PER_WINDOW}",
                    "scan.pending" to pending.toString(),
                    "device.api" to Build.VERSION.SDK_INT.toString(),
                    "device.model" to Build.MODEL
                ))
            }
        }
    }

    // ── Batched notification system ────────────────────────────────────────────
    /** Pending alert messages per type, accumulated until cooldown elapses. */
    private val pendingNotifications = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()
    /** Timestamp of the last posted notification per type. */
    private val lastNotificationTime = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Timestamp when the first pending message for each type was added (for flush-on-timeout). */
    private val pendingFirstAddedTime = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val scanScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val scanErrorHandler = CoroutineExceptionHandler { _, throwable ->
        AppLog.e("ScanService", "Scan coroutine error: ${throwable.message}", throwable)
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
    }
    private val leScanner get() = bluetoothAdapter?.bluetoothLeScanner
    private val wifiManager by lazy { applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private val locationManager by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }

    private var ignoreSet: Set<String> = emptySet()
    @Volatile private var cachedLocation: Location? = null
    private var wifiReceiverRegistered = false

    // ── Location listener ──────────────────────────────────────────────────
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            cachedLocation = location
            trackerDetectionEngine.updateUserLocation(location.latitude, location.longitude)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // ── WiFi scan receiver ────────────────────────────────────────────────────
    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (ContextCompat.checkSelfPermission(this@ScanService, Manifest.permission.ACCESS_WIFI_STATE)
                != PackageManager.PERMISSION_GRANTED) return
            scanScope.launch(scanErrorHandler) {
                try {
                    val location = cachedLocation
                    @Suppress("DEPRECATION")
                    wifiManager.scanResults.forEach { result ->
                        wifiCallbackCount++
                        val bssid = result.BSSID ?: return@forEach
                        if (bssid in ignoreSet) return@forEach
                        val log = ScanLog(
                            deviceAddress = bssid,
                            rssi = result.level,
                            timestamp = System.currentTimeMillis(),
                            isTracker = false,
                            manufacturerDataHex = null,
                            scanType = "WIFI",
                            lat = location?.latitude,
                            lng = location?.longitude,
                            ssid = result.SSID?.takeIf { it.isNotBlank() }
                        )
                        correlationEngine.addToRingBuffer(log)

                        // Phase 2A: MAC correlation + cross-correlation
                        macCorrelationEngine.onScanResult(log)
                        crossCorrelationEngine.onWifiResult(log)

                        // Resolve virtual MAC for tracker engine (stable across rotations)
                        val virtualMac = macCorrelationEngine.resolveVirtualMac(log.deviceAddress)
                        val trackerLog = if (virtualMac != log.deviceAddress)
                            log.copy(deviceAddress = virtualMac) else log

                        // For police detector: prefer leaked global MAC (valid OUI); else keep original
                        val policeLog = macCorrelationEngine.getGroupForMac(log.deviceAddress)
                            ?.leakedGlobalMac
                            ?.let { log.copy(deviceAddress = it) }
                            ?: log

                        trackerDetectionEngine.onScanResult(trackerLog)
                        withContext(Dispatchers.Main) { scanState.scannedDevices[log.deviceAddress] = log }
                        flockDetector.onWifiScanResult(log, result.SSID)
                        policeDetector.onWifiScanResult(policeLog, result.SSID)

                        // Phase 4.8: SSID pattern analysis — cop detection boost
                        ssidPatternAnalyzer.onWifiScanResult(log)?.let { match ->
                            AppLog.i("ScanService",
                                "Phase4/SSID match bssid=${match.bssid} ssid=${match.ssid} " +
                                "reason=${match.matchReason} confidence=${match.confidence}")
                            // Cross-reference back so future SSID hits on this BSSID get boosted
                            ssidPatternAnalyzer.markPoliceConfirmed(match.bssid)
                        }

                        // Watchlist: check if this WiFi MAC is watched
                        val wifiLoc = cachedLocation
                        watchlistAlertManager.onDeviceSeen(
                            mac = bssid,
                            lat = wifiLoc?.latitude ?: 0.0,
                            lon = wifiLoc?.longitude ?: 0.0,
                            rssi = result.level,
                            timestamp = System.currentTimeMillis()
                        )
                    }
                } catch (e: Exception) {
                    AppLog.e("WiFiScan", "Error processing WiFi results", e)
                }
            }
        }
    }

    // ── BLE scan callback ─────────────────────────────────────────────────────
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            bleForegroundCallbackCount++
            if (result.device.address in ignoreSet) return

            scanScope.launch(scanErrorHandler) {
                try {
                    val location = cachedLocation
                    // Apple manufacturer data (AirTag detection)
                    val mfgApple = result.scanRecord?.getManufacturerSpecificData(0x004C)
                    val isAirTag = mfgApple?.let { TrackerDetector.isAirTagManufacturerData(it) } ?: false

                    // Flock Safety / XUNTONG manufacturer data (company ID 0x09C8)
                    // Stored as "c809" prefix + payload hex so FlockDetector can match the prefix
                    val mfgFlock = result.scanRecord?.getManufacturerSpecificData(0x09C8)
                    val mfgHex = when {
                        mfgFlock != null -> "c809" + mfgFlock.joinToString("") { "%02x".format(it) }
                        // [SEC-FIX] Prepend "4c00" (LE company ID for Apple 0x004C) so classifyFromHex()
                        // correctly identifies AirTag (subtype 0x12) and FindMy (subtype 0x07).
                        mfgApple != null -> "4c00" + mfgApple.joinToString("") { "%02x".format(it) }
                        else -> null
                    }

                    // Advertised service UUIDs — key for Raven gunshot detector detection
                    val serviceUuids = result.scanRecord?.serviceUuids
                        ?.joinToString(",") { it.toString() }

                    // BLE advertised device name
                    val deviceName = result.scanRecord?.deviceName
                        ?.takeIf { it.isNotBlank() }

                    // Phase 3 — classify tracker type from manufacturer data + service UUIDs
                    val trackerType = trackerTypeClassifier.classifyFromHex(mfgHex, serviceUuids)

                    val log = ScanLog(
                        deviceAddress = result.device.address,
                        rssi = result.rssi,
                        timestamp = System.currentTimeMillis(),
                        isTracker = isAirTag || trackerType != TrackerType.UNKNOWN,
                        manufacturerDataHex = mfgHex,
                        scanType = "BLE",
                        lat = location?.latitude,
                        lng = location?.longitude,
                        serviceUuids = serviceUuids,
                        deviceName = deviceName,
                        trackerType = trackerType.name
                    )

                    correlationEngine.addToRingBuffer(log)

                    // Phase 2A: MAC correlation + cross-correlation
                    macCorrelationEngine.onScanResult(log)
                    crossCorrelationEngine.onBleResult(log)

                    // Resolve virtual MAC for tracker engine (stable across rotations)
                    val virtualMac = macCorrelationEngine.resolveVirtualMac(log.deviceAddress)
                    val trackerLog = if (virtualMac != log.deviceAddress)
                        log.copy(deviceAddress = virtualMac) else log

                    // For police detector: prefer leaked global MAC (valid OUI); else keep original
                    val policeLog = macCorrelationEngine.getGroupForMac(log.deviceAddress)
                        ?.leakedGlobalMac
                        ?.let { log.copy(deviceAddress = it) }
                        ?: log

                    withContext(Dispatchers.Main) { scanState.scannedDevices[log.deviceAddress] = log }

                    // Feed tracker detection engine (in-memory, no disk writes)
                    trackerDetectionEngine.onScanResult(trackerLog)

                    // Phase 2B — feed RssiDistanceEstimator + SignalTriangulator
                    val distM = rssiDistanceEstimator.estimate(log.deviceAddress, log.rssi, isBle = true)
                    location?.let { loc ->
                        signalTriangulator.addObservation(
                            log.deviceAddress, loc.latitude, loc.longitude, distM, log.rssi
                        )
                        // Phase 5: calibrate estimator when triangulation is high-confidence
                        val triResult = signalTriangulator.currentResults[log.deviceAddress]
                        if (triResult != null && triResult.gdop < 4.0) {
                            val triangDist = haversineMetersService(
                                loc.latitude, loc.longitude,
                                triResult.estimatedLat, triResult.estimatedLon
                            )
                            rssiDistanceEstimator.calibrate(
                                log.deviceAddress, log.rssi, isBle = true,
                                trueDistanceMeters = triangDist
                            )
                            AppLog.d("ScanService",
                                "Calibrated RSSI for ${log.deviceAddress}: " +
                                "dist=${triangDist.toInt()}m gdop=${"%.2f".format(triResult.gdop)}")
                        }
                        // Phase 5 / Task 4: determine device category for map icon
                        val freshResult = signalTriangulator.currentResults[log.deviceAddress]
                        if (freshResult != null) {
                            val isPoliceMac = policeDetector.isKnownPoliceMac(log.deviceAddress) ||
                                policeDetector.isKnownPoliceMac(policeLog.deviceAddress)
                            val category: String? = when {
                                isPoliceMac -> "POLICE"
                                else -> {
                                    // Check if triangulated position is within 50m of ALPR camera
                                    val lat = freshResult.estimatedLat
                                    val lon = freshResult.estimatedLon
                                    val deltaLat = 50.0 / 111320.0
                                    val deltaLon = 50.0 / (111320.0 * cos(
                                        kotlin.math.PI / 180.0 * lat))
                                    val nearbyAlpr = alprBlocklistDao.getNearby(
                                        lat - deltaLat, lat + deltaLat,
                                        lon - deltaLon, lon + deltaLon
                                    )
                                    if (nearbyAlpr.isNotEmpty()) "ALPR" else null
                                }
                            }
                            signalTriangulator.setDeviceCategory(log.deviceAddress, category)
                        }
                    }

                    // Phase 3 — persist sighting to BeaconHistoryManager
                    beaconHistoryManager.onBleSighting(
                        mac         = log.deviceAddress,
                        rssi        = log.rssi,
                        lat         = location?.latitude,
                        lng         = location?.longitude,
                        trackerType = trackerType
                    )

                    // Feed Flock Safety detector (in-memory; disk write only on threshold)
                    flockDetector.onBleScanResult(log)

                    // Feed police equipment detector (in-memory; disk write only on threshold)
                    policeDetector.onBleScanResult(policeLog)

                    // Phase 4.7: Multi-window time analysis — record sighting for all windows
                    multiWindowAnalyzer.onScanResult(log)

                    // Phase 4.11: Convoy detection — velocity vector correlation
                    convoyDetector.onScanResult(log, cachedLocation?.speed ?: 0f)?.let { alert ->
                        onConvoyAlert(alert)
                    }

                    // Phase 4.4: Coordinated surveillance detection
                    coordinatedSurveillanceDetector.onScanResult(log, cachedLocation?.speed ?: 0f)?.let { alert ->
                        onCoordinatedSurveillanceAlert(alert)
                    }

                    // Watchlist: check if this MAC is watched → may fire WATCHLIST alert
                    val loc = cachedLocation
                    watchlistAlertManager.onDeviceSeen(
                        mac = log.deviceAddress,
                        lat = loc?.latitude ?: 0.0,
                        lon = loc?.longitude ?: 0.0,
                        rssi = log.rssi,
                        timestamp = log.timestamp
                    )
                } catch (e: Exception) {
                    AppLog.e("BLEScan", "Error processing scan result", e)
                }
            }
        }

        /** Batched scan results — used when setReportDelay() > 0 (API 36+ optimization). */
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results ?: return
            results.forEach { result -> onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result) }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @android.annotation.SuppressLint("MissingPermission") // Guarded: BLE/WiFi permissions checked before scan calls
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        // intent == null means Android restarted the service after a system kill (START_STICKY).
        // Only resume if the user actually wanted scanning — if they stopped it explicitly,
        // honour their intent and stay stopped.
        if (intent == null) {
            // Pattern C: read user preference in a coroutine; stop self inside if flag is false.
            // We launch+join on IO to avoid blocking the main thread — this is onStartCommand
            // which runs on the main thread, so we must dispatch off it.
            val userWants = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                SecureDataStore.get(this@ScanService, "an_prefs")
                    .data.first()[booleanPreferencesKey("user_wants_scanning")] ?: false
            }
            if (!userWants) {
                AppLog.i("ScanService", "System restart suppressed — user stopped scanning explicitly")
                stopSelf(); return START_NOT_STICKY
            }
            AppLog.i("ScanService", "System restart accepted — resuming scan per user intent")
        }

        // Read user scan intensity preference (API 36+ only)
        if (Build.VERSION.SDK_INT >= 36) {
            userScanIntensity = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                SecureDataStore.get(this@ScanService, "an_prefs")
                    .data.first()[androidx.datastore.preferences.core.stringPreferencesKey("scan_intensity")] ?: "balanced"
            }
            AppLog.i("ScanService", "Scan intensity preference: $userScanIntensity")
        }

        trackerDetectionEngine.onScanSessionStart()
        scanState.setScanning(true)
        scanDiagStartMs = System.currentTimeMillis()
        bleForegroundCallbackCount = 0L
        bleBackgroundCallbackCount = 0L
        wifiCallbackCount = 0L

        // ServiceCompat.startForeground handles the API 29+ 3-arg overload automatically.
        // Both types are required on Android 14 (API 34): location (GPS) + connectedDevices (BLE).
        ServiceCompat.startForeground(
            this,
            NOTIF_ID_FOREGROUND,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AegisNav")
                .setContentText("Scanning BLE + WiFi…")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )

        if (!hasRequiredPermissions()) {
            AppLog.w("ScanService", "Missing permissions - stopping")
            stopSelf(); return START_NOT_STICKY
        }

        // Start Android native location updates
        startLocationUpdates()

        // Load ignore list + refresh periodically
        scanScope.launch(scanErrorHandler) {
            refreshIgnoreList()
            while (true) {
                delay(IGNORE_LIST_REFRESH_MS)
                refreshIgnoreList()
            }
        }

        // Collect tracker alerts and post notifications
        scanScope.launch(scanErrorHandler) {
            trackerDetectionEngine.alerts.collect { alert ->
                onTrackerAlert(alert)
            }
        }

        // Collect MAC leak alerts (Phase 2A — 2.2)
        scanScope.launch(scanErrorHandler) {
            macCorrelationEngine.leakAlerts.collect { alert ->
                onMacLeakAlert(alert)
            }
        }

        // Phase 3 — Collect BeaconHistoryManager suspicious-device events (3.6/3.8)
        scanScope.launch(scanErrorHandler) {
            beaconHistoryManager.suspiciousDevices.collect { event ->
                val key = "BEACON_HISTORY:${event.mac}"
                if (alertDeduplicationManager.shouldEmit(
                        key, AlertDeduplicationManager.TRACKER_ALERT_WINDOW_MS)) {
                    AppLog.i("ScanService", "Suspicious tracker ${event.mac} " +
                        "type=${event.trackerType} locs=${event.locationCount} " +
                        "duration=${event.durationMs / 60_000L}min")
                }
            }
        }

        // Start Flock Safety reporting coordinator (auto-report + notifications + P2P)
        flockReportingCoordinator.start()
        policeReportingCoordinator.start()

        // Start BLE scan (guard: BLUETOOTH_SCAN required on API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.e("ScanService", "BLUETOOTH_SCAN permission not granted"); stopSelf(); return START_NOT_STICKY
        }
        // 3.4 — Route foreground scan through ScanOrchestrator (HIGH priority).
        // The orchestrator enforces Android's 5-scan-per-30s budget across all callers.
        scanOrchestrator.requestScan(ScanOrchestrator.ScanPriority.HIGH, "foreground") {
            val settings = ScanSettings.Builder()
                .setScanMode(desiredBleScanMode)
                .apply {
                    // API 36+ throttles LOW_POWER callbacks aggressively.
                    // Batch reporting accumulates 5s of results → delivered via onBatchScanResults().
                    if (Build.VERSION.SDK_INT >= 36) setReportDelay(5000L)
                }
                .build()
            currentBleScanMode = desiredBleScanMode
            if (leScanner?.startScan(null, settings, scanCallback) == null) {
                AppLog.e("ScanService", "BLE scanner unavailable during orchestrated start")
            }
        }
        if (leScanner == null) {
            AppLog.e("ScanService", "BLE scanner unavailable"); stopSelf(); return START_NOT_STICKY
        }

        // 3.11 — Register persistent PendingIntent scanner.
        // Android delivers BLE results to BackgroundScanReceiver even when app process is dead.
        startPersistentPendingIntentScanner()

        // 3.16 — Start opportunistic scan (zero battery cost piggyback).
        opportunisticScanner.start()

        // Register WiFi receiver + trigger scans every 30s
        if (!wifiReceiverRegistered) {
            ContextCompat.registerReceiver(this, wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
            wifiReceiverRegistered = true
        }
        scanScope.launch(scanErrorHandler) {
            var lastYieldingInterval = WIFI_SCAN_INTERVAL_IDLE_MS

            while (true) {
                // Feature 2.7: adaptive timing — decay threat state toward IDLE over time
                val now = System.currentTimeMillis()
                if (threatState != ThreatState.IDLE &&
                    (now - lastThreatDetectedMs) > THREAT_IDLE_TIMEOUT_MS) {
                    val prev = threatState
                    threatState = ThreatState.IDLE
                    AppLog.d("ScanService", "Threat state: $prev → IDLE (timeout)")
                    // Restart BLE at LOW_POWER when going idle
                    restartBleIfModeChanged()
                }

                // Guard: ACCESS_FINE_LOCATION required for WiFi scan results
                if (ContextCompat.checkSelfPermission(this@ScanService, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    try {
                        @Suppress("DEPRECATION")
                        wifiManager.startScan()
                    } catch (e: SecurityException) { AppLog.w("ScanService", "WiFi startScan denied: ${e.message}") }
                }

                // Scan diagnostics — report every 5 minutes
                if (scanDiagStartMs > 0 && (now - scanDiagStartMs) % 300_000L < (currentWifiInterval + 1000L)) {
                    reportScanDiagnostics()
                }

                // Feature 2.7: use dynamic interval based on threat state
                val interval = currentWifiInterval
                if (interval != lastYieldingInterval) {
                    AppLog.d("ScanService", "WiFi interval: ${lastYieldingInterval}ms → ${interval}ms (state=$threatState)")
                    lastYieldingInterval = interval
                }

                // Periodic flush: drain any batches that have been waiting longer than the cooldown
                val flushNow = System.currentTimeMillis()
                for ((type, firstAdded) in pendingFirstAddedTime) {
                    val lastPost = lastNotificationTime[type] ?: 0L
                    if (flushNow - lastPost >= NOTIFICATION_COOLDOWN_MS && flushNow - firstAdded >= NOTIFICATION_COOLDOWN_MS) {
                        val (notifId, titleFn, bodyFn) = when (type) {
                            "CONVOY" -> Triple(
                                NOTIF_ID_CONVOY,
                                { n: Int -> if (n == 1) "🚗 Coordinated movement detected" else "🚗 $n convoy alerts detected" },
                                { msgs: List<String> -> msgs.last() }
                            )
                            "COORDINATED" -> Triple(
                                NOTIF_ID_COORDINATED,
                                { n: Int -> if (n == 1) "👥 Coordinated surveillance detected" else "👥 $n coordinated surveillance alerts" },
                                { msgs: List<String> -> msgs.last() }
                            )
                            "TRACKER" -> Triple(
                                NOTIF_ID_TRACKER,
                                { n: Int -> if (n == 1) "⚠️ Tracking alert" else "⚠️ $n tracker alerts detected" },
                                { msgs: List<String> -> msgs.last() }
                            )
                            "POLICE" -> Triple(
                                NOTIF_ID_ALERT,
                                { n: Int -> if (n == 1) "🚨 Police detection alert" else "🚨 $n police detection alerts" },
                                { msgs: List<String> -> msgs.last() }
                            )
                            else -> continue
                        }
                        flushBatchedNotification(type, notifId, titleFn, bodyFn)
                    }
                }

                delay(interval)
            }
        }

        // Periodic RSSI calibration check (every 30s) — Task 1 / Phase 5
        scanScope.launch(scanErrorHandler) {
            while (isActive) {
                delay(30_000L)
                val loc = cachedLocation ?: continue
                signalTriangulator.currentResults.values
                    .filter { it.gdop < 4.0 }
                    .forEach { result ->
                        val smoothed = rssiDistanceEstimator.smoothedRssi(result.mac, isBle = true)
                        if (smoothed != null) {
                            val dist = haversineMetersService(
                                loc.latitude, loc.longitude,
                                result.estimatedLat, result.estimatedLon
                            )
                            rssiDistanceEstimator.calibrate(
                                result.mac, smoothed.toInt(), isBle = true,
                                trueDistanceMeters = dist
                            )
                        }
                    }
            }
        }

        // Prune expired triangulation markers (1hr TTL)
        scanScope.launch(scanErrorHandler) {
            while (isActive) {
                delay(5 * 60_000L)
                signalTriangulator.pruneExpired()
                // Prune confirmed officer-unit verdicts older than 1 hour
                officerUnitDao.pruneExpiredConfirmed(System.currentTimeMillis() - 3_600_000L)
            }
        }

        // Pulse expiry loop
        scanScope.launch(scanErrorHandler) {
            while (true) {
                delay(5_000L)
                withContext(Dispatchers.Main) { scanState.expirePulses(System.currentTimeMillis()) }
            }
        }

        return START_STICKY
    }

    @android.annotation.SuppressLint("MissingPermission") // Guarded: BLE permission checked in onStartCommand
    override fun onDestroy() {
        instance = null
        super.onDestroy()
        try { leScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        // 3.11 — Stop the PendingIntent-based persistent scanner (best-effort; it will be restarted
        // next time ScanService starts, and we intentionally leave opportunistic alive).
        try { stopPersistentPendingIntentScanner() } catch (_: Exception) {}
        try { unregisterReceiver(wifiReceiver) } catch (_: Exception) {}
        wifiReceiverRegistered = false
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
        scanState.setScanning(false)
        flockReportingCoordinator.stop()
        trackerDetectionEngine.onScanSessionEnd()
        trackerDetectionEngine.clear()
        // Phase 2A: clear MAC and cross-correlation state for this session
        macCorrelationEngine.clear()
        crossCorrelationEngine.clear()
        // Do NOT call trackerDetectionEngine.shutdown() here.
        // The engine is a @Singleton that outlives the service. Cancelling its scope
        // permanently kills all evaluate() coroutines so restart produces zero alerts.
        scanScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Add a helper to restart BLE scan when the desired mode changes.
     * No-op if scan mode is already correct or if BLE scanner is unavailable.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun restartBleIfModeChanged() {
        val desired = desiredBleScanMode
        if (desired == currentBleScanMode) return
        try {
            leScanner?.stopScan(scanCallback)
            val settings = ScanSettings.Builder()
                .setScanMode(desired)
                .apply { if (Build.VERSION.SDK_INT >= 36) setReportDelay(5000L) }
                .build()
            leScanner?.startScan(null, settings, scanCallback)
            currentBleScanMode = desired
            AppLog.d("ScanService", "BLE scan mode changed to $desired")
        } catch (e: Exception) {
            AppLog.w("ScanService", "BLE restart failed: ${e.message}")
        }
    }

    /**
     * Rate-limited crash reporter message to prevent event flooding.
     * Each unique [key] is throttled independently with [cooldownMs] (default 5 min).
     * AppLog calls are NOT affected — those still fire at full rate for local debugging.
     */
    private fun sentryCaptureThrottled(key: String, cooldownMs: Long = 300_000L, message: String) {
        val now = System.currentTimeMillis()
        val last = lastSentryEventTime[key] ?: 0L
        if (now - last >= cooldownMs) {
            lastSentryEventTime[key] = now
            crashReporter.captureMessage(message)
        }
    }

    private fun onTrackerAlert(alert: TrackerAlert) {
        scanScope.launch(scanErrorHandler) {
            // Feature 2.7: transition to ALERT state, trigger BLE burst
            threatState = ThreatState.ALERT
            lastThreatDetectedMs = System.currentTimeMillis()
            bleBurstUntilMs = lastThreatDetectedMs + BLE_BURST_DURATION_MS
            restartBleIfModeChanged()
            AppLog.d("ScanService", "Threat state → ALERT (tracker), BLE burst for ${BLE_BURST_DURATION_MS / 1000}s")
            sentryCaptureThrottled("tracker_${alert.mac.take(8)}", 300_000L, "Tracker alert: mac=${alert.mac.take(8)}XX:XX spread=${alert.maxSpreadMeters.toInt()}m stops=${alert.stopCount}")

            val durationMin = (alert.lastSeen - alert.firstSeen) / 60_000L
            val spreadM = alert.maxSpreadMeters
            val stopCount = alert.stopCount
            val deviceLabel = alert.manufacturer ?: "Unknown device"

            val (title, body) = when {
                spreadM >= 500.0 && durationMin >= 20 -> {
                    // HIGH-risk case: fire TTS alert (with cooldown to prevent spam)
                    val now = System.currentTimeMillis()
                    if (now - lastTrackerTtsMs >= TTS_COOLDOWN_MS) {
                        lastTrackerTtsMs = now
                        alertTtsManager.speakIfEnabled("Possible high risk tracker", com.aegisnav.app.util.TtsCategory.TRACKER, "tracker_alert")
                        secureLogger.i("ScanService", "TTS: Possible high risk tracker (mac=${alert.mac})")
                    }
                    Pair(
                        "⚠️ Tracking alert",
                        "A device has been following you for $durationMin min across $stopCount stops. Tap to review."
                    )
                }
                spreadM >= 300.0 && durationMin >= 10 -> Pair(
                    "Device following your route",
                    "A $deviceLabel has been near you for $durationMin min across $stopCount stops."
                )
                else -> Pair(
                    "Possible nearby device",
                    "A device has been near you for $durationMin min - probably not a concern. Tap to review."
                )
            }

            // Persist as ThreatEvent
            val detailJson = buildTrackerAlertJson(alert)
            threatEventRepository.insert(
                ThreatEvent(
                    type = "TRACKER",
                    mac = alert.mac,
                    timestamp = alert.lastSeen,
                    detailJson = detailJson
                )
            )

            // Post batched notification (60s cooldown)
            postBatchedNotification(
                type = "TRACKER",
                message = body,
                notifId = NOTIF_ID_TRACKER,
                titleFn = { n -> if (n == 1) title else "⚠️ $n tracker alerts detected" },
                bodyFn = { msgs -> msgs.last() }
            )
        }
    }

    private fun buildTrackerAlertJson(alert: TrackerAlert): String {
        return try {
            val lastSighting = alert.sightings.maxByOrNull { it.timestamp }
            val ssid = alert.sightings.firstNotNullOfOrNull { it.ssid }
            JSONObject().apply {
                put("alertId", alert.alertId)
                put("mac", alert.mac)
                put("manufacturer", alert.manufacturer ?: JSONObject.NULL)
                put("ssid", ssid ?: JSONObject.NULL)
                put("lastRssi", lastSighting?.rssi ?: JSONObject.NULL)
                put("firstSeen", alert.firstSeen)
                put("lastSeen", alert.lastSeen)
                put("stopCount", alert.stopCount)
                put("maxSpreadMeters", alert.maxSpreadMeters)
                // Phase 2B fields (estimatedDistanceMeters, distanceTrend, isBaselineAnomaly,
                // baselineAnomalyType) will be added here when TrackerAlert is extended in Phase 2B.
                put("rssiTrend", JSONArray(alert.rssiTrend))
                put("sightings", JSONArray(alert.sightings.map { s ->
                    val roundedLat = Math.round(s.lat * 1000.0) / 1000.0
                    val roundedLon = Math.round(s.lon * 1000.0) / 1000.0
                    JSONObject().apply {
                        put("timestamp", s.timestamp)
                        put("lat", roundedLat)
                        put("lon", roundedLon)
                        put("rssi", s.rssi)
                    }
                }))
            }.toString()
        } catch (e: Exception) { AppLog.w("ScanService", "Failed to serialize sightings JSON: ${e.message}"); "{}" }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            val provider = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            cachedLocation = locationManager.getLastKnownLocation(provider)
            locationManager.requestLocationUpdates(provider, 15_000L, 10f, locationListener)
        } catch (e: Exception) {
            AppLog.e("ScanService", "Failed to start location updates", e)
        }
    }

    private suspend fun refreshIgnoreList() {
        ignoreSet = ignoreListRepository.getAllAddresses().toSet()
    }

    private fun hasRequiredPermissions(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) return false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false
        }
        return true
    }

    // ── 3.11 Persistent PendingIntent BLE Scanner ─────────────────────────────

    private val persistentScanRequestCode = 0xB1E0

    /**
     * Registers a low-power BLE scan whose results are delivered to
     * [BackgroundScanReceiver] via PendingIntent — Android delivers results
     * even when this service or the entire app process is dead.
     */
    @android.annotation.SuppressLint("MissingPermission") // guarded by hasRequiredPermissions()
    private fun startPersistentPendingIntentScanner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val scanner = leScanner ?: return

        val intent = Intent(this, BackgroundScanReceiver::class.java).apply {
            action = BackgroundScanReceiver.ACTION_BLE_SCAN_RESULT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, persistentScanRequestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        try {
            scanner.startScan(null, settings, pendingIntent)
            AppLog.i("ScanService", "Persistent background BLE scanner registered (PendingIntent)")
        } catch (e: Exception) {
            AppLog.e("ScanService", "Failed to start persistent PendingIntent scanner: ${e.message}", e)
        }
    }

    @android.annotation.SuppressLint("MissingPermission") // guarded
    private fun stopPersistentPendingIntentScanner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val scanner = leScanner ?: return
        val intent = Intent(this, BackgroundScanReceiver::class.java).apply {
            action = BackgroundScanReceiver.ACTION_BLE_SCAN_RESULT
        }
        val pi = PendingIntent.getBroadcast(
            this, persistentScanRequestCode, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        try {
            scanner.stopScan(pi)
            AppLog.i("ScanService", "Persistent PendingIntent BLE scanner stopped")
        } catch (e: Exception) {
            AppLog.w("ScanService", "stopScan(PendingIntent) failed: ${e.message}")
        }
    }

    /**
     * Phase 2A — 2.2: Fired when a device transitions from randomized MAC to global MAC.
     * Notifies the user that a device has revealed its real identity.
     */
    private fun onMacLeakAlert(alert: com.aegisnav.app.correlation.MacLeakAlert) {
        scanScope.launch(scanErrorHandler) {
            val randomCount = alert.previousRandomMacs.size
            val body = if (randomCount > 0) {
                "Device revealed real MAC: ${alert.leakedMac} " +
                    "(was using $randomCount randomized address${if (randomCount > 1) "es" else ""})"
            } else {
                "Device revealed real MAC: ${alert.leakedMac}"
            }

            AppLog.i("ScanService", "MAC leak alert: ${alert.leakedMac} group=${alert.groupId}")

            // Notification suppressed per requirements - logic preserved
            // Emitted as ThreatEvent instead for persistence/UI
            val detailJson = JSONObject().apply {
                put("groupId", alert.groupId)
                put("leakedMac", alert.leakedMac)
                put("previousRandomMacs", JSONArray(alert.previousRandomMacs.toList()))
                put("lat", alert.lat ?: JSONObject.NULL)
                put("lon", alert.lon ?: JSONObject.NULL)
            }.toString()
            threatEventRepository.insert(
                ThreatEvent(
                    type = "MAC_LEAK",
                    mac = alert.leakedMac,
                    timestamp = alert.timestamp,
                    detailJson = detailJson
                )
            )
            // Boost tracker persistence score for leaked group (virtualMac integration)
            // Police already prefers leakedGlobalMac in policeLog
            // trackerDetectionEngine.boostRisk(alert.leakedMac, 1.5) // placeholder boost - not implemented; ThreatEvent + policeLog/virtualMac ties cover integration
        }
    }

    /**
     * Batched notification poster.
     * Accumulates [message] for [type] and posts a summary notification only when the
     * [NOTIFICATION_COOLDOWN_MS] has elapsed since the last post for that type.
     * During the cooldown window events are silently queued; the flush in the WiFi scan
     * loop will drain the queue once the cooldown expires.
     *
     * Must be called from the main thread (wraps notify in withContext if needed).
     * Safe to call from any coroutine; dispatches internally.
     *
     * @param type      One of "CONVOY", "COORDINATED", "TRACKER", "POLICE"
     * @param message   Human-readable alert body for this single event
     * @param notifId   Notification ID to use for this type
     * @param titleFn   Produces a notification title given the pending count
     * @param bodyFn    Produces a notification body given the pending list of messages
     */
    private suspend fun postBatchedNotification(
        type: String,
        message: String,
        notifId: Int,
        titleFn: (Int) -> String,
        bodyFn: (List<String>) -> String
    ) {
        val now = System.currentTimeMillis()
        val list = pendingNotifications.getOrPut(type) { java.util.Collections.synchronizedList(mutableListOf()) }
        list.add(message)
        // Record when the first pending item was added (for periodic flush logic)
        pendingFirstAddedTime.putIfAbsent(type, now)

        val lastPost = lastNotificationTime[type] ?: 0L
        if (now - lastPost < NOTIFICATION_COOLDOWN_MS) {
            // Still within cooldown — accumulate silently
            return
        }

        // Cooldown elapsed — flush the batch
        flushBatchedNotification(type, notifId, titleFn, bodyFn)
    }

    /** Flush a pending batch for [type] immediately (used by both cooldown check and periodic flush). */
    private suspend fun flushBatchedNotification(
        type: String,
        notifId: Int,
        titleFn: (Int) -> String,
        bodyFn: (List<String>) -> String
    ) {
        val list = pendingNotifications[type] ?: return
        val snapshot: List<String>
        synchronized(list) {
            if (list.isEmpty()) return
            snapshot = list.toList()
            list.clear()
        }
        pendingFirstAddedTime.remove(type)
        lastNotificationTime[type] = System.currentTimeMillis()

        val count = snapshot.size
        val title = titleFn(count)
        val body = bodyFn(snapshot)

        withContext(Dispatchers.Main) {
            val pi = PendingIntent.getActivity(
                this@ScanService, type.hashCode(),
                Intent(this@ScanService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            com.aegisnav.app.util.NotificationHelper.notify(
                this@ScanService,
                notifId,
                NotificationCompat.Builder(this@ScanService, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build()
            )
        }
    }

    private fun onConvoyAlert(alert: com.aegisnav.app.intelligence.ConvoyDetector.ConvoyAlert) {
        scanScope.launch(scanErrorHandler) {
            val now = System.currentTimeMillis()

            // 1. Compute group key from sorted MACs
            val groupKey = alert.deviceGroup.sorted().joinToString(",")

            // 2. Filter against ignore list; skip if all MACs ignored
            val filteredMacs = alert.deviceGroup.filter { it !in ignoreSet }
            if (filteredMacs.isEmpty()) {
                AppLog.d("ScanService", "Convoy alert: all devices ignored, skipping")
                return@launch
            }

            val count = filteredMacs.size
            val speedMph = (alert.avgSpeedMps * 2.237).toInt()
            val body = "$count devices moving together at ~${speedMph}mph"
            AppLog.i("ScanService", "Convoy alert: $count devices, bearing=${alert.avgBearingDeg.toInt()}°, speed=${speedMph}mph")
            sentryCaptureThrottled("convoy", 300_000L, "Convoy alert: $count devices, bearing=${alert.avgBearingDeg.toInt()}°, speed=${speedMph}mph")

            // 3. DB dedup: insert only if outside 5-min window for this group
            val lastInsert = convoyGroupLastInsert[groupKey] ?: 0L
            if (now - lastInsert >= CONVOY_DB_DEDUP_MS) {
                val detailJson = JSONObject().apply {
                    put("deviceGroup", JSONArray(filteredMacs.sorted()))
                    put("count", filteredMacs.size)
                    put("avgBearingDeg", alert.avgBearingDeg)
                    put("avgSpeedMps", alert.avgSpeedMps)
                    put("speedMph", speedMph)
                    put("correlatedVectors", alert.correlatedVectors)
                }.toString()
                threatEventRepository.insert(
                    ThreatEvent(
                        type = "CONVOY",
                        mac = groupKey,
                        timestamp = now,
                        detailJson = detailJson
                    )
                )
                convoyGroupLastInsert[groupKey] = now
            }

            // 4. TTS with 10-min cooldown
            if (now - lastConvoyTtsMs >= CONVOY_TTS_COOLDOWN_MS) {
                lastConvoyTtsMs = now
                alertTtsManager.speakIfEnabled(
                    "Coordinated movement detected. $count devices traveling together.",
                    com.aegisnav.app.util.TtsCategory.CONVOY,
                    "convoy_${groupKey.hashCode()}"
                )
            }

            // 5. Batched notification (60s cooldown)
            postBatchedNotification(
                type = "CONVOY",
                message = body,
                notifId = NOTIF_ID_CONVOY,
                titleFn = { n -> if (n == 1) "🚗 Coordinated movement detected" else "🚗 $n convoy alerts detected" },
                bodyFn = { msgs -> msgs.last() }
            )
        }
    }

    private fun onCoordinatedSurveillanceAlert(alert: com.aegisnav.app.intelligence.CoordinatedSurveillanceDetector.CoordinatedAlert) {
        scanScope.launch(scanErrorHandler) {
            val now = System.currentTimeMillis()

            // 1. Compute group key from sorted MACs
            val groupKey = alert.deviceGroup.sorted().joinToString(",")

            // 2. Filter against ignore list; skip if all MACs ignored
            val filteredMacs = alert.deviceGroup.filter { it !in ignoreSet }
            if (filteredMacs.isEmpty()) {
                AppLog.d("ScanService", "Coordinated alert: all devices ignored, skipping")
                return@launch
            }

            val count = filteredMacs.size
            val locations = alert.sharedCells.size
            val body = "$count devices detected at $locations shared locations (correlation: ${String.format("%.0f%%", alert.correlationScore * 100)})"
            AppLog.i("ScanService", "Coordinated surveillance: $count devices, $locations cells, corr=${alert.correlationScore}")
            sentryCaptureThrottled("coordinated", 300_000L, "Coordinated surveillance: $count devices, $locations cells, corr=${alert.correlationScore}")

            // 3. DB dedup: insert only if outside 5-min window for this group
            val lastInsert = coordinatedGroupLastInsert[groupKey] ?: 0L
            if (now - lastInsert >= COORDINATED_DB_DEDUP_MS) {
                val detailJson = JSONObject().apply {
                    put("deviceGroup", JSONArray(filteredMacs.sorted()))
                    put("count", filteredMacs.size)
                    put("sharedCells", JSONArray(alert.sharedCells.toList()))
                    put("correlationScore", alert.correlationScore)
                    put("threatMultiplier", alert.threatMultiplier)
                }.toString()
                threatEventRepository.insert(
                    ThreatEvent(
                        type = "COORDINATED",
                        mac = groupKey,
                        timestamp = now,
                        detailJson = detailJson
                    )
                )
                coordinatedGroupLastInsert[groupKey] = now
            }

            // 4. TTS with 10-min cooldown
            if (now - lastCoordinatedTtsMs >= COORDINATED_TTS_COOLDOWN_MS) {
                lastCoordinatedTtsMs = now
                alertTtsManager.speakIfEnabled(
                    "Coordinated surveillance detected. $count devices at $locations shared locations.",
                    com.aegisnav.app.util.TtsCategory.CONVOY,
                    "coordinated_${groupKey.hashCode()}"
                )
            }

            // 5. Batched notification (60s cooldown)
            postBatchedNotification(
                type = "COORDINATED",
                message = body,
                notifId = NOTIF_ID_COORDINATED,
                titleFn = { n -> if (n == 1) "👥 Coordinated surveillance detected" else "👥 $n coordinated surveillance alerts" },
                bodyFn = { msgs -> msgs.last() }
            )
        }
    }

    /**
     * Haversine distance in metres between two WGS-84 coordinates.
     * Extracted as a service-local helper to avoid cross-module dependencies.
     */
    private fun haversineMetersService(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2).let { it * it }
        return 2 * r * asin(sqrt(a))
    }

    // Finding 1.1: sendAlert() dead code removed — no internal callers found.

    // ── Background lifecycle hooks ─────────────────────────────────────────

    /**
     * Called when the user swipes AegisNav from the recents list.
     * This is the "full kill" signal — stop everything cleanly.
     *
     * Per Option C rules:
     *  - Stop BLE scan, WiFi scan, location updates
     *  - Cancel all WorkManager work (including periodic background scan)
     *  - Remove foreground notification
     *  - Mark AppLifecycleTracker as killed so TTS + popups won't fire
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.i("ScanService", "onTaskRemoved — full shutdown (swipe from recents)")

        // Mark as killed FIRST — prevents any in-flight callbacks from firing
        AppLifecycleTracker.onKill()

        // Cancel ALL WorkManager work — including periodic background BLE scan worker
        try {
            WorkManager.getInstance(this).cancelAllWork()
            AppLog.i("ScanService", "All WorkManager work cancelled")
        } catch (e: Exception) {
            AppLog.w("ScanService", "WorkManager cancel failed: ${e.message}")
        }

        // Stop the foreground service and remove notification
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Stop this service (triggers onDestroy which stops BLE/WiFi/location)
        stopSelf()

        super.onTaskRemoved(rootIntent)
    }
}
