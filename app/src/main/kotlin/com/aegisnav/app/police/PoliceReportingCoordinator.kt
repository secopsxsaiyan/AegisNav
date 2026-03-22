package com.aegisnav.app.police

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.aegisnav.app.util.AppLog
import androidx.core.app.NotificationCompat
import com.aegisnav.app.MainActivity
import com.aegisnav.app.ScanService
import com.aegisnav.app.p2p.P2PManager
import com.aegisnav.app.di.ApplicationScope
import com.aegisnav.app.util.AlertTtsManager
import com.aegisnav.app.util.TtsCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PoliceReportingCoordinator - wires [PoliceDetector] sightings to:
 *
 * 1. TTS alert with per-category 5-min cooldown
 * 2. System notification → tap opens MainActivity
 * 3. OfficerCorrelationEngine → correlates MACs to persistent officer units
 *
 * NOTE: Police sightings do NOT insert ThreatEvents or Reports.
 * NOTE: P2P broadcast is commented out — reserved for future feature.
 */
@Singleton
class PoliceReportingCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val policeDetector: PoliceDetector,
    private val p2pManager: P2PManager,
    private val alertTtsManager: AlertTtsManager,
    private val officerCorrelationEngine: OfficerCorrelationEngine,
    private val policeSightingDao: PoliceSightingDao,
    @ApplicationScope private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "PoliceReportingCoordinator"
        const val AUTO_REPORT_THRESHOLD = 0.5f
        private const val NOTIF_ID_BASE = 9500
        private const val TTS_COOLDOWN_MS = 5 * 60_000L  // 5 minutes per category
    }

    // Cooldown: map of detectionCategory → last TTS timestamp (ms)
    private val lastPoliceTtsMs = mutableMapOf<String, Long>()

    fun start() {
        scope.launch {
            policeDetector.sightings
                .filterNotNull()
                .collect { sighting -> handleSighting(sighting) }
        }
    }

    private suspend fun handleSighting(sighting: PoliceSighting) {
        AppLog.i(TAG, "Handling PoliceSighting ${sighting.id} " +
            "category=${sighting.detectionCategory} confidence=${sighting.confidence}")

        if (sighting.confidence < AUTO_REPORT_THRESHOLD) return

        // TTS alert with 5-minute per-category cooldown
        val now = System.currentTimeMillis()
        val category = sighting.detectionCategory
        val lastTts = lastPoliceTtsMs[category] ?: 0L
        if (now - lastTts >= TTS_COOLDOWN_MS) {
            lastPoliceTtsMs[category] = now
            alertTtsManager.speakIfEnabled("Possible police activity", TtsCategory.POLICE, "police_alert_$category")
            AppLog.i(TAG, "TTS: Possible police activity (category=$category)")
        }

        // System notification
        postNotification(sighting)

        // Officer unit correlation
        scope.launch {
            try {
                val unitId = officerCorrelationEngine.correlate(sighting)
                if (unitId != null) {
                    policeSightingDao.setOfficerUnitId(sighting.id, unitId)
                    val unit = officerCorrelationEngine.getUnit(unitId)
                    if (unit != null && unit.confirmCount >= 3) {
                        policeSightingDao.setUserVerdict(sighting.id, "confirmed")
                        AppLog.i(TAG, "Auto-confirmed sighting ${sighting.id} (unit $unitId, confirmCount=${unit.confirmCount})")
                    }
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Officer correlation failed: ${e.message}", e)
            }
        }

        // TODO(phase-future): re-enable for P2P feature
        // val isOffline = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        //     .getBoolean("offline_mode", true)
        // if (!isOffline && p2pManager.isP2PEnabled()) {
        //     try { p2pManager.broadcastPoliceSighting(sighting) }
        //     catch (e: Exception) { AppLog.w(TAG, "P2P broadcast failed", e) }
        // }
    }

    private fun postNotification(sighting: PoliceSighting) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                // Audit fix 4.3: pass only sighting ID, not raw GPS coords in PendingIntent
                putExtra("police_sighting_id", sighting.id)
            }
            val pi = PendingIntent.getActivity(
                context,
                NOTIF_ID_BASE + sighting.id.hashCode().and(0xFFFF),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val categoryLabel = when (sighting.detectionCategory) {
                "AXON"             -> "Axon body camera"
                "TASER"            -> "TASER device"
                "DIGITAL_ALLY"     -> "Digital Ally body camera"
                "WATCHGUARD"       -> "WatchGuard body camera"
                "KENWOOD"          -> "Kenwood radio equipment"
                "MOTIVE"           -> "MOTIVE fleet device"
                "CRADLEPOINT"      -> "Police vehicle router (CradlePoint)"
                "MOTOROLA_SOLUTIONS" -> "Motorola Solutions device"
                "CELLULAR_MDT"     -> "Mobile Data Terminal"
                "CORRELATED"       -> "Multiple police signatures detected"
                else               -> "Law enforcement equipment"
            }
            com.aegisnav.app.util.NotificationHelper.notify(
                context,
                NOTIF_ID_BASE + sighting.id.hashCode().and(0xFFFF),
                NotificationCompat.Builder(context, ScanService.CHANNEL_ID)
                    .setContentTitle("⚠️ $categoryLabel detected nearby")
                    .setContentText(
                        "${String.format("%.4f, %.4f", sighting.lat, sighting.lon)} " +
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
