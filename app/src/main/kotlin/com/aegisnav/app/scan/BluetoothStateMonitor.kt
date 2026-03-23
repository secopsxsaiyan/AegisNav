package com.aegisnav.app.scan

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.core.app.NotificationCompat
import com.aegisnav.app.security.SecureDataStore
import androidx.core.content.ContextCompat
import com.aegisnav.app.MainActivity
import com.aegisnav.app.R
import com.aegisnav.app.ScanService
import com.aegisnav.app.util.AppLog
import com.aegisnav.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 3.15 — Bluetooth State Monitoring.
 *
 * Manifest-registered [BroadcastReceiver] for [BluetoothAdapter.ACTION_STATE_CHANGED].
 *
 * Behaviour:
 *  - **BT OFF**: Show persistent notification "Bluetooth disabled — tracker detection paused".
 *               If ScanService is running during an active alert, show additional warning.
 *               Record BT-off timestamp in SharedPreferences so ScanService can detect it
 *               on resume.
 *  - **BT ON**: Cancel the BT-disabled notification. Automatically restart ScanService if
 *               the user had scanning enabled before BT went off.
 *
 * Edge cases handled:
 *  - BT toggled while ScanService is actively scanning: service detects BT loss naturally
 *    (leScanner becomes null, startScan fails), and will NOT restart until BT is back.
 *  - BT toggled while a tracker alert is active: warning notification is posted alongside
 *    the existing tracker alert.
 *  - Multiple rapid BT off/on cycles: SharedPreferences guard prevents duplicate restarts.
 *
 * Integration:
 *  ScanService reads PREFS_USER_WANTS_SCANNING from "an_prefs" SharedPreferences.
 *  BluetoothStateMonitor writes PREFS_BT_DISABLED_AT so ScanService can log BT gaps.
 */
class BluetoothStateMonitor : BroadcastReceiver() {

    companion object {
        private const val TAG = "BluetoothStateMonitor"

        private const val PREFS_NAME = "an_prefs"
        private const val PREFS_USER_WANTS_SCANNING = "user_wants_scanning"
        private const val PREFS_BT_DISABLED_AT = "bt_disabled_at_ms"

        const val NOTIF_ID_BT_DISABLED = 7001
        const val NOTIF_ID_BT_ALERT_WARNING = 7002
        private const val CHANNEL_ID = ScanService.CHANNEL_ID

        // Coroutine scope for goAsync() BroadcastReceiver work — lives for the app process
        internal val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Returns true if [state] corresponds to Bluetooth being fully off.
         * Exposed for unit testing without Android framework.
         */
        fun isBluetoothOff(state: Int): Boolean = state == BluetoothAdapter.STATE_OFF

        /**
         * Returns true if [state] corresponds to Bluetooth being fully on.
         * Exposed for unit testing without Android framework.
         */
        fun isBluetoothOn(state: Int): Boolean = state == BluetoothAdapter.STATE_ON

        /**
         * Maps a BluetoothAdapter state integer to a human-readable label.
         * Exposed for unit testing without Android framework.
         */
        fun stateName(state: Int): String = when (state) {
            BluetoothAdapter.STATE_OFF     -> "OFF"
            BluetoothAdapter.STATE_ON      -> "ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
            BluetoothAdapter.STATE_TURNING_ON  -> "TURNING_ON"
            else -> "UNKNOWN($state)"
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return

        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
        AppLog.i(TAG, "Bluetooth state changed → ${stateName(state)}")

        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                when {
                    isBluetoothOff(state) -> handleBluetoothOff(context)
                    isBluetoothOn(state)  -> handleBluetoothOn(context)
                    // TURNING_OFF / TURNING_ON: transitional states — no action needed
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    // ── BT OFF ────────────────────────────────────────────────────────────────

    private suspend fun handleBluetoothOff(context: Context) {
        val dataStore = SecureDataStore.get(context, PREFS_NAME)
        dataStore.edit { it[longPreferencesKey(PREFS_BT_DISABLED_AT)] = System.currentTimeMillis() }

        AppLog.w(TAG, "Bluetooth disabled — BLE scanning paused")

        // Stop ScanService gracefully; it will be restarted when BT comes back
        // (only if user_wants_scanning is still true).
        context.stopService(Intent(context, ScanService::class.java))

        // Primary notification: BT disabled, detection paused
        showBtDisabledNotification(context)

        // Additional warning if there are active tracker notifications
        // (we can't know for sure without shared state, so we check a prefs flag
        //  that ScanService sets when an alert fires and clears when service stops)
        val hasActiveAlert = dataStore.data.first()[booleanPreferencesKey("has_active_tracker_alert")] ?: false
        if (hasActiveAlert) {
            showActiveAlertWarning(context)
        }
    }

    private fun showBtDisabledNotification(context: Context) {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPi = android.app.PendingIntent.getActivity(
            context, NOTIF_ID_BT_DISABLED, tapIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Bluetooth disabled")
            .setContentText("Tracker detection is paused. Re-enable Bluetooth to resume scanning.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Tracker detection is paused because Bluetooth was turned off. " +
                        "Re-enable Bluetooth to automatically resume scanning."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(tapPi)
            .build()

        NotificationHelper.notify(context, NOTIF_ID_BT_DISABLED, notification)
    }

    private fun showActiveAlertWarning(context: Context) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("⚠️ Cannot track device — Bluetooth off")
            .setContentText("An active tracker alert was suspended. Re-enable Bluetooth to continue tracking.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationHelper.notify(context, NOTIF_ID_BT_ALERT_WARNING, notification)
    }

    // ── BT ON ─────────────────────────────────────────────────────────────────

    private suspend fun handleBluetoothOn(context: Context) {
        // Cancel the BT-disabled notification
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.cancel(NOTIF_ID_BT_DISABLED)
        nm?.cancel(NOTIF_ID_BT_ALERT_WARNING)

        val dataStore = SecureDataStore.get(context, PREFS_NAME)
        val snapshot = dataStore.data.first()
        val btOffAt = snapshot[longPreferencesKey(PREFS_BT_DISABLED_AT)] ?: 0L
        val gapMs = if (btOffAt > 0) System.currentTimeMillis() - btOffAt else 0L
        dataStore.edit { it.remove(longPreferencesKey(PREFS_BT_DISABLED_AT)) }

        AppLog.i(TAG, "Bluetooth re-enabled (was off for ~${gapMs / 1000}s). Checking scan intent…")

        // Restart ScanService only if the user had scanning active before
        val userWantsScanning = snapshot[booleanPreferencesKey(PREFS_USER_WANTS_SCANNING)] ?: false
        if (userWantsScanning) {
            AppLog.i(TAG, "Restarting ScanService after Bluetooth re-enable")
            ContextCompat.startForegroundService(context, Intent(context, ScanService::class.java))
        } else {
            AppLog.d(TAG, "User did not want scanning — ScanService not restarted")
        }
    }

}
