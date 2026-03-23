package com.aegisnav.app.signal

/**
 * Generic 1D linear Kalman filter for scalar state estimation.
 *
 * Suitable for smoothing noisy scalar signals such as RSSI (dBm) or
 * GPS-coordinate components. Uses a constant-velocity model where
 * the state is assumed to change slowly between samples.
 *
 * @param q Process noise variance — how much the true value can change per step.
 *          Lower = smoother output but slower to track real changes.
 * @param r Measurement noise variance — assumed sensor noise.
 * @param initialEstimate Starting state guess; overwritten by first [update] call.
 * @param initialError    Starting error covariance.
 */
class KalmanFilter(
    private val q: Double = 1.0,
    private val r: Double = 4.0,
    initialEstimate: Double = 0.0,
    initialError: Double = 1.0
) {
    private var x: Double = initialEstimate   // state estimate
    private var p: Double = initialError      // error covariance

    /** True once at least one measurement has been processed. */
    var initialized: Boolean = false
        private set

    /** Current smoothed state estimate. Only valid after the first [update] call. */
    val estimate: Double get() = x

    /**
     * Process a new measurement [z] and return the updated state estimate.
     */
    fun update(z: Double): Double {
        if (!initialized) {
            x = z
            initialized = true
            return x
        }
        // Prediction step — constant model: x_pred = x, p_pred = p + q
        val pPred = p + q
        // Update step
        val k = pPred / (pPred + r)   // Kalman gain
        x += k * (z - x)
        p = (1.0 - k) * pPred
        return x
    }

    /**
     * Reset the filter to a new initial state.
     * Call when the tracked signal is expected to jump discontinuously
     * (e.g. device re-acquired after a long absence).
     */
    fun reset(initialEstimate: Double = 0.0) {
        x = initialEstimate
        p = 1.0
        initialized = false
    }
}
