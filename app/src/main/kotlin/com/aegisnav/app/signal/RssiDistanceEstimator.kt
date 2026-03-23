package com.aegisnav.app.signal

import com.aegisnav.app.util.AppLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * RSSI-to-distance estimator using the log-distance path-loss model:
 *
 *   distance = 10 ^ ((txPower - rssi) / (10 x n))
 *
 * Separate path-loss exponent (n) and reference Tx-Power values are maintained
 * for BLE and WiFi.  Each device MAC gets its own [KalmanFilter] for RSSI
 * smoothing.  When a ground-truth GPS distance is available,
 * [calibrate] adjusts the path-loss exponent toward the best-fit value.
 *
 * Default parameters (literature values for indoor/urban environments):
 *   - BLE:  n = 2.5, txPower = -59 dBm  (AirTag-class transmit power at 1 m)
 *   - WiFi: n = 3.0, txPower = -30 dBm
 */
@Singleton
class RssiDistanceEstimator @Inject constructor() {

    companion object {
        private const val TAG = "RssiDistanceEstimator"

        // BLE defaults
        private const val BLE_N_DEFAULT   = 2.5
        private const val BLE_TX_DEFAULT  = -59.0

        // WiFi defaults
        private const val WIFI_N_DEFAULT  = 3.0
        private const val WIFI_TX_DEFAULT = -30.0

        // Physical distance bounds
        const val MIN_DISTANCE_M = 0.1
        const val MAX_DISTANCE_M = 200.0

        // GPS auto-tuning learning rate
        private const val TUNING_ALPHA = 0.1

        // Kalman parameters for RSSI smoothing (dBm)
        private const val RSSI_KALMAN_Q = 1.0   // process noise
        private const val RSSI_KALMAN_R = 4.0   // measurement noise (~+-2 dBm std)
    }

    // Per-MAC Kalman filters -- keyed by "${mac}_BLE" or "${mac}_WIFI"
    private val kalmanFilters = ConcurrentHashMap<String, KalmanFilter>()

    // Adaptively-tuned path-loss exponents (AtomicReference for safe read-modify-write)
    private val bleN  = AtomicReference(BLE_N_DEFAULT)
    private val wifiN = AtomicReference(WIFI_N_DEFAULT)

    fun estimate(mac: String, rssi: Int, isBle: Boolean): Double {
        val key = "${mac}_${if (isBle) "BLE" else "WIFI"}"
        val filter = kalmanFilters.computeIfAbsent(key) {
            KalmanFilter(RSSI_KALMAN_Q, RSSI_KALMAN_R, rssi.toDouble())
        }
        val smoothedRssi = filter.update(rssi.toDouble())
        val (txPower, n) = if (isBle) (BLE_TX_DEFAULT to bleN.get()) else (WIFI_TX_DEFAULT to wifiN.get())
        val raw = 10.0.pow((txPower - smoothedRssi) / (10.0 * n))
        return raw.coerceIn(MIN_DISTANCE_M, MAX_DISTANCE_M)
    }

    fun calibrate(mac: String, rssi: Int, isBle: Boolean, trueDistanceMeters: Double) {
        if (trueDistanceMeters < 1.0 || trueDistanceMeters > MAX_DISTANCE_M) return
        val txPower = if (isBle) BLE_TX_DEFAULT else WIFI_TX_DEFAULT
        val key = "${mac}_${if (isBle) "BLE" else "WIFI"}"
        val smoothedRssi = kalmanFilters[key]?.estimate ?: rssi.toDouble()

        val denominator = 10.0 * log10(trueDistanceMeters)
        if (abs(denominator) < 0.001) return

        val observedN = (txPower - smoothedRssi) / denominator
        val clampedN  = observedN.coerceIn(1.5, 5.0)

        val ref = if (isBle) bleN else wifiN
        while (true) {
            val old = ref.get()
            val new = old * (1 - TUNING_ALPHA) + clampedN * TUNING_ALPHA
            if (ref.compareAndSet(old, new)) break
        }

        val label = if (isBle) "BLE" else "WiFi"
        val updatedN = ref.get()
        AppLog.d(TAG, "$label n->${"%.3f".format(updatedN)} (obs=${"%.3f".format(observedN)}, d=${"%.1f".format(trueDistanceMeters)}m, rssi=$rssi)")
    }

    fun distanceTrend(rssiTrend: List<Int>): String {
        if (rssiTrend.size < 3) return "STABLE"
        val firstAvg = rssiTrend.take(3).average()
        val lastAvg  = rssiTrend.takeLast(3).average()
        val delta    = lastAvg - firstAvg
        return when {
            delta >  3.0 -> "CLOSING"
            delta < -3.0 -> "RECEDING"
            else         -> "STABLE"
        }
    }

    fun smoothedRssi(mac: String, isBle: Boolean): Double? {
        val key = "${mac}_${if (isBle) "BLE" else "WIFI"}"
        val filter = kalmanFilters[key]
        return if (filter?.initialized == true) filter.estimate else null
    }

    fun clearDevice(mac: String) {
        kalmanFilters.keys.removeIf { it.startsWith("${mac}_") }
    }

    fun clearAll() {
        kalmanFilters.clear()
        bleN.set(BLE_N_DEFAULT)
        wifiN.set(WIFI_N_DEFAULT)
    }
}
