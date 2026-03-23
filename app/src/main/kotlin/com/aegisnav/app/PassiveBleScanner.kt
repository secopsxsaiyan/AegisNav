package com.aegisnav.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

data class BleResult(
    val address: String,
    val name: String?,
    val rssi: Int,
    val manufacturerData: String?
)

class PassiveBleScanner(private val context: Context) {
    @android.annotation.SuppressLint("MissingPermission") // Guarded: BLUETOOTH_SCAN checked before startScan
    fun scan(onResult: (BleResult) -> Unit, onDone: () -> Unit) {
        // Check BLE scan permission before accessing scanner (required on API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) { onDone(); return }
        }

        // Use BluetoothManager (non-deprecated) instead of BluetoothAdapter.getDefaultAdapter()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: run { onDone(); return }
        val scanner = adapter.bluetoothLeScanner ?: run { onDone(); return }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val mfData = result.scanRecord?.bytes?.let { bytes ->
                    bytes.joinToString("") { "%02X".format(it) }
                }
                onResult(BleResult(
                    address = result.device.address,
                    name = result.device.name,
                    rssi = result.rssi,
                    manufacturerData = mfData
                ))
            }
        }

        scanner.startScan(callback)

        Handler(Looper.getMainLooper()).postDelayed({
            try { scanner.stopScan(callback) } catch (e: Exception) { /* ignore */ }
            onDone()
        }, 60_000L)
    }
}
