package com.aegisnav.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aegisnav.app.data.model.ThreatEvent
import com.aegisnav.app.util.AppLog
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// ── Threat level ──────────────────────────────────────────────────────────────

enum class ThreatLevel { HIGH, MEDIUM, LOW }

// ── Grouped event model ────────────────────────────────────────────────────────

/**
 * One entry in the Threat History list, representing 1-N [ThreatEvent]s
 * from the same device (by MAC/BSSID) within a 10-minute window.
 */
private data class GroupedThreatEvent(
    /** The single event that best represents this group (highest confidence). */
    val representativeEvent: ThreatEvent,
    /** All raw DB events in this group. */
    val events: List<ThreatEvent>,
    /** Device identifier (MAC address / BSSID). */
    val deviceIdentifier: String,
    /** Epoch ms of the earliest event. */
    val firstSeen: Long,
    /** Epoch ms of the latest event. */
    val lastSeen: Long,
    /** How many raw ThreatEvents were merged into this group. */
    val sightingCount: Int,
    val threatLevel: ThreatLevel,
    /** 0.0–1.0 */
    val confidence: Float
)

// ── Grouping logic ────────────────────────────────────────────────────────────

private const val GROUP_WINDOW_MS = 10 * 60_000L   // 10-minute merge window

/**
 * Merge a flat list of [ThreatEvent]s into [GroupedThreatEvent]s:
 * – Same MAC/device identifier, within a 10-minute rolling window → one entry.
 * – Result is sorted newest-first (by lastSeen).
 */
private fun groupThreatEvents(events: List<ThreatEvent>): List<GroupedThreatEvent> {
    if (events.isEmpty()) return emptyList()

    val result = mutableListOf<GroupedThreatEvent>()

    // Separate known-MAC events from blank-MAC events (don't merge blanks together)
    val (namedEvents, anonymousEvents) = events.partition { it.mac.isNotBlank() }

    // --- Named devices: group by MAC + 10-min windows ---
    val byMac = namedEvents.groupBy { it.mac }
    for ((mac, macEvents) in byMac) {
        val sorted = macEvents.sortedBy { it.timestamp }
        var windowStart = 0
        while (windowStart < sorted.size) {
            val windowEvents = mutableListOf(sorted[windowStart])
            var i = windowStart + 1
            while (i < sorted.size &&
                sorted[i].timestamp - sorted[i - 1].timestamp <= GROUP_WINDOW_MS
            ) {
                windowEvents.add(sorted[i])
                i++
            }
            windowStart = i
            result.add(buildGroup(mac, windowEvents))
        }
    }

    // --- Anonymous events: each is its own group ---
    for (event in anonymousEvents) {
        val confidence = computeConfidence(event)
        val tl = computeThreatLevel(listOf(event), event.timestamp, event.timestamp, confidence)
        result.add(
            GroupedThreatEvent(
                representativeEvent = event,
                events = listOf(event),
                deviceIdentifier = "unknown",
                firstSeen = event.timestamp,
                lastSeen = event.timestamp,
                sightingCount = 1,
                threatLevel = tl,
                confidence = confidence
            )
        )
    }

    return result.sortedByDescending { it.lastSeen }
}

private fun buildGroup(mac: String, windowEvents: List<ThreatEvent>): GroupedThreatEvent {
    val firstSeen = windowEvents.minOf { it.timestamp }
    val lastSeen  = windowEvents.maxOf { it.timestamp }

    // Pick the event with highest confidence as representative
    val confidences = windowEvents.map { computeConfidence(it) }
    val maxConf = confidences.max()
    val representative = windowEvents[confidences.indexOf(maxConf)]

    val threatLevel = computeThreatLevel(windowEvents, firstSeen, lastSeen, maxConf)
    return GroupedThreatEvent(
        representativeEvent = representative,
        events              = windowEvents,
        deviceIdentifier    = mac,
        firstSeen           = firstSeen,
        lastSeen            = lastSeen,
        sightingCount       = windowEvents.size,
        threatLevel         = threatLevel,
        confidence          = maxConf
    )
}

/**
 * Derives a 0–1 confidence score from the event's detailJson fields.
 *
 * TRACKER:          stopCount (up to 8) + maxSpreadMeters (up to 2000 m) → each weighted 50%
 * POLICE_DETECTION: uses the "confidence" field stored by PoliceReportingCoordinator
 * BLE_CORRELATION:  fixed moderate score
 * ALPR_PROXIMITY:   fixed moderate-high score
 */
private fun computeConfidence(event: ThreatEvent): Float {
    return try {
        val detail = JSONObject(event.detailJson)
        when (event.type) {
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
            else              -> 0.5f
        }
    } catch (e: org.json.JSONException) { AppLog.w("ThreatHistoryScreen", "computeConfidence JSON parse failed: ${e.message}"); 0.5f }
}

/**
 * Derives a [ThreatLevel] from the group's temporal/spatial properties.
 *
 * HIGH:   tracking span > 5 min AND (4+ stops OR spread ≥ 500 m), or very high confidence
 * MEDIUM: more than one sighting, or moderate confidence
 * LOW:    single or sparse sightings
 */
private fun computeThreatLevel(
    events: List<ThreatEvent>,
    firstSeen: Long,
    lastSeen: Long,
    maxConfidence: Float
): ThreatLevel {
    val durationMs = lastSeen - firstSeen

    // Extract stopCount / maxSpreadMeters from the first TRACKER event if present
    val trackerEvent = events.firstOrNull { it.type == "TRACKER" }
    val detail = try {
        JSONObject((trackerEvent ?: events.first()).detailJson)
    } catch (e: org.json.JSONException) { AppLog.w("ThreatHistoryScreen", "computeThreatLevel JSON parse failed: ${e.message}"); JSONObject() }
    val stopCount = detail.optInt("stopCount", 0)
    val maxSpread = detail.optDouble("maxSpreadMeters", 0.0)

    return when {
        // HIGH: device followed user across time + distance
        (durationMs > 5 * 60_000L && (stopCount >= 4 || maxSpread >= 500.0)) ||
        (maxConfidence >= 0.8f && durationMs > 2 * 60_000L) -> ThreatLevel.HIGH
        // MEDIUM: repeated sightings or moderate confidence
        events.size > 1 || maxConfidence >= 0.5f            -> ThreatLevel.MEDIUM
        // LOW: isolated / low-confidence sighting
        else                                                 -> ThreatLevel.LOW
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

/**
 * Chronological (newest-first) feed of all threat events stored in the DB.
 * Local-only - never synced to P2P.
 *
 * Events are grouped by device identifier (MAC/BSSID) within 10-minute windows.
 * Each card shows sighting count, threat level badge, and confidence bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreatHistoryScreen(
    onBack: () -> Unit,
    onViewTrackerDetail: ((ThreatEvent) -> Unit)? = null,
    onIgnore: ((String) -> Unit)? = null,
    onIgnoreMac: ((String) -> Unit)? = null,
    onWatchlistAdd: ((String) -> Unit)? = null,
    viewModel: MainViewModel = hiltViewModel()
) {
    val allEvents by viewModel.threatEvents.collectAsStateWithLifecycle()
    // Police sightings are shown in Report History, not here
    val events = remember(allEvents) { allEvents.filter { it.type != "POLICE_DETECTION" && it.type != "MAC_LEAK" } }
    val groupedEvents = remember(events) { groupThreatEvents(events) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy  HH:mm:ss", Locale.US) }
    val shortFmt  = remember { SimpleDateFormat("MMM d HH:mm:ss", Locale.US) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Threat History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (groupedEvents.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🛡️", fontSize = 48.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("No threats detected yet", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Alerts will appear here when potential trackers or threats are found.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        "${groupedEvents.size} alert group(s)  •  ${events.size} total event(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(groupedEvents, key = { "${it.deviceIdentifier}_${it.firstSeen}" }) { group ->
                    GroupedThreatEventCard(
                        group = group,
                        dateFormat = dateFormat,
                        shortFmt = shortFmt,
                        onViewDetail = if (onViewTrackerDetail != null) {
                            { onViewTrackerDetail(group.representativeEvent) }
                        } else null,
                        onIgnore = if (onIgnore != null && group.deviceIdentifier != "unknown") {
                            { onIgnore(group.deviceIdentifier) }
                        } else null,
                        onIgnoreMac = onIgnoreMac,
                        onWatchlistAdd = onWatchlistAdd
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

// ── Grouped card ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun GroupedThreatEventCard(
    group: GroupedThreatEvent,
    dateFormat: SimpleDateFormat,
    shortFmt: SimpleDateFormat,
    @Suppress("UNUSED_PARAMETER") onViewDetail: (() -> Unit)?,
    onIgnore: (() -> Unit)? = null,
    onIgnoreMac: ((String) -> Unit)? = null,
    onWatchlistAdd: ((String) -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val event = group.representativeEvent

    val (icon, baseLabel, accentColor, containerColor) = when (event.type) {
        "TRACKER"          -> Quad("📡", "Possible Tracker",           Color(0xFFE53935), Color(0x1FE53935))
        "BLE_CORRELATION"  -> Quad("🔵", "BLE Correlation",            Color(0xFF2196F3), Color(0x1F2196F3))
        "ALPR_PROXIMITY"   -> Quad("📷", "ALPR Camera",                Color(0xFFFF9800), Color(0x1FFF9800))
        "POLICE_DETECTION" -> Quad("👮", "Law Enforcement Equipment",  Color(0xFF1565C0), Color(0x1F1565C0))
        "CONVOY"           -> Quad("🚗", "Coordinated Movement",       Color(0xFF7B1FA2), Color(0x1F7B1FA2))
        "COORDINATED"      -> Quad("👥", "Coordinated Surveillance",   Color(0xFFE65100), Color(0x1FE65100))
        "WATCHLIST"        -> Quad("🔔", "Watched Device Alert",       Color(0xFFF9A825), Color(0x1FF9A825))
        else               -> Quad("⚠️", event.type,                   Color(0xFF9E9E9E), Color(0x1F9E9E9E))
    }

    // Parse detailJson from the representative event
    val detail = remember(event.detailJson) {
        try { JSONObject(event.detailJson) } catch (e: org.json.JSONException) { AppLog.w("ThreatHistoryScreen", "Failed to parse event detailJson: ${e.message}"); JSONObject() }
    }

    val mac          = group.deviceIdentifier.ifBlank { detail.optString("mac", "").ifBlank { "-" } }
    val manufacturer = detail.optString("manufacturer", "").ifBlank { null }
    val ssid         = detail.optString("ssid", "").ifBlank { null }
    val alertId      = detail.optString("alertId", "").ifBlank { null }
    val lastRssi     = if (detail.isNull("lastRssi")) null else detail.optInt("lastRssi")
    // Phase 2B: distance (2.5) and baseline anomaly (2.4)
    val estimatedDistM    = if (detail.isNull("estimatedDistanceMeters")) null
                            else detail.optDouble("estimatedDistanceMeters").takeIf { it > 0 }
    val distanceTrend     = detail.optString("distanceTrend", "").ifBlank { null }
    val isBaselineAnomaly = detail.optBoolean("isBaselineAnomaly", false)
    val anomalyType       = detail.optString("baselineAnomalyType", "").ifBlank { null }
    // Phase 2B: police distance (2.5)
    val policeDistM       = if (event.type == "POLICE_DETECTION")
        detail.optDouble("estimated_distance_meters", -1.0).takeIf { it > 0 } else null

    val rssiTrend = remember(event.detailJson) {
        try {
            val arr = detail.optJSONArray("rssiTrend") ?: return@remember emptyList<Int>()
            (0 until arr.length()).map { arr.getInt(it) }
        } catch (e: org.json.JSONException) { AppLog.w("ThreatHistoryScreen", "Failed to parse rssiTrend: ${e.message}"); emptyList() }
    }
    val sightings = remember(event.detailJson) {
        try {
            val arr = detail.optJSONArray("sightings") ?: return@remember emptyList<SightingRow>()
            (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                SightingRow(
                    timestamp = s.optLong("timestamp", 0L),
                    lat       = s.optDouble("lat", 0.0),
                    lon       = s.optDouble("lon", 0.0),
                    rssi      = if (s.isNull("rssi")) null else s.optInt("rssi")
                )
            }
        } catch (e: org.json.JSONException) { AppLog.w("ThreatHistoryScreen", "JSON parse failed: ${e.message}"); emptyList() }
    }

    // Police-specific fields
    val policeCategory = if (event.type == "POLICE_DETECTION")
        detail.optString("category", "").ifBlank { null } else null

    // Dynamic label: police shows category
    val label = when {
        policeCategory != null -> policeCategory
            .replace("_", " ")
            .lowercase()
            .replaceFirstChar { it.uppercase() }
        else -> baseLabel
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        onClick = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {

            // ── Header row ────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 26.sp, modifier = Modifier.padding(end = 10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        ThreatLevelBadge(group.threatLevel)
                    }
                    Text(
                        dateFormat.format(Date(group.lastSeen)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Phase 3 — Feature 3.9: Ignore button
                if (onIgnore != null) {
                    var showConfirm by remember { mutableStateOf(false) }
                    if (showConfirm) {
                        AlertDialog(
                            onDismissRequest = { showConfirm = false },
                            title = { Text("Ignore device?") },
                            text = {
                                Text(
                                    "This device (${group.deviceIdentifier}) will be permanently " +
                                    "ignored and won't trigger future alerts."
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showConfirm = false
                                    onIgnore()
                                }) { Text("Ignore permanently") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
                            }
                        )
                    }
                    IconButton(onClick = { showConfirm = true }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Ignore this device permanently",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── Grouping summary row ──────────────────────────────────────────
            if (group.sightingCount > 1) {
                val durationMs = group.lastSeen - group.firstSeen
                val durationStr = formatDuration(durationMs / 1000L)
                Text(
                    "Seen ${group.sightingCount} times in $durationStr  " +
                    "(${shortFmt.format(Date(group.firstSeen))} → ${shortFmt.format(Date(group.lastSeen))})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── Confidence bar ────────────────────────────────────────────────
            ConfidenceBar(confidence = group.confidence, accentColor = accentColor)

            // ── Always-visible summary ────────────────────────────────────────
            if (mac != "-" && mac != "unknown") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        mac,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = accentColor
                    )
                    if (lastRssi != null) {
                        val rssiColor = when {
                            lastRssi >= -60 -> Color(0xFFE53935)
                            lastRssi >= -75 -> Color(0xFFFFB300)
                            else            -> Color.Gray
                        }
                        // Phase 3 — Feature 3.14: RSSI signal quality %
                        val rssiQuality = (2 * (lastRssi + 100)).coerceIn(0, 100)
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "$lastRssi dBm",
                                style = MaterialTheme.typography.labelSmall,
                                color = rssiColor
                            )
                            Text(
                                "Signal: $rssiQuality%",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = rssiColor.copy(alpha = 0.75f)
                            )
                        }
                    }
                }
            }
            manufacturer?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }

            // Phase 2B: distance badge (2.5) + baseline anomaly indicator (2.4)
            val displayDist = estimatedDistM ?: policeDistM
            if (displayDist != null || isBaselineAnomaly) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    displayDist?.let { dm ->
                        val distColor = when {
                            dm < 20  -> Color(0xFFE53935)
                            dm < 60  -> Color(0xFFFFB300)
                            else     -> Color(0xFF757575)
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = distColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                "📏 ${"%.0f".format(dm)} m${distanceTrend?.let { " $it" } ?: ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = distColor,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (isBaselineAnomaly) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFFFF6F00).copy(alpha = 0.15f)
                        ) {
                            Text(
                                "⚡ ${anomalyType?.replace("_", " ") ?: "ANOMALY"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFF6F00),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // ── Expanded detail section ───────────────────────────────────────
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = accentColor.copy(alpha = 0.3f))

                // Confidence detail
                DetailRow("Confidence", "${(group.confidence * 100).toInt()}%", valueColor = accentColor)
                DetailRow("Threat level", group.threatLevel.name, valueColor = when (group.threatLevel) {
                    ThreatLevel.HIGH   -> Color(0xFFE53935)
                    ThreatLevel.MEDIUM -> Color(0xFFFFB300)
                    ThreatLevel.LOW    -> Color.Gray
                })

                // Group size summary
                if (group.sightingCount > 1) {
                    DetailRow("Events merged", "${group.sightingCount}")
                    DetailRow("First seen", shortFmt.format(Date(group.firstSeen)))
                    DetailRow("Last seen",  shortFmt.format(Date(group.lastSeen)))
                    val durationSec = (group.lastSeen - group.firstSeen) / 1000L
                    DetailRow("Tracking span", formatDuration(durationSec))
                }

                // SSID
                ssid?.let {
                    DetailRow("SSID", it, valueColor = Color(0xFF4FC3F7))
                }

                // Sighting locations from detailJson
                if (sightings.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "📍 ${sightings.size} sighting location(s)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    sightings.forEachIndexed { idx, s ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "#${idx + 1}  ${String.format("%.4f, %.4f", s.lat, s.lon)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                s.rssi?.let { rssi ->
                                    val rssiColor = when {
                                        rssi >= -60 -> Color(0xFFE53935)
                                        rssi >= -75 -> Color(0xFFFFB300)
                                        else        -> Color.Gray
                                    }
                                    Text("$rssi dBm", style = MaterialTheme.typography.labelSmall, color = rssiColor)
                                }
                                if (s.timestamp > 0L) {
                                    Text(
                                        shortFmt.format(Date(s.timestamp)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }
                }

                // Phase 3 — Feature 3.14: RSSI quality % in expanded detail
                lastRssi?.let { rssi ->
                    val quality = (2 * (rssi + 100)).coerceIn(0, 100)
                    DetailRow("Signal quality", "$quality%")
                }

                // RSSI trend
                if (rssiTrend.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "📶 Signal trend (dBm)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    RssiTrendBar(rssiTrend, accentColor)
                    Text(
                        rssiTrend.joinToString(" → "),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Gray
                    )
                }

                // Police-specific detail
                if (event.type == "POLICE_DETECTION") {
                    val policeLat  = detail.optDouble("lat",  Double.NaN).takeIf { !it.isNaN() }
                    val policeLon  = detail.optDouble("lon",  Double.NaN).takeIf { !it.isNaN() }
                    val policeConf = detail.optDouble("confidence", Double.NaN).takeIf { !it.isNaN() }
                    Spacer(Modifier.height(4.dp))
                    if (policeLat != null && policeLon != null) {
                        DetailRow("Coordinates", String.format("%.5f, %.5f", policeLat, policeLon))
                    }
                    policeCategory?.let { DetailRow("Equipment type", it.replace("_", " ").lowercase()
                        .replaceFirstChar { c -> c.uppercase() }) }
                    policeConf?.let { DetailRow("Detection confidence", "${(it * 100).toInt()}%") }
                }

                // Phase 2B: distance (2.5)
                val expandedDist = estimatedDistM ?: policeDistM
                if (expandedDist != null) {
                    Spacer(Modifier.height(4.dp))
                    DetailRow("Est. distance", "${"%.1f".format(expandedDist)} m")
                    distanceTrend?.let { DetailRow("Signal trend", it) }
                }

                // Phase 2B: baseline anomaly (2.4)
                if (isBaselineAnomaly) {
                    Spacer(Modifier.height(4.dp))
                    DetailRow(
                        "Baseline anomaly",
                        anomalyType?.replace("_", " ") ?: "Detected",
                        valueColor = Color(0xFFFF6F00)
                    )
                }

                // TRACKER-specific: per-MAC long-press to ignore or watch
                if (event.type == "TRACKER" && mac != "-" && mac != "unknown") {
                    Spacer(Modifier.height(4.dp))
                    var showTrackerMacDialog by remember { mutableStateOf(false) }
                    if (showTrackerMacDialog) {
                        AlertDialog(
                            onDismissRequest = { showTrackerMacDialog = false },
                            title = { Text("Device options") },
                            text = { Text("Choose an action for $mac") },
                            confirmButton = {
                                TextButton(onClick = {
                                    showTrackerMacDialog = false
                                    onIgnoreMac?.invoke(mac)
                                }) { Text("🚫 Ignore") }
                            },
                            dismissButton = {
                                Row {
                                    TextButton(onClick = {
                                        showTrackerMacDialog = false
                                        onWatchlistAdd?.invoke(mac)
                                    }) { Text("🔔 Always alert") }
                                    TextButton(onClick = { showTrackerMacDialog = false }) {
                                        Text("Cancel")
                                    }
                                }
                            }
                        )
                    }
                    Text(
                        "Long-press MAC to ignore or watch:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        mac,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = accentColor,
                        modifier = Modifier
                            .padding(start = 8.dp, top = 2.dp)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { showTrackerMacDialog = true }
                            )
                    )
                }

                // WATCHLIST-specific: show watchlist label + sightings
                if (event.type == "WATCHLIST") {
                    val watchlistLabel = detail.optString("watchlistLabel", "").ifBlank { null }
                    watchlistLabel?.let { DetailRow("Watch label", it, valueColor = Color(0xFFF9A825)) }
                    if (sightings.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "📍 ${sightings.size} sighting(s) in this window",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        sightings.forEachIndexed { idx, s ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 8.dp, top = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "#${idx + 1}  ${String.format("%.4f, %.4f", s.lat, s.lon)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    s.rssi?.let { rssi ->
                                        val rssiColor = when {
                                            rssi >= -60 -> Color(0xFFE53935)
                                            rssi >= -75 -> Color(0xFFFFB300)
                                            else        -> Color.Gray
                                        }
                                        Text("$rssi dBm", style = MaterialTheme.typography.labelSmall, color = rssiColor)
                                    }
                                    if (s.timestamp > 0L) {
                                        Text(
                                            shortFmt.format(Date(s.timestamp)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.Gray
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Convoy-specific detail
                if (event.type == "CONVOY") {
                    val convoyMacs = remember(event.detailJson) {
                        try {
                            val arr = detail.optJSONArray("deviceGroup") ?: return@remember emptyList<String>()
                            (0 until arr.length()).map { arr.getString(it) }
                        } catch (e: org.json.JSONException) { AppLog.w("ThreatHistoryScreen", "JSON parse failed: ${e.message}"); emptyList() }
                    }
                    val speedMph = detail.optInt("speedMph", 0)
                    val bearing  = detail.optDouble("avgBearingDeg", -1.0).takeIf { it >= 0 }
                    val vectors  = detail.optInt("correlatedVectors", 0)
                    if (convoyMacs.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Devices in group:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        convoyMacs.forEach { mac ->
                            var showIgnoreDialog by remember { mutableStateOf(false) }
                            if (showIgnoreDialog) {
                                AlertDialog(
                                    onDismissRequest = { showIgnoreDialog = false },
                                    title = { Text("Device options") },
                                    text = { Text("Choose an action for $mac") },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            showIgnoreDialog = false
                                            onIgnoreMac?.invoke(mac)
                                        }) { Text("🚫 Ignore") }
                                    },
                                    dismissButton = {
                                        Row {
                                            TextButton(onClick = {
                                                showIgnoreDialog = false
                                                onWatchlistAdd?.invoke(mac)
                                            }) { Text("🔔 Always alert") }
                                            TextButton(onClick = { showIgnoreDialog = false }) {
                                                Text("Cancel")
                                            }
                                        }
                                    }
                                )
                            }
                            Text(
                                mac,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = accentColor,
                                modifier = Modifier
                                    .padding(start = 8.dp, top = 2.dp)
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = { showIgnoreDialog = true }
                                    )
                            )
                        }
                    }
                    if (speedMph > 0) DetailRow("Speed", "~${speedMph} mph")
                    bearing?.let { DetailRow("Avg bearing", "${it.toInt()}°") }
                    if (vectors > 0) DetailRow("Correlated vectors", "$vectors")
                }

                // Coordinated-specific detail
                if (event.type == "COORDINATED") {
                    val coordinatedMacs = remember(event.detailJson) {
                        try {
                            val arr = detail.optJSONArray("deviceGroup") ?: return@remember emptyList<String>()
                            (0 until arr.length()).map { arr.getString(it) }
                        } catch (e: org.json.JSONException) { AppLog.w("ThreatHistoryScreen", "JSON parse failed: ${e.message}"); emptyList() }
                    }
                    val sharedCount      = detail.optJSONArray("sharedCells")?.length() ?: 0
                    val correlationScore = detail.optDouble("correlationScore", -1.0).takeIf { it >= 0 }
                    if (coordinatedMacs.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Devices in group:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        coordinatedMacs.forEach { mac ->
                            var showIgnoreDialog by remember { mutableStateOf(false) }
                            if (showIgnoreDialog) {
                                AlertDialog(
                                    onDismissRequest = { showIgnoreDialog = false },
                                    title = { Text("Device options") },
                                    text = { Text("Choose an action for $mac") },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            showIgnoreDialog = false
                                            onIgnoreMac?.invoke(mac)
                                        }) { Text("🚫 Ignore") }
                                    },
                                    dismissButton = {
                                        Row {
                                            TextButton(onClick = {
                                                showIgnoreDialog = false
                                                onWatchlistAdd?.invoke(mac)
                                            }) { Text("🔔 Always alert") }
                                            TextButton(onClick = { showIgnoreDialog = false }) {
                                                Text("Cancel")
                                            }
                                        }
                                    }
                                )
                            }
                            Text(
                                mac,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = accentColor,
                                modifier = Modifier
                                    .padding(start = 8.dp, top = 2.dp)
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = { showIgnoreDialog = true }
                                    )
                            )
                        }
                    }
                    if (sharedCount > 0) DetailRow("Shared locations", "$sharedCount")
                    correlationScore?.let {
                        DetailRow("Correlation score", "${(it * 100).toInt()}%")
                    }
                }

                // Alert ID + type tag
                alertId?.let {
                    Spacer(Modifier.height(4.dp))
                    DetailRow(
                        "Alert ID",
                        it.take(16) + if (it.length > 16) "…" else "",
                        valueColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                DetailRow("DB id", "#${event.id}")
                DetailRow("Type", event.type)
            }
        }
    }
}

// ── Threat level badge ────────────────────────────────────────────────────────

@Composable
private fun ThreatLevelBadge(level: ThreatLevel) {
    val (text, bgColor, textColor) = when (level) {
        ThreatLevel.HIGH   -> Triple("HIGH",   Color(0xFFE53935), Color.White)
        ThreatLevel.MEDIUM -> Triple("MED",    Color(0xFFFFB300), Color(0xFF1A1A1A))
        ThreatLevel.LOW    -> Triple("LOW",    Color(0xFF757575), Color.White)
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = bgColor,
        modifier = Modifier.height(18.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 5.dp)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }
    }
}

// ── Confidence bar ────────────────────────────────────────────────────────────

@Composable
private fun ConfidenceBar(confidence: Float, accentColor: Color) {
    val pct = (confidence * 100).toInt()
    val barColor = when {
        confidence >= 0.75f -> Color(0xFFE53935)
        confidence >= 0.5f  -> Color(0xFFFFB300)
        else                -> Color(0xFF757575)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Confidence",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "$pct%",
                style = MaterialTheme.typography.labelSmall,
                color = barColor,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(
                    accentColor.copy(alpha = 0.15f),
                    RoundedCornerShape(2.dp)
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(confidence.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(barColor, RoundedCornerShape(2.dp))
            )
        }
    }
}

// ── RSSI trend mini bar chart ──────────────────────────────────────────────────

@Composable
private fun RssiTrendBar(trend: List<Int>, accentColor: Color) {
    val min = trend.min().coerceAtMost(-90)
    val max = trend.max().coerceAtLeast(-40)
    val range = (max - min).toFloat().coerceAtLeast(1f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        trend.forEach { rssi ->
            val frac = ((rssi - min) / range).coerceIn(0f, 1f)
            val barColor = when {
                rssi >= -60 -> Color(0xFFE53935)
                rssi >= -75 -> Color(0xFFFFB300)
                else        -> accentColor.copy(alpha = 0.6f)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(frac.coerceAtLeast(0.08f))
                    .background(barColor)
            )
        }
    }
}

// ── Small helpers ─────────────────────────────────────────────────────────────

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(value, style = MaterialTheme.typography.labelSmall, color = valueColor)
    }
}

private fun formatDuration(seconds: Long): String = when {
    seconds < 60   -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else           -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

private data class SightingRow(val timestamp: Long, val lat: Double, val lon: Double, val rssi: Int?)

/** Convenience destructure for (icon, label, accentColor, containerColor). */
private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
