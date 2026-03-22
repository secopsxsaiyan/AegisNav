package com.aegisnav.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.aegisnav.app.util.AppLog
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides a continuous [Flow] of [Location] updates using Android's native [LocationManager].
 * Replaces the former FusedLocationProviderClient / play-services-location dependency.
 */
@Singleton
class LocationRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /**
     * Returns a [Flow] that emits [Location] updates.
     * Emits the last-known location immediately (if available), then continues with live updates.
     * Stops updates when the flow is cancelled.
     */
    fun locationFlow(
        minTimeMs: Long = 10_000L,
        minDistanceM: Float = 5f
    ): Flow<Location> = callbackFlow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            close(SecurityException("ACCESS_FINE_LOCATION permission not granted"))
            return@callbackFlow
        }

        val provider = bestProvider()
        // Emit last-known location immediately so UI has a starting point
        locationManager.getLastKnownLocation(provider)?.let { trySend(it) }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { trySend(location) }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            locationManager.requestLocationUpdates(provider, minTimeMs, minDistanceM, listener)
        } catch (e: Exception) {
            AppLog.e("LocationRepository", "Failed to request location updates", e)
            close(e)
            return@callbackFlow
        }

        awaitClose {
            try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
        }
    }.conflate()

    /** Returns the last known location without registering ongoing updates. */
    fun getLastLocation(): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return null
        return try {
            locationManager.getLastKnownLocation(bestProvider())
        } catch (e: Exception) {
            AppLog.w("LocationRepository", "getLastLocation failed: ${e.message}")
            null
        }
    }

    private fun bestProvider(): String =
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            LocationManager.GPS_PROVIDER
        else LocationManager.NETWORK_PROVIDER
}
