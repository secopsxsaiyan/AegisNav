package com.aegisnav.app

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.aegisnav.app.data.model.ScanLog
import javax.inject.Inject
import javax.inject.Singleton

data class WifiScanResult(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val timestamp: Long
)

/** Lightweight lat/lon holder - replaces osmdroid GeoPoint */
data class LatLon(val lat: Double, val lon: Double)

@Singleton
class ScanState @Inject constructor() {
    val isScanning = mutableStateOf(false)
    val pulses = mutableStateListOf<Pair<LatLon, Long>>()
    val wifiResults = mutableStateListOf<WifiScanResult>()

    // All unique devices seen this session keyed by MAC - never cleared until process restart.
    // Used by the first-launch ignore wizard (DB scan_logs table is never written to by ScanService).
    val scannedDevices = mutableStateMapOf<String, ScanLog>()

    fun setScanning(value: Boolean) {
        isScanning.value = value
    }

    fun addPulse(lat: Double, lng: Double) {
        pulses.add(Pair(LatLon(lat, lng), System.currentTimeMillis()))
    }

    fun expirePulses(now: Long) {
        pulses.removeAll { now - it.second > 30000L }
    }
}
