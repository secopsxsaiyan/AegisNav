package com.aegisnav.app.intelligence

import com.aegisnav.app.data.model.ScanLog
import com.aegisnav.app.util.AppLog
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-Window Time Analysis Engine — Phase 4.7 (CYT-NG Intelligence).
 *
 * Extends the existing 30-minute tracker window with four additional analysis windows
 * targeting different threat profiles:
 *
 * | Window       | Duration  | Target         | Check interval |
 * |--------------|-----------|----------------|----------------|
 * | STAKEOUT     |  15 min   | Cop stakeouts  | every scan     |
 * | SHORT        |  30 min   | Trackers (existing baseline) | every scan |
 * | MEDIUM       |   1 hour  | Persistent surveillance | every 10 min |
 * | LONG         |   4 hours | Day-long following | every 30 min |
 * | DAY          |  24 hours | Cross-session  | every 60 min   |
 *
 * Consumers call [onScanResult] for every new scan event, then [analyzeNow] when
 * they want multi-window analysis for a specific MAC.
 *
 * The engine tracks when each window was last checked per MAC to avoid expensive
 * re-analysis on every scan tick.
 */
@Singleton
class MultiWindowAnalyzer @Inject constructor() {

    companion object {
        private const val TAG = "MultiWindowAnalyzer"

        /** The five analysis windows (ms). */
        const val WINDOW_STAKEOUT_MS = 15 * 60_000L       // 15 min — cop stakeout
        const val WINDOW_SHORT_MS    = 30 * 60_000L       // 30 min — existing baseline
        const val WINDOW_MEDIUM_MS   =  1 * 60 * 60_000L  // 1 hour
        const val WINDOW_LONG_MS     =  4 * 60 * 60_000L  // 4 hours
        const val WINDOW_DAY_MS      = 24 * 60 * 60_000L  // 24 hours

        /** Minimum interval between MEDIUM window checks per device. */
        const val MEDIUM_CHECK_INTERVAL_MS = 10 * 60_000L // 10 min

        /** Minimum interval between LONG window checks per device. */
        const val LONG_CHECK_INTERVAL_MS   = 30 * 60_000L // 30 min

        /** Minimum interval between DAY window checks per device. */
        const val DAY_CHECK_INTERVAL_MS    = 60 * 60_000L // 60 min

        /** Maximum sightings kept per device (cap memory). */
        private const val MAX_SIGHTINGS = 200
    }

    enum class AnalysisWindow(val durationMs: Long) {
        STAKEOUT(WINDOW_STAKEOUT_MS),
        SHORT(WINDOW_SHORT_MS),
        MEDIUM(WINDOW_MEDIUM_MS),
        LONG(WINDOW_LONG_MS),
        DAY(WINDOW_DAY_MS)
    }

    data class WindowResult(
        val window: AnalysisWindow,
        val mac: String,
        val sightingCount: Int,
        val distinctZones: Int,
        val spanMs: Long,
        val analyzedAtMs: Long
    )

    /** Per-MAC sighting ring-buffer (24h max). */
    private val macSightings = ConcurrentHashMap<String, ArrayDeque<ScanLog>>()

    /** Per-MAC, per-window last-check epoch-ms. */
    private val lastCheckMs = ConcurrentHashMap<String, MutableMap<AnalysisWindow, Long>>()

    /**
     * Record a new scan result. Prunes entries older than DAY window.
     */
    fun onScanResult(log: ScanLog) {
        val mac = log.deviceAddress
        val buf = macSightings.computeIfAbsent(mac) { ArrayDeque(MAX_SIGHTINGS) }
        synchronized(buf) {
            buf.addLast(log)
            if (buf.size > MAX_SIGHTINGS) buf.removeFirst()
            // Prune > 24 h
            val cutoff = log.timestamp - WINDOW_DAY_MS
            while (buf.isNotEmpty() && buf.first().timestamp < cutoff) buf.removeFirst()
        }
    }

    /**
     * Run multi-window analysis for [mac] and return results for each window that is due.
     *
     * Short/Stakeout windows are always run. Medium/Long/Day windows are throttled by
     * their respective check intervals to keep CPU overhead low.
     *
     * @param mac   Device MAC address.
     * @param nowMs Current epoch-ms (injectable for tests).
     */
    fun analyzeNow(mac: String, nowMs: Long = System.currentTimeMillis()): List<WindowResult> {
        val history = macSightings[mac] ?: return emptyList()
        val checks = lastCheckMs.getOrPut(mac) { mutableMapOf() }
        val results = mutableListOf<WindowResult>()

        for (window in AnalysisWindow.values()) {
            if (!isDue(window, checks, nowMs)) continue

            val windowSightings = synchronized(history) {
                history.filter { s -> (nowMs - s.timestamp) <= window.durationMs }
            }
            if (windowSightings.isEmpty()) continue

            val zones = windowSightings
                .mapNotNull { s -> s.lat?.let { la -> s.lng?.let { lo -> zoneKey(la, lo) } } }
                .toSet()

            val span = windowSightings.maxOf { it.timestamp } -
                       windowSightings.minOf { it.timestamp }

            results.add(
                WindowResult(
                    window        = window,
                    mac           = mac,
                    sightingCount = windowSightings.size,
                    distinctZones = zones.size,
                    spanMs        = span,
                    analyzedAtMs  = nowMs
                )
            )
            checks[window] = nowMs
            AppLog.d(TAG, "Window ${window.name} mac=$mac sightings=${windowSightings.size} zones=${zones.size}")
        }
        return results
    }

    /**
     * Convenience: analyse all known MACs and return all due window results.
     */
    fun analyzeAll(nowMs: Long = System.currentTimeMillis()): Map<String, List<WindowResult>> {
        return macSightings.keys.associateWith { mac -> analyzeNow(mac, nowMs) }
            .filterValues { it.isNotEmpty() }
    }

    /** Clear in-memory state for all devices. */
    fun clear() {
        macSightings.clear()
        lastCheckMs.clear()
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun isDue(
        window: AnalysisWindow,
        checks: Map<AnalysisWindow, Long>,
        nowMs: Long
    ): Boolean {
        val interval = when (window) {
            AnalysisWindow.STAKEOUT -> 0L           // always
            AnalysisWindow.SHORT    -> 0L           // always
            AnalysisWindow.MEDIUM   -> MEDIUM_CHECK_INTERVAL_MS
            AnalysisWindow.LONG     -> LONG_CHECK_INTERVAL_MS
            AnalysisWindow.DAY      -> DAY_CHECK_INTERVAL_MS
        }
        val lastCheck = checks[window] ?: 0L
        return (nowMs - lastCheck) >= interval
    }

    private fun zoneKey(lat: Double, lon: Double): String {
        val la = Math.round(lat * 1000) / 1000.0
        val lo = Math.round(lon * 1000) / 1000.0
        return "$la:$lo"
    }
}
