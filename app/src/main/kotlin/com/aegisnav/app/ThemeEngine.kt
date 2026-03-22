package com.aegisnav.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import com.aegisnav.app.util.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.ZonedDateTime

class ThemeEngine(private val context: Context) {

    companion object {
        private const val TAG = "ThemeEngine"
        private const val LUX_DARK_THRESHOLD = 50f
        private const val LUX_LIGHT_THRESHOLD = 300f
        private const val DEBOUNCE_MS = 2000L
        private const val EVAL_INTERVAL_MS = 60_000L
    }

    private val _isDark = MutableStateFlow(true)
    val isDark: StateFlow<Boolean> = _isDark

    private var currentMode: ThemeMode = ThemeMode.AUTO
    private var currentLocation: Location? = null
    private var currentLux: Float? = null
    private var luxWasDark: Boolean = false // start optimistic (light)

    // Recreated on each start() so stop() → start() works correctly
    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var evalJob: Job? = null
    private var debounceJob: Job? = null

    private val sensorManager: SensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    private val luxSensor: Sensor? by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_LIGHT) {
                val lux = event.values[0]
                currentLux = lux
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(DEBOUNCE_MS)
                    evaluate()
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    fun start(mode: ThemeMode, location: Location?) {
        // Recreate scope in case stop() was called before (cancels the old scope)
        if (scope.coroutineContext[Job]?.isActive == false) {
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        }
        currentMode = mode
        currentLocation = location
        luxSensor?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        evalJob?.cancel()
        evalJob = scope.launch {
            while (isActive) {
                evaluate()
                delay(EVAL_INTERVAL_MS)
            }
        }
        AppLog.d(TAG, "ThemeEngine started, mode=$mode")
    }

    fun stop() {
        try { sensorManager.unregisterListener(sensorListener) } catch (_: Exception) {}
        evalJob?.cancel()
        debounceJob?.cancel()
        scope.cancel()
        AppLog.d(TAG, "ThemeEngine stopped")
    }

    fun setMode(mode: ThemeMode) {
        currentMode = mode
        scope.launch { evaluate() }
    }

    fun updateLocation(location: Location) {
        currentLocation = location
        scope.launch { evaluate() }
    }

    private fun evaluate() {
        val dark = when (currentMode) {
            ThemeMode.DARK  -> true
            ThemeMode.LIGHT -> false
            ThemeMode.AUTO  -> computeAuto()
        }
        AppLog.d(TAG, "evaluate: mode=$currentMode dark=$dark lux=$currentLux")
        _isDark.value = dark
    }

    private fun computeAuto(): Boolean {
        val loc = currentLocation
        val lux = currentLux

        val nighttime = if (loc != null) {
            SunriseSunset.isNighttime(loc.latitude, loc.longitude)
        } else {
            // No location: fall back to hour-of-day
            val hour = ZonedDateTime.now().hour
            hour < 7 || hour >= 20
        }

        return if (nighttime) {
            true
        } else {
            // Daytime - use lux with hysteresis
            if (lux == null) {
                // No sensor data: use time-of-day fallback
                val hour = ZonedDateTime.now().hour
                hour < 7 || hour >= 20
            } else {
                // Hysteresis: once dark, need >LUX_LIGHT_THRESHOLD to go light
                luxWasDark = if (luxWasDark) {
                    lux <= LUX_LIGHT_THRESHOLD
                } else {
                    lux <= LUX_DARK_THRESHOLD
                }
                luxWasDark
            }
        }
    }
}
