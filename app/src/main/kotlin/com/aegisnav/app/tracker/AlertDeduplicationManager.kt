package com.aegisnav.app.tracker

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 3 — Feature 3.8: Unified AlertDeduplicationManager.
 *
 * Prevents duplicate alerts from being shown/notified when multiple detection
 * subsystems (tracker engine, beacon history manager, correlation engine) would
 * independently generate the same alert within a short window.
 *
 * Usage:
 *   if (dedup.shouldEmit("TRACKER:AA:BB:CC:DD:EE:FF")) { /* show alert */ }
 *
 * Thread-safe.  All internal state is stored in [ConcurrentHashMap].
 */
@Singleton
class AlertDeduplicationManager @Inject constructor() {

    companion object {
        /** Default deduplication window — same key within this window is suppressed. */
        const val DEFAULT_WINDOW_MS = 5 * 60_000L  // 5 minutes

        /** Longer window for high-severity tracker alerts (avoids repeat alarms in same session). */
        const val TRACKER_ALERT_WINDOW_MS = 15 * 60_000L
    }

    /** last-emitted timestamp per key. */
    private val lastEmitted = ConcurrentHashMap<String, Long>()

    /**
     * Returns true if the alert with this [key] should be emitted now.
     *
     * Records the current timestamp for [key] and returns true only when
     * the key has not been seen within [windowMs].
     *
     * @param key     Unique identifier for the alert (e.g. "TRACKER:AA:BB:CC:DD:EE:FF")
     * @param windowMs Deduplication window in milliseconds
     */
    fun shouldEmit(key: String, windowMs: Long = DEFAULT_WINDOW_MS): Boolean {
        val now = System.currentTimeMillis()
        val last = lastEmitted[key] ?: 0L
        return if (now - last > windowMs) {
            lastEmitted[key] = now
            true
        } else {
            false
        }
    }

    /**
     * Force-reset the deduplication state for [key], allowing the next call to
     * [shouldEmit] to return true regardless of the previous emit time.
     */
    fun reset(key: String) {
        lastEmitted.remove(key)
    }

    /**
     * Clear all deduplication state (e.g. at the start of a new scan session).
     */
    fun clear() {
        lastEmitted.clear()
    }

    /** Number of tracked keys in the deduplication map. */
    val trackedCount: Int get() = lastEmitted.size
}
