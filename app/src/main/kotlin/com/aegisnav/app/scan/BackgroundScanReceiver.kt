package com.aegisnav.app.scan

import android.Manifest
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.aegisnav.app.data.model.ScanLog
import com.aegisnav.app.tracker.TrackerDetectionEngine
import com.aegisnav.app.util.AppLog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 3.11 — Permanent Background Scanner receiver.
 * 3.16 — Also receives Opportunistic BLE scan results.
 *
 * Receives BLE scan results delivered via [android.app.PendingIntent] from
 * [android.bluetooth.le.BluetoothLeScanner.startScan(List, ScanSettings, PendingIntent)].
 * Android wakes this receiver even when the app process is completely dead.
 *
 * Two scan modes feed results here:
 *  - [ACTION_BLE_SCAN_RESULT]          : persistent low-power background scanner (3.11)
 *  - [ACTION_OPPORTUNISTIC_SCAN_RESULT]: SCAN_MODE_OPPORTUNISTIC scanner (3.16)
 *
 * Results are forwarded to [TrackerDetectionEngine] which handles all detection
 * logic and alert emission.
 *
 * Register in AndroidManifest.xml with both action intent-filters.
 */
@AndroidEntryPoint
class BackgroundScanReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BackgroundScanReceiver"

        const val ACTION_BLE_SCAN_RESULT = "com.aegisnav.app.BLE_SCAN_RESULT"
        const val ACTION_OPPORTUNISTIC_SCAN_RESULT = "com.aegisnav.app.OPPORTUNISTIC_SCAN_RESULT"

        /** Processes a raw [ScanResult] into a [ScanLog] for engine ingestion. */
        fun toScanLog(result: ScanResult, lat: Double? = null, lng: Double? = null): ScanLog {
            val mfgApple = result.scanRecord?.getManufacturerSpecificData(0x004C)
            val mfgFlock = result.scanRecord?.getManufacturerSpecificData(0x09C8)
            val mfgHex: String? = when {
                mfgFlock != null -> "c809" + mfgFlock.joinToString("") { "%02x".format(it) }
                // [SEC-FIX] Prepend "4c00" (LE company ID for Apple 0x004C) so classifyFromHex()
                // correctly identifies AirTag (subtype 0x12) and FindMy (subtype 0x07).
                // Without this prefix the first 4 hex chars were the payload, not the company ID,
                // causing all Apple trackers to be classified as TrackerType.UNKNOWN in background mode.
                mfgApple != null -> "4c00" + mfgApple.joinToString("") { "%02x".format(it) }
                else -> null
            }
            val serviceUuids = result.scanRecord?.serviceUuids
                ?.joinToString(",") { it.toString() }
            val deviceName = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }

            return ScanLog(
                deviceAddress = result.device.address,
                rssi = result.rssi,
                timestamp = System.currentTimeMillis(),
                isTracker = false,
                manufacturerDataHex = mfgHex,
                scanType = "BLE_BACKGROUND",
                lat = lat,
                lng = lng,
                serviceUuids = serviceUuids,
                deviceName = deviceName
            )
        }
    }

    @Inject lateinit var trackerDetectionEngine: TrackerDetectionEngine

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_BLE_SCAN_RESULT && action != ACTION_OPPORTUNISTIC_SCAN_RESULT) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.w(TAG, "BLUETOOTH_SCAN not granted — ignoring background scan result")
            return
        }

        // BluetoothLeScanner.EXTRA_ERROR_CODE = "android.bluetooth.le.extra.ERROR_CODE"
        // BluetoothLeScanner.EXTRA_LIST_OF_SCAN_RESULTS = "android.bluetooth.le.extra.LIST_OF_SCAN_RESULTS"
        // Using string literals for compatibility (field access requires API-level guards on some targets).
        val errorCode = intent.getIntExtra("android.bluetooth.le.extra.ERROR_CODE", 0)
        if (errorCode != 0) {
            AppLog.w(TAG, "PendingIntent scan error code: $errorCode")
            return
        }

        val extraKey = "android.bluetooth.le.extra.LIST_OF_SCAN_RESULTS"
        val results: List<ScanResult>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(extraKey, ScanResult::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(extraKey)
        }

        if (results.isNullOrEmpty()) {
            AppLog.d(TAG, "[$action] received empty result list")
            return
        }

        val mode = if (action == ACTION_OPPORTUNISTIC_SCAN_RESULT) "opportunistic" else "persistent"
        AppLog.d(TAG, "[$mode] received ${results.size} BLE result(s)")

        // Ensure engine coroutine scope is active (process may have just been woken).
        trackerDetectionEngine.ensureScopeActive()

        results.forEach { result ->
            // No GPS in process-dead scenario — lat/lng will be null; engine skips location-
            // dependent checks but still updates sighting history for session continuity.
            val log = toScanLog(result)
            trackerDetectionEngine.onScanResult(log)
        }
    }
}
