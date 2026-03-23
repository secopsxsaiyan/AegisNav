package com.aegisnav.app.ui

import com.aegisnav.app.ThreatLevel
import com.aegisnav.app.data.model.ThreatEvent
import com.aegisnav.app.util.AppLog
import org.json.JSONException
import org.json.JSONObject

// ── Parsed display models ──────────────────────────────────────────────────────

/**
 * Fully-parsed display model extracted from a [ThreatEvent]'s detailJson.
 *
 * Used in [com.aegisnav.app.ThreatHistoryScreen] via
 * `remember(event) { event.toThreatEventDetail() }`.
 */
data class ThreatEventDetail(
    val raw: JSONObject,
    val mac: String,
    val manufacturer: String?,
    val ssid: String?,
    val alertId: String?,
    val lastRssi: Int?,
    val estimatedDistanceMeters: Double?,
    val distanceTrend: String?,
    val isBaselineAnomaly: Boolean,
    val baselineAnomalyType: String?,
    val policeDistanceMeters: Double?,
    val rssiTrend: List<Int>,
    val sightings: List<SightingDetail>,
    val policeCategory: String?,
    val convoyMacs: List<String>,
    val coordinatedMacs: List<String>,
    val speedMph: Int,
    val avgBearingDeg: Double?,
    val correlatedVectors: Int,
    val sharedCellCount: Int,
    val correlationScore: Double?,
    val watchlistLabel: String?,
    val policeLat: Double?,
    val policeLon: Double?,
    val policeConf: Double?,
)

/** A single sighting location parsed from the "sightings" JSON array. */
data class SightingDetail(
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    val rssi: Int?
)

// ── Extension functions ────────────────────────────────────────────────────────

/**
 * Parse all display fields from [ThreatEvent.detailJson] into a [ThreatEventDetail].
 * Safe — never throws; bad JSON produces safe defaults.
 */
fun ThreatEvent.toThreatEventDetail(): ThreatEventDetail {
    val detail = try {
        JSONObject(detailJson)
    } catch (e: JSONException) {
        AppLog.w("ThreatHistoryUiModel", "Failed to parse event detailJson: ${e.message}")
        JSONObject()
    }

    val parsedRssiTrend: List<Int> = try {
        val arr = detail.optJSONArray("rssiTrend")
        if (arr != null) (0 until arr.length()).map { arr.getInt(it) } else emptyList()
    } catch (e: JSONException) {
        AppLog.w("ThreatHistoryUiModel", "Failed to parse rssiTrend: ${e.message}")
        emptyList()
    }

    val parsedSightings: List<SightingDetail> = try {
        val arr = detail.optJSONArray("sightings")
        if (arr != null) (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            SightingDetail(
                timestamp = s.optLong("timestamp", 0L),
                lat       = s.optDouble("lat", 0.0),
                lon       = s.optDouble("lon", 0.0),
                rssi      = if (s.isNull("rssi")) null else s.optInt("rssi")
            )
        } else emptyList()
    } catch (e: JSONException) {
        AppLog.w("ThreatHistoryUiModel", "JSON parse failed (sightings): ${e.message}")
        emptyList()
    }

    val parsedConvoyMacs: List<String> = try {
        val arr = detail.optJSONArray("deviceGroup")
        if (arr != null) (0 until arr.length()).map { arr.getString(it) } else emptyList()
    } catch (e: JSONException) {
        AppLog.w("ThreatHistoryUiModel", "JSON parse failed (convoy deviceGroup): ${e.message}")
        emptyList()
    }

    // coordinatedMacs reuses the same "deviceGroup" key — parsed identically
    val parsedCoordinatedMacs: List<String> = parsedConvoyMacs

    val policeCategory = if (type == "POLICE_DETECTION")
        detail.optString("category", "").ifBlank { null } else null
    val policeDistM = if (type == "POLICE_DETECTION")
        detail.optDouble("estimated_distance_meters", -1.0).takeIf { it > 0 } else null
    val estimatedDistM = if (detail.isNull("estimatedDistanceMeters")) null
                         else detail.optDouble("estimatedDistanceMeters").takeIf { it > 0 }
    val policeLat  = detail.optDouble("lat",  Double.NaN).takeIf { !it.isNaN() }
    val policeLon  = detail.optDouble("lon",  Double.NaN).takeIf { !it.isNaN() }
    val policeConf = detail.optDouble("confidence", Double.NaN).takeIf { !it.isNaN() }

    return ThreatEventDetail(
        raw                    = detail,
        mac                    = mac.ifBlank { detail.optString("mac", "").ifBlank { "-" } },
        manufacturer           = detail.optString("manufacturer", "").ifBlank { null },
        ssid                   = detail.optString("ssid", "").ifBlank { null },
        alertId                = detail.optString("alertId", "").ifBlank { null },
        lastRssi               = if (detail.isNull("lastRssi")) null else detail.optInt("lastRssi"),
        estimatedDistanceMeters = estimatedDistM,
        distanceTrend          = detail.optString("distanceTrend", "").ifBlank { null },
        isBaselineAnomaly      = detail.optBoolean("isBaselineAnomaly", false),
        baselineAnomalyType    = detail.optString("baselineAnomalyType", "").ifBlank { null },
        policeDistanceMeters   = policeDistM,
        rssiTrend              = parsedRssiTrend,
        sightings              = parsedSightings,
        policeCategory         = policeCategory,
        convoyMacs             = parsedConvoyMacs,
        coordinatedMacs        = parsedCoordinatedMacs,
        speedMph               = detail.optInt("speedMph", 0),
        avgBearingDeg          = detail.optDouble("avgBearingDeg", -1.0).takeIf { it >= 0 },
        correlatedVectors      = detail.optInt("correlatedVectors", 0),
        sharedCellCount        = detail.optJSONArray("sharedCells")?.length() ?: 0,
        correlationScore       = detail.optDouble("correlationScore", -1.0).takeIf { it >= 0 },
        watchlistLabel         = detail.optString("watchlistLabel", "").ifBlank { null },
        policeLat              = if (type == "POLICE_DETECTION") policeLat else null,
        policeLon              = if (type == "POLICE_DETECTION") policeLon else null,
        policeConf             = if (type == "POLICE_DETECTION") policeConf else null,
    )
}

// ── Confidence / threat-level computation extracted as pure functions ──────────

/**
 * Derives a 0–1 confidence score from the event's detailJson fields.
 *
 * TRACKER:          stopCount (up to 8) + maxSpreadMeters (up to 2000 m) → each weighted 50%
 * POLICE_DETECTION: uses the "confidence" field stored by PoliceReportingCoordinator
 * BLE_CORRELATION:  fixed moderate score
 * ALPR_PROXIMITY:   fixed moderate-high score
 */
fun ThreatEvent.computeConfidenceScore(): Float {
    return try {
        val detail = JSONObject(detailJson)
        when (type) {
            "TRACKER" -> {
                val stopCount = detail.optInt("stopCount", 0)
                val maxSpread = detail.optDouble("maxSpreadMeters", 0.0)
                val stopScore   = (stopCount.toFloat() / 8f).coerceAtMost(0.5f)
                val spreadScore = (maxSpread.toFloat() / 2000f).coerceAtMost(0.5f)
                (stopScore + spreadScore).coerceIn(0f, 1f)
            }
            "POLICE_DETECTION" ->
                detail.optDouble("confidence", 0.6).toFloat().coerceIn(0f, 1f)
            "BLE_CORRELATION" -> 0.6f
            "ALPR_PROXIMITY"  -> 0.7f
            "CONVOY" -> {
                val vectors = detail.optInt("correlatedVectors", 0)
                (vectors.toFloat() / 10f).coerceIn(0.3f, 1f)
            }
            "COORDINATED" -> {
                detail.optDouble("correlationScore", 0.5).toFloat().coerceIn(0.3f, 1f)
            }
            else -> 0.5f
        }
    } catch (e: JSONException) {
        AppLog.w("ThreatHistoryUiModel", "computeConfidenceScore JSON parse failed: ${e.message}")
        0.5f
    }
}

/**
 * Derives a [ThreatLevel] from a group's temporal/spatial properties.
 *
 * HIGH:   tracking span > 5 min AND (4+ stops OR spread ≥ 500 m), or very high confidence
 * MEDIUM: more than one sighting, or moderate confidence
 * LOW:    single or sparse sightings
 */
fun computeThreatLevelForGroup(
    events: List<ThreatEvent>,
    firstSeen: Long,
    lastSeen: Long,
    maxConfidence: Float
): ThreatLevel {
    val durationMs = lastSeen - firstSeen
    val trackerEvent = events.firstOrNull { it.type == "TRACKER" }
    val detail = try {
        JSONObject((trackerEvent ?: events.first()).detailJson)
    } catch (e: JSONException) {
        AppLog.w("ThreatHistoryUiModel", "computeThreatLevel JSON parse failed: ${e.message}")
        JSONObject()
    }
    val stopCount = detail.optInt("stopCount", 0)
    val maxSpread = detail.optDouble("maxSpreadMeters", 0.0)

    return when {
        (durationMs > 5 * 60_000L && (stopCount >= 4 || maxSpread >= 500.0)) ||
        (maxConfidence >= 0.8f && durationMs > 2 * 60_000L) -> ThreatLevel.HIGH
        events.size > 1 || maxConfidence >= 0.5f             -> ThreatLevel.MEDIUM
        else                                                  -> ThreatLevel.LOW
    }
}
