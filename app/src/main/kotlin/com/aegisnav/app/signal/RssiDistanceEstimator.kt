package com.aegisnav.app.signal

import com.aegisnav.app.util.AppLog
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * RSSI-to-distance estimator using the log-distance path-loss model:
 *
 *   distance = 10 ^ ((txPower − rssi) / (10 × n))
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
        private const val RSSI_KALMAN_R = 4.0   // measurement noise (~±2 dBm std)
    }

    // Per-MAC Kalman filters — keyed by "${mac}_BLE" or "${mac}_WIFI"
    private val kalmanFilters = ConcurrentHashMap<String, KalmanFilter>()

    // Adaptively-tuned path-loss exponents
    @Volatile private var bleN  = BLE_N_DEFAULT
    @Volatile private var wifiN = WIFI_N_DEFAULT

    /**
     * Estimate distance from a single RSSI measurement.
     *
     * Applies Kalman smoothing to the raw RSSI, then computes path-loss distance.
     *
     * @param mac   Device MAC address (used as Kalman filter key)
     * @param rssi  Raw RSSI in dBm
     * @param isBle True for BLE, false for WiFi
     * @return Estimated distance in metres, clamped to [[MIN_DISTANCE_M], [MAX_DISTANCE_M]]
     */
    fun estimate(mac: String, rssi: Int, isBle: Boolean): Double {
        val key = "${mac}_${if (isBle) "BLE" else "WIFI"}"
        val filter = kalmanFilters.computeIfAbsent(key) {
            KalmanFilter(RSSI_KALMAN_Q, RSSI_KALMAN_R, rssi.toDouble())
        }
        val smoothedRssi = filter.update(rssi.toDouble())
        val (txPower, n) = if (isBle) (BLE_TX_DEFAULT to bleN) else (WIFI_TX_DEFAULT to wifiN)
        val raw = 10.0.pow((txPower - smoothedRssi) / (10.0 * n))
        return raw.coerceIn(MIN_DISTANCE_M, MAX_DISTANCE_M)
    }

    /**
     * GPS-calibrated auto-tuning.
     *
     * Called when the true distance between the user and the detected device is known
     * (e.g. from GPS triangulation or manual measurement).  Nudges the path-loss
     * exponent toward the value that would have produced [trueDistanceMeters] for
     * the observed [rssi].
     *
     * @param mac               Device MAC address
     * @param rssi              RSSI measured at the calibration point (dBm)
     * @param isBle             True for BLE, false for WiFi
     * @param trueDistanceMeters Known ground-truth distance in metres
     */
    fun calibrate(mac: String, rssi: Int, isBle: Boolean, trueDistanceMeters: Double) {
        if (trueDistanceMeters < 1.0 || trueDistanceMeters > MAX_DISTANCE_M) return
        val (txPower, _) = if (isBle) (BLE_TX_DEFAULT to bleN) else (WIFI_TX_DEFAULT to wifiN)
        val key = "${mac}_${if (isBle) "BLE" else "WIFI"}"
        val smoothedRssi = kalmanFilters[key]?.estimate ?: rssi.toDouble()

        // Solve: distance = 10^((txPower - rssi) / (10 * n))
        //        log10(distance) = (txPower - rssi) / (10 * n)
        //        n = (txPower - rssi) / (10 * log10(distance))
        val denominator = 10.0 * log10(trueDistanceMeters)
        if (abs(denominator) < 0.001) return

        val observedN = (txPower - smoothedRssi) / denominator
        val clampedN  = observedN.coerceIn(1.5, 5.0)  // physically reasonable bounds

        if (isBle) {
            bleN = bleN * (1 - TUNING_ALPHA) + clampedN * TUNING_ALPHA
            AppLog.d(TAG, "BLE n→${"%.3f".format(bleN)} (obs=${"%.3f".format(observedN)}, d=${"%.1f".format(trueDistanceMeters)}m, rssi=$rssi)")
        } else {
            wifiN = wifiN * (1 - TUNING_ALPHA) + clampedN * TUNING_ALPHA
            AppLog.d(TAG, "WiFi n→${"%.3f".format(wifiN)} (obs=${"%.3f".format(observedN)}, d=${"%.1f".format(trueDistanceMeters)}m, rssi=$rssi)")
        }
    }

    /**
     * Derive the signal trend ("CLOSING" / "STABLE" / "RECEDING") from a list of
     * sequential RSSI values.  Compares the average of the first 3 samples against
     * the average of the last 3 samples.
     *
     * Higher RSSI = closer to device.  Increasing RSSI over time = CLOSING.
     */
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

    /** Current smoothed RSSI estimate for a device (null if not yet observed). */
    fun smoothedRssi(mac: String, isBle: Boolean): Double? {
        val key = "${mac}_${if (isBle) "BLE" else "WIFI"}"
        val filter = kalmanFilters[key]
        return if (filter?.initialized == true) filter.estimate else null
    }

    /** Clear cached Kalman state for a single device MAC (both BLE and WiFi keys). */
    fun clearDevice(mac: String) {
        kalmanFilters.keys.removeIf { it.startsWith("${mac}_") }
    }

    /** Clear all per-device Kalman state and reset tuned parameters. */
    fun clearAll() {
        kalmanFilters.clear()
        bleN  = BLE_N_DEFAULT
        wifiN = WIFI_N_DEFAULT
    }
}
