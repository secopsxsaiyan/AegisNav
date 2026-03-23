package com.aegisnav.app

import com.aegisnav.app.util.AppLog

/**
 * Singleton that tracks whether the app is in the foreground or background.
 * Updated by the ProcessLifecycleOwner observer in [AegisNavApplication].
 *
 * Consumers:
 *  - [com.aegisnav.app.util.AlertTtsManager]  — suppress TTS when backgrounded
 *  - [com.aegisnav.app.ui.DetectionPopupHost] — queue alerts when backgrounded
 *  - [com.aegisnav.app.ScanService]           — throttle scans after 10min background
 *  - [com.aegisnav.app.scan.ScanOrchestrator] — reduce scan budget when throttled
 */
object AppLifecycleTracker {

    private const val TAG = "AppLifecycleTracker"

    /** Minimum background duration before scan throttle kicks in (10 minutes). */
    const val BACKGROUND_THROTTLE_MS = 10 * 60_000L

    /** True when the app is in the foreground (at least one Activity is started). */
    @Volatile var isInForeground: Boolean = true
        private set

    /** Epoch ms when the app last entered the background. 0 if currently in foreground. */
    @Volatile var backgroundSinceMs: Long = 0L
        private set

    /**
     * True if the user swiped the app from recents (onTaskRemoved fired).
     * When true, all scanning and alerts should be permanently stopped until the
     * app is explicitly relaunched by the user.
     */
    @Volatile var isKilled: Boolean = false

    /**
     * True if the app has been in the background for >= [BACKGROUND_THROTTLE_MS].
     * Used to trigger reduced scan frequency and alert suppression.
     */
    val isBackgroundThrottled: Boolean
        get() = !isInForeground && backgroundSinceMs > 0L &&
                (System.currentTimeMillis() - backgroundSinceMs) >= BACKGROUND_THROTTLE_MS

    /** Called by AegisNavApplication when the app enters the foreground. */
    fun onForeground() {
        isInForeground = true
        backgroundSinceMs = 0L
        AppLog.i(TAG, "App entered foreground")
    }

    /** Called by AegisNavApplication when the app enters the background. */
    fun onBackground() {
        isInForeground = false
        backgroundSinceMs = System.currentTimeMillis()
        AppLog.i(TAG, "App entered background")
    }

    /**
     * Called by ScanService.onTaskRemoved() when the user swipes the app from recents.
     * Signals full shutdown — no scanning, TTS, location updates, or WorkManager work.
     */
    fun onKill() {
        isKilled = true
        isInForeground = false
        AppLog.i(TAG, "App killed (swipe from recents)")
    }
}
