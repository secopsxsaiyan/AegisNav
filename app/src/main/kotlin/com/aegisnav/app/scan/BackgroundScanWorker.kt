package com.aegisnav.app.scan

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aegisnav.app.MainActivity
import com.aegisnav.app.R
import com.aegisnav.app.ScanService
import com.aegisnav.app.data.model.ScanLog
import com.aegisnav.app.tracker.TrackerAlert
import com.aegisnav.app.tracker.TrackerDetectionEngine
import com.aegisnav.app.AppLifecycleTracker
import com.aegisnav.app.util.AppLog
import com.aegisnav.app.util.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 3.5 — Background Scanning via WorkManager.
 *
 * Runs a 10-second BLE scan every 15 minutes (Android PeriodicWorkRequest minimum)
 * even when the app is not in the foreground. Results are fed to the
 * [TrackerDetectionEngine] singleton; any alert emitted during the scan window
 * triggers a user notification.
 *
 * Scan parameters:
 *  - Mode    : SCAN_MODE_LOW_POWER (battery-optimised)
 *  - Window  : [SCAN_WINDOW_MS] = 10 seconds
 *  - Battery : constrained — will not run if battery is critically low
 *
 * The worker is scheduled once at app startup via [schedule]. Subsequent
 * scheduling calls use [ExistingPeriodicWorkPolicy.KEEP] so the period is not
 * reset on every app launch.
 */
@HiltWorker
class BackgroundScanWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val trackerDetectionEngine: TrackerDetectionEngine,
    private val scanOrchestrator: ScanOrchestrator
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "BackgroundScanWorker"

        const val WORK_NAME = "aegisnav_background_ble_scan"

        /** Android PeriodicWorkRequest minimum interval. */
        const val INTERVAL_MINUTES = 15L

        /** BLE scan duration per work execution. */
        const val SCAN_WINDOW_MS = 10_000L

        private const val NOTIF_ID_BG_TRACKER = 8000

        /**
         * Enqueues the periodic background scan worker with KEEP policy.
         * Safe to call on every app launch.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                // Don't burn the last battery charge on background scanning
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<BackgroundScanWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            AppLog.i(TAG, "Background BLE scan worker scheduled (every ${INTERVAL_MINUTES}min)")
        }
    }

    @android.annotation.SuppressLint("MissingPermission") // guarded via hasBleScanPermission()
    override suspend fun doWork(): Result {
        // Respect kill state — user swiped from recents, stop everything
        if (AppLifecycleTracker.isKilled) {
            AppLog.i(TAG, "App killed (swipe from recents) — skipping background scan")
            return Result.success()
        }

        if (!hasBleScanPermission()) {
            AppLog.w(TAG, "BLUETOOTH_SCAN not granted — skipping background scan")
            return Result.success()
        }

        val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            AppLog.w(TAG, "Bluetooth unavailable — skipping background scan")
            return Result.success()
        }

        val scanner = btAdapter.bluetoothLeScanner ?: run {
            AppLog.w(TAG, "BluetoothLeScanner null — skipping background scan")
            return Result.success()
        }

        AppLog.i(TAG, "Background BLE scan starting (${SCAN_WINDOW_MS}ms window, LOW_POWER mode)")

        // Ensure engine scope is alive — process may have been dormant
        trackerDetectionEngine.ensureScopeActive()
        trackerDetectionEngine.onScanSessionStart()

        // Structured concurrency: collector and scan share the same coroutineScope;
        // the collector is cancelled in the finally block, preventing a scope leak.
        val detectedAlerts = mutableListOf<TrackerAlert>()
        coroutineScope {
            val alertCollectorJob = launch {
                trackerDetectionEngine.alerts.collect { alert ->
                    detectedAlerts.add(alert)
                }
            }

            try {
                // Try to get last known location for context
                val (lat, lng) = getBestLastLocation()

                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .build()

                val scanCallback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        val log = BackgroundScanReceiver.toScanLog(result, lat, lng)
                        trackerDetectionEngine.onScanResult(log)
                    }

                    override fun onScanFailed(errorCode: Int) {
                        AppLog.w(TAG, "Background BLE scan failed with error code $errorCode")
                    }
                }

                var scanStarted = false
                scanOrchestrator.requestScan(ScanOrchestrator.ScanPriority.LOW, "background_worker") {
                    scanner.startScan(null, settings, scanCallback)
                    scanStarted = true
                }

                if (scanStarted) {
                    delay(SCAN_WINDOW_MS)
                    try {
                        scanner.stopScan(scanCallback)
                    } catch (e: Exception) {
                        AppLog.w(TAG, "stopScan failed (may already be stopped): ${e.message}")
                    }
                } else {
                    AppLog.d(TAG, "Scan was queued by orchestrator — will run when budget allows")
                    // Still wait to give the orchestrator time to retry
                    delay(SCAN_WINDOW_MS * 3)
                }
            } finally {
                alertCollectorJob.cancel()
            }
        }

        // Post notification for every alert detected during this scan window
        detectedAlerts.forEach { alert ->
            AppLog.i(TAG, "Background scan alert: mac=${alert.mac} spread=${alert.maxSpreadMeters.toInt()}m")
            postTrackerNotification(alert)
        }

        AppLog.i(TAG, "Background BLE scan complete. ${detectedAlerts.size} alert(s) detected.")
        return Result.success()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun hasBleScanPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("MissingPermission") // guarded by ACCESS_FINE_LOCATION check
    private fun getBestLastLocation(): Pair<Double?, Double?> {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null to null

        return try {
            val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            val location = providers.firstNotNullOfOrNull { provider ->
                runCatching { lm?.getLastKnownLocation(provider) }.getOrNull()
            }
            location?.latitude to location?.longitude
        } catch (e: Exception) {
            null to null
        }
    }

    private suspend fun postTrackerNotification(alert: TrackerAlert) {
        val durationMin = (alert.lastSeen - alert.firstSeen) / 60_000L
        val deviceLabel = alert.manufacturer ?: "Unknown device"

        val tapIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // Audit fix 4.4: pass alertId (not raw MAC) in PendingIntent extra
            // TODO(phase-refactor): receiver should look up TrackerAlert from DB/cache by alertId
            putExtra("tracker_alert_id", alert.alertId)
        }
        val tapPi = PendingIntent.getActivity(
            appContext,
            alert.mac.hashCode().and(0xFFFF),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, ScanService.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("⚠️ Tracker detected while app was closed")
            .setContentText("A $deviceLabel has been near you for ${durationMin}min across ${alert.stopCount} stops.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("A $deviceLabel (${alert.mac}) has been near you for ${durationMin} minutes " +
                        "across ${alert.stopCount} stops, spread across ${alert.maxSpreadMeters.toInt()}m. " +
                        "Tap to review."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapPi)
            .build()

        withContext(Dispatchers.Main) {
            NotificationHelper.notify(
                appContext,
                NOTIF_ID_BG_TRACKER + alert.mac.hashCode().and(0xFFFF),
                notification
            )
        }
    }
}
