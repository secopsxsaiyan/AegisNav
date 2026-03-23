package com.aegisnav.app.scan

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.aegisnav.app.util.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 3.16 — Opportunistic BLE Scanning.
 *
 * Registers a [ScanSettings.SCAN_MODE_OPPORTUNISTIC] scan with a [PendingIntent].
 * The OS piggybacks on other applications' BLE scans to deliver results — this has
 * zero additional battery cost beyond what other apps already consume.
 *
 * Results are delivered to [BackgroundScanReceiver] via
 * [BackgroundScanReceiver.ACTION_OPPORTUNISTIC_SCAN_RESULT].
 *
 * Combines with the persistent PendingIntent scanner (3.11) registered in
 * [ScanService] for maximum passive background coverage:
 *  - Opportunistic  : zero-cost piggyback on other apps' scans
 *  - Persistent (3.11): own low-power scan, always active when process is dead
 *
 * Call [start] once at app startup (from ScanService or Application) and
 * [stop] when the user disables scanning entirely.
 */
@Singleton
class OpportunisticScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "OpportunisticScanner"
        private const val REQUEST_CODE = 0xBEAD  // arbitrary stable request code
    }

    /**
     * Starts the opportunistic scan. Safe to call multiple times (PendingIntent
     * is updated via FLAG_UPDATE_CURRENT; the OS deduplicates the scan).
     *
     * Must be called with BLUETOOTH_SCAN permission granted.
     */
    @android.annotation.SuppressLint("MissingPermission") // guarded below
    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.w(TAG, "BLUETOOTH_SCAN not granted — opportunistic scan not started")
            return
        }

        val scanner = bluetoothLeScanner() ?: run {
            AppLog.w(TAG, "BluetoothLeScanner unavailable — opportunistic scan skipped")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_OPPORTUNISTIC)
            .build()

        try {
            scanner.startScan(null, settings, buildPendingIntent())
            AppLog.i(TAG, "Opportunistic BLE scan started (piggyback mode, zero battery cost)")
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to start opportunistic scan: ${e.message}", e)
        }
    }

    /**
     * Stops the opportunistic scan. No-op if the scan was never started or the
     * PendingIntent has already been cancelled.
     */
    @android.annotation.SuppressLint("MissingPermission") // guarded below
    fun stop() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val scanner = bluetoothLeScanner() ?: return

        // FLAG_NO_CREATE: returns null if the PendingIntent doesn't exist (already cancelled).
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            buildIntent(),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return

        try {
            scanner.stopScan(pi)
            AppLog.i(TAG, "Opportunistic BLE scan stopped")
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to stop opportunistic scan: ${e.message}", e)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun bluetoothLeScanner() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.bluetoothLeScanner

    private fun buildIntent() = Intent(context, BackgroundScanReceiver::class.java).apply {
        action = BackgroundScanReceiver.ACTION_OPPORTUNISTIC_SCAN_RESULT
    }

    private fun buildPendingIntent() = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        buildIntent(),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
