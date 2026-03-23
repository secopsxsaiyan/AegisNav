package com.aegisnav.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

class PassiveWifiScanner(private val context: Context) {
    @android.annotation.SuppressLint("MissingPermission") // Guarded: ACCESS_FINE_LOCATION checked before startScan
    fun scan(onResults: (List<Pair<String, String>>) -> Unit) { // SSID, BSSID
        // Check location permission (required for WiFi scan results on all Android versions)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) { onResults(emptyList()); return }

        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val handler = Handler(Looper.getMainLooper())
        var receiverRegistered = false

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (!receiverRegistered) return
                receiverRegistered = false
                handler.removeCallbacksAndMessages(null)
                try { context.unregisterReceiver(this) } catch (e: Exception) { /* ignore */ }
                @Suppress("DEPRECATION")
                val results = wm.scanResults.map { it.SSID to it.BSSID }
                onResults(results)
            }
        }

        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true

        // Hint to OS; may be throttled/ignored on Android 9+
        @Suppress("DEPRECATION")
        wm.startScan()

        // 10-second timeout fallback: unregister and return cached results
        handler.postDelayed({
            if (!receiverRegistered) return@postDelayed
            receiverRegistered = false
            try { context.unregisterReceiver(receiver) } catch (e: Exception) { /* ignore */ }
            @Suppress("DEPRECATION")
            val results = wm.scanResults.map { it.SSID to it.BSSID }
            onResults(results)
        }, 10_000L)
    }
}
