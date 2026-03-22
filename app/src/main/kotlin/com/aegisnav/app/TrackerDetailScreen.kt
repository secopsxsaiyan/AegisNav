package com.aegisnav.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegisnav.app.util.AppLog
import com.aegisnav.app.tracker.TrackerAlert
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.text.SimpleDateFormat
import java.util.*

/**
 * Detail screen for a TrackerAlert - shows mini-map, sighting trail,
 * signal trend chart, and action buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerDetailScreen(
    alert: TrackerAlert,
    onBack: () -> Unit,
    onIgnore: (String) -> Unit,
    onReport: (TrackerAlert) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.US) }
    redactMac(alert.mac)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tracker Detail") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Mini-map with sighting trail ──────────────────────────────────
            Text("Sighting Trail", fontWeight = FontWeight.Bold)
            TrackerMiniMap(
                alert = alert,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            )

            // ── Device info ───────────────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow("MAC Address", alert.mac)
                    InfoRow("Manufacturer", alert.manufacturer ?: "Unknown")
                    InfoRow("First Seen", dateFormat.format(Date(alert.firstSeen)))
                    InfoRow("Last Seen", dateFormat.format(Date(alert.lastSeen)))
                    InfoRow("Locations Observed", "${alert.sightings.size}")
                    val durationMin = ((alert.lastSeen - alert.firstSeen) / 60_000).toInt()
                    InfoRow("Tracking Duration", "$durationMin min")
                }
            }

            // ── Signal trend chart ────────────────────────────────────────────
            if (alert.rssiTrend.isNotEmpty()) {
                Text("Signal Strength Trend (RSSI)", fontWeight = FontWeight.Bold)
                RssiBarChart(
                    rssiValues = alert.rssiTrend,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                )
            }

            // ── Action buttons ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { onIgnore(alert.mac) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Ignore Device", fontSize = 12.sp)
                }
                Button(
                    onClick = { onReport(alert) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Report Tracker", fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Mini-map with MapLibre ────────────────────────────────────────────────────

/**
 * Reuses the shared [MapLibreMapView] composable for lifecycle management
 * instead of duplicating the MapView lifecycle pattern inline.
 */
@Composable
private fun TrackerMiniMap(alert: TrackerAlert, modifier: Modifier = Modifier) {
    MapLibreMapView(
        modifier = modifier,
        onMapReady = { map, _ ->
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isLogoEnabled = false
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isScrollGesturesEnabled = false
            map.uiSettings.isZoomGesturesEnabled = false
            map.getStyle { style -> renderSightingTrail(style, map, alert) }
        }
    )
}

private fun renderSightingTrail(style: Style, map: MapLibreMap, alert: TrackerAlert) {
    if (alert.sightings.isEmpty()) return

    val points = alert.sightings.map { Point.fromLngLat(it.lon, it.lat) }

    // Polyline trail
    val lineSourceId = "tracker-trail-line"
    val dotSourceId  = "tracker-trail-dots"

    if (style.getSource(lineSourceId) == null) {
        style.addSource(GeoJsonSource(lineSourceId,
            FeatureCollection.fromFeature(
                Feature.fromGeometry(LineString.fromLngLats(points))
            )
        ))
        style.addLayer(LineLayer("tracker-trail-line-layer", lineSourceId).withProperties(
            PropertyFactory.lineColor("#FF5722"),
            PropertyFactory.lineWidth(2.5f),
            PropertyFactory.lineOpacity(0.85f)
        ))
    }

    if (style.getSource(dotSourceId) == null) {
        style.addSource(GeoJsonSource(dotSourceId,
            FeatureCollection.fromFeatures(points.map { Feature.fromGeometry(it) })
        ))
        style.addLayer(CircleLayer("tracker-trail-dots-layer", dotSourceId).withProperties(
            PropertyFactory.circleColor("#FF5722"),
            PropertyFactory.circleRadius(5f),
            PropertyFactory.circleOpacity(0.9f),
            PropertyFactory.circleStrokeColor("#fff"),
            PropertyFactory.circleStrokeWidth(1f)
        ))
    }

    // Fit camera to sightings
    if (alert.sightings.size == 1) {
        val s = alert.sightings.first()
        map.cameraPosition = CameraPosition.Builder()
            .target(LatLng(s.lat, s.lon)).zoom(14.0).build()
    } else {
        val boundsBuilder = LatLngBounds.Builder()
        alert.sightings.forEach { boundsBuilder.include(LatLng(it.lat, it.lon)) }
        try {
            map.cameraPosition = CameraPosition.Builder()
                .target(boundsBuilder.build().center)
                .zoom(13.0)
                .build()
        } catch (e: Exception) { AppLog.w("TrackerDetailScreen", "Failed to set camera position: ${e.message}") }
    }
}

// ── Signal RSSI Bar Chart (Compose Canvas only) ───────────────────────────────

@Composable
private fun RssiBarChart(rssiValues: List<Int>, modifier: Modifier = Modifier) {
    val barColor = Color(0xFF00BCD4)
    val weakColor = Color(0xFFE53935)

    Canvas(modifier = modifier) {
        if (rssiValues.isEmpty()) return@Canvas
        val minRssi = rssiValues.min().toFloat()     // most negative = weakest
        val maxRssi = rssiValues.max().toFloat()     // least negative = strongest
        val range = (maxRssi - minRssi).coerceAtLeast(1f)

        val barCount = rssiValues.size
        val barWidth = (size.width / barCount) * 0.7f
        val gap = (size.width / barCount) * 0.3f

        rssiValues.forEachIndexed { index, rssi ->
            val normalised = (rssi.toFloat() - minRssi) / range   // 0=weak 1=strong
            val barHeight = size.height * normalised.coerceIn(0.05f, 1f)
            val x = index * (barWidth + gap) + gap / 2
            val color = if (normalised > 0.5f) barColor else weakColor
            drawLine(
                color = color,
                start = Offset(x + barWidth / 2, size.height),
                end   = Offset(x + barWidth / 2, size.height - barHeight),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        Text(value, fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodySmall)
    }
}

/** Redacts the last 3 octets of a MAC - delegates to shared MacUtils. */
fun redactMac(mac: String): String = com.aegisnav.app.util.MacUtils.redactMac(mac)
