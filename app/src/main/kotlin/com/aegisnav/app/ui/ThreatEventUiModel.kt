package com.aegisnav.app.ui

import com.aegisnav.app.data.model.ThreatEvent
import com.aegisnav.app.tracker.Sighting
import com.aegisnav.app.tracker.TrackerAlert
import com.aegisnav.app.util.AppLog
import org.json.JSONException
import org.json.JSONObject

/**
 * Maps a persisted [ThreatEvent] to a [TrackerAlert] suitable for display in
 * [TrackerDetailScreen]. Returns null if the event type is not "TRACKER" or if
 * the JSON payload cannot be used to construct a valid alert.
 */
fun ThreatEvent.toTrackerAlert(): TrackerAlert? {
    val detail = try {
        JSONObject(detailJson)
    } catch (e: JSONException) {
        AppLog.w("ThreatEventUiModel", "Failed to parse event detailJson: ${e.message}")
        JSONObject()
    }

    val sightingsList: List<Sighting> = try {
        val arr = detail.optJSONArray("sightings")
        if (arr != null) {
            (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                Sighting(
                    timestamp = s.optLong("timestamp", 0L),
                    lat = s.optDouble("lat", 0.0),
                    lon = s.optDouble("lon", 0.0),
                    rssi = s.optInt("rssi", -80)
                )
            }
        } else emptyList()
    } catch (e: JSONException) {
        AppLog.w("ThreatEventUiModel", "Failed to parse sightings: ${e.message}")
        emptyList()
    }

    val rssiTrend: List<Int> = try {
        val arr = detail.optJSONArray("rssiTrend")
        if (arr != null) (0 until arr.length()).map { arr.getInt(it) } else emptyList()
    } catch (e: JSONException) {
        AppLog.w("ThreatEventUiModel", "Failed to parse rssiTrend: ${e.message}")
        emptyList()
    }

    val sightingsForAlert = if (sightingsList.isEmpty()) {
        listOf(Sighting(timestamp = timestamp, lat = 0.0, lon = 0.0, rssi = -80))
    } else sightingsList

    return TrackerAlert(
        mac = mac.ifBlank { detail.optString("mac", "??:??:??:??:??:??") },
        manufacturer = detail.optString("manufacturer", "").ifBlank { null },
        sightings = sightingsForAlert,
        firstSeen = detail.optLong("firstSeen", timestamp),
        lastSeen = detail.optLong("lastSeen", timestamp),
        rssiTrend = rssiTrend
    )
}
