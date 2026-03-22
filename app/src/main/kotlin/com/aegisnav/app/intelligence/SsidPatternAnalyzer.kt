package com.aegisnav.app.intelligence

import com.aegisnav.app.data.model.ScanLog
import com.aegisnav.app.police.PoliceDetector
import com.aegisnav.app.util.AppLog
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSID Pattern Analyzer — Phase 4.8 (CYT-NG Intelligence).
 *
 * Identifies law enforcement access points via SSID pattern matching and
 * anomalous SSID diversity signatures. Integrates with [PoliceDetector] to
 * boost confidence when SSID evidence is co-located with other police indicators.
 *
 * **Detection vectors:**
 *
 * 1. **Prefix patterns** — SSID starts with a known law-enforcement prefix:
 *    MDT-, CAD-, PATROL-, GOVT-, LEA-, AXON-, EVIDENCE-
 *    (matched case-insensitively).
 *
 * 2. **SSID diversity flag** — an access point (BSSID) that broadcasts >20 unique
 *    SSIDs is likely a covert police AP or MDT hotspot (normal APs broadcast 1–4 SSIDs).
 *
 * 3. **PoliceDetector cross-reference** — when [PoliceDetector.ALERT_THRESHOLD] is
 *    exceeded for the same BSSID or nearby device, SSID confidence is boosted by
 *    [POLICE_CROSS_REF_BOOST].
 *
 * Results are returned as [SsidMatch] objects. Callers may feed these into
 * [PoliceDetector] as supplementary confidence signals.
 */
@Singleton
class SsidPatternAnalyzer @Inject constructor() {

    companion object {
        private const val TAG = "SsidPatternAnalyzer"

        /** SSID prefixes associated with law enforcement equipment. */
        val POLICE_SSID_PREFIXES: List<String> = listOf(
            "MDT-",
            "CAD-",
            "PATROL-",
            "GOVT-",
            "LEA-",
            "AXON-",
            "EVIDENCE-"
        )

        /** Confidence score per matched SSID prefix. */
        const val PREFIX_MATCH_CONFIDENCE = 0.60f

        /** Number of unique SSIDs per BSSID that triggers the diversity flag. */
        const val SSID_DIVERSITY_THRESHOLD = 20

        /** Confidence score for an AP with anomalous SSID diversity. */
        const val DIVERSITY_CONFIDENCE = 0.40f

        /** Additional confidence boost when PoliceDetector has already flagged this area. */
        const val POLICE_CROSS_REF_BOOST = 0.20f

        /** How long to retain per-BSSID SSID history (ms). */
        private const val BSSID_HISTORY_TTL_MS = 60 * 60_000L  // 1 hour
    }

    data class SsidMatch(
        val bssid: String,
        val ssid: String?,
        val matchReason: MatchReason,
        val confidence: Float,
        val timestamp: Long
    )

    enum class MatchReason {
        PREFIX_MATCH,
        SSID_DIVERSITY,
        CROSS_REFERENCED
    }

    /** BSSID → set of unique SSIDs seen (with timestamps). */
    private val bssidSsidHistory = ConcurrentHashMap<String, BssidRecord>()

    /** BSSIDs already flagged by PoliceDetector (injected via [markPoliceConfirmed]). */
    private val policeConfirmedBssids = ConcurrentHashMap.newKeySet<String>()

    private data class BssidRecord(
        val ssids: MutableSet<String> = mutableSetOf(),
        var lastSeen: Long = 0L
    )

    /**
     * Process a WiFi scan log entry.
     * Returns a [SsidMatch] if law enforcement SSID evidence is detected, otherwise null.
     */
    fun onWifiScanResult(log: ScanLog): SsidMatch? {
        if (log.scanType != "WIFI") return null
        val bssid = log.deviceAddress.uppercase()
        val ssid  = log.ssid?.trim()?.takeIf { it.isNotEmpty() }
        val now   = log.timestamp

        // Update BSSID SSID history
        val record = bssidSsidHistory.computeIfAbsent(bssid) { BssidRecord() }
        synchronized(record) {
            if (ssid != null) record.ssids.add(ssid)
            record.lastSeen = now
        }

        pruneStale(now)

        // --- Detection 1: prefix match ---
        if (ssid != null) {
            val matchedPrefix = POLICE_SSID_PREFIXES.firstOrNull { prefix ->
                ssid.uppercase().startsWith(prefix.uppercase())
            }
            if (matchedPrefix != null) {
                val boost = if (bssid in policeConfirmedBssids) POLICE_CROSS_REF_BOOST else 0f
                val confidence = minOf(PREFIX_MATCH_CONFIDENCE + boost, 1.0f)
                AppLog.i(TAG, "SSID prefix match bssid=$bssid ssid=$ssid confidence=$confidence")
                val reason = if (boost > 0) MatchReason.CROSS_REFERENCED else MatchReason.PREFIX_MATCH
                return SsidMatch(bssid, ssid, reason, confidence, now)
            }
        }

        // --- Detection 2: SSID diversity flag ---
        val uniqueSsidCount = synchronized(record) { record.ssids.size }
        if (uniqueSsidCount > SSID_DIVERSITY_THRESHOLD) {
            val boost = if (bssid in policeConfirmedBssids) POLICE_CROSS_REF_BOOST else 0f
            val confidence = minOf(DIVERSITY_CONFIDENCE + boost, 1.0f)
            AppLog.i(TAG, "SSID diversity bssid=$bssid uniqueSsids=$uniqueSsidCount confidence=$confidence")
            val reason = if (boost > 0) MatchReason.CROSS_REFERENCED else MatchReason.SSID_DIVERSITY
            return SsidMatch(bssid, ssid, reason, confidence, now)
        }

        return null
    }

    /**
     * Notify the analyzer that [bssid] has been independently flagged by [PoliceDetector].
     * Future matches for this BSSID will receive the [POLICE_CROSS_REF_BOOST].
     */
    fun markPoliceConfirmed(bssid: String) {
        policeConfirmedBssids.add(bssid.uppercase())
        AppLog.d(TAG, "policeConfirmed bssid=$bssid")
    }

    /**
     * Check an SSID string against the known police prefix list.
     * Pure function — useful for one-shot checks without state.
     */
    fun matchesPolicePattern(ssid: String): Boolean {
        val upper = ssid.uppercase()
        return POLICE_SSID_PREFIXES.any { prefix -> upper.startsWith(prefix.uppercase()) }
    }

    /** Clear in-memory state. */
    fun clear() {
        bssidSsidHistory.clear()
        policeConfirmedBssids.clear()
    }

    private fun pruneStale(nowMs: Long) {
        val cutoff = nowMs - BSSID_HISTORY_TTL_MS
        bssidSsidHistory.entries.removeIf { (_, record) ->
            synchronized(record) { record.lastSeen < cutoff }
        }
    }
}
