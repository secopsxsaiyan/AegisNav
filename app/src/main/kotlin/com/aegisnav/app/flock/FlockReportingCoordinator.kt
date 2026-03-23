package com.aegisnav.app.flock

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.aegisnav.app.security.SecureDataStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.aegisnav.app.util.AppLog
import androidx.core.app.NotificationCompat
import com.aegisnav.app.MainActivity
import com.aegisnav.app.ScanService
import com.aegisnav.app.data.model.Report
import com.aegisnav.app.data.repository.ReportsRepository
import com.aegisnav.app.di.ApplicationScope
import com.aegisnav.app.p2p.P2PManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FlockReportingCoordinator - wires [FlockDetector] sighting events to:
 *
 * 1. Automatic local DB report creation (confidence ≥ 0.75, fully offline)
 * 2. System notification → tapping opens FlockSightingDetailSheet via MainActivity
 * 3. P2P broadcast if user has P2P sharing enabled (relay URLs configured)
 *
 * This coordinator does NOT auto-broadcast to P2P without user consent;
 * P2P is gated on whether the user has configured relay URLs (isP2PEnabled()).
 */
@Singleton
class FlockReportingCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val flockDetector: FlockDetector,
    private val reportsRepository: ReportsRepository,
    private val p2pManager: P2PManager,
    @ApplicationScope private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "FlockReportingCoordinator"

        /** Confidence threshold for auto-creating a report + showing notification */
        const val AUTO_REPORT_THRESHOLD = 0.75f

        private const val NOTIF_ID_BASE = 9000
    }

    // Cached offline_mode flag — avoids blocking reads in handleSighting
    private val offlineMode = SecureDataStore.get(context, "app_prefs")
        .data
        .map { prefs -> prefs[booleanPreferencesKey("offline_mode")] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    private var collectionJob: Job? = null

    /**
     * Start collecting FlockDetector sightings and react accordingly.
     * Call from ScanService.onStartCommand(). Guards against double-start:
     * cancels any existing collection job before launching a new one.
     */
    fun start() {
        collectionJob?.cancel()
        collectionJob = scope.launch {
            flockDetector.sightings
                .filterNotNull()
                .collect { sighting ->
                    handleSighting(sighting)
                }
        }
    }

    /**
     * Stop collecting sightings. Call from ScanService.onDestroy().
     */
    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
    }

    private suspend fun handleSighting(sighting: FlockSighting) {
        AppLog.i(TAG, "Handling FlockSighting ${sighting.id} confidence=${sighting.confidence}")

        // Auto-report to local DB if confidence >= 0.75
        if (sighting.confidence >= AUTO_REPORT_THRESHOLD) {
            try {
                reportsRepository.insert(
                    Report(
                        type = "ALPR",
                        subtype = "Flock Safety",
                        latitude = sighting.lat,
                        longitude = sighting.lon,
                        description = "Flock Safety node detected automatically",
                        threatLevel = "MEDIUM"
                    )
                )
                AppLog.i(TAG, "Auto-report created for sighting ${sighting.id}")
            } catch (e: Exception) {
                AppLog.e(TAG, "Failed to auto-create report", e)
            }

            // Post system notification - tap opens MainActivity with sighting intent
            postNotification(sighting)
        }

        // P2P broadcast - only if offline mode is OFF and user has relay URLs configured
        val isOffline = offlineMode.value
        if (!isOffline && p2pManager.isP2PEnabled()) {
            p2pManager.broadcastFlockSighting(sighting)
        }
    }

    private fun postNotification(sighting: FlockSighting) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                // Audit fix 4.3: pass only sighting ID, not raw GPS coords in PendingIntent
                putExtra("flock_sighting_id", sighting.id)
            }
            val pi = PendingIntent.getActivity(
                context,
                NOTIF_ID_BASE + sighting.id.hashCode().and(0xFFFF),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            com.aegisnav.app.util.NotificationHelper.notify(
                context,
                NOTIF_ID_BASE + sighting.id.hashCode().and(0xFFFF),
                NotificationCompat.Builder(context, ScanService.CHANNEL_ID)
                    .setContentTitle("Flock Safety camera detected nearby")
                    .setContentText(
                        "ALPR node at ${String.format("%.4f, %.4f", sighting.lat, sighting.lon)} " +
                        "(${(sighting.confidence * 100).toInt()}% confidence)"
                    )
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to post notification", e)
        }
    }
}
