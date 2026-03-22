package com.aegisnav.app.flock

import androidx.compose.foundation.Canvas
import com.aegisnav.app.util.AppLog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.*

/**
 * Flock-you screen - shows detected Flock Safety infrastructure nodes.
 * List only (Map tab removed - flock sightings now shown on main map).
 * Each item has a confidence bar, timestamp, location, and "View on Map" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlockScreen(
    onBack: () -> Unit,
    onReportFlock: (Double, Double) -> Unit,
    onViewOnMap: ((Double, Double) -> Unit)? = null,
    scanServiceRunning: Boolean = true,
    viewModel: FlockViewModel = hiltViewModel()
) {
    val sightings by viewModel.sightings.collectAsStateWithLifecycle()
    var selectedSighting by remember { mutableStateOf<FlockSighting?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Discovered Auto-Reported ALPR Cameras") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            FlockListContent(
                sightings = sightings,
                onItemTapped = { selectedSighting = it },
                onViewOnMap = onViewOnMap,
                scanServiceRunning = scanServiceRunning
            )
        }

        selectedSighting?.let { sighting ->
            FlockSightingDetailSheet(
                sighting = sighting,
                onDismiss = { selectedSighting = null },
                onReport = { s ->
                    selectedSighting = null
                    onReportFlock(s.lat, s.lon)
                    viewModel.markReported(s.id)
                }
            )
        }
    }
}

// ── List Content ──────────────────────────────────────────────────────────────

@Composable
private fun FlockListContent(
    sightings: List<FlockSighting>,
    onItemTapped: (FlockSighting) -> Unit,
    onViewOnMap: ((Double, Double) -> Unit)? = null,
    scanServiceRunning: Boolean = true
) {
    if (sightings.isEmpty()) {
        val scanStatus = if (scanServiceRunning)
            "Passive BLE + WiFi scanning is active."
        else
            "Scanning is paused - check permissions in Settings."
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No Flock Safety nodes detected yet.\n$scanStatus",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.US) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        items(sightings, key = { it.id }) { sighting ->
            FlockSightingListItem(
                sighting = sighting,
                dateFormat = dateFormat,
                onClick = { onItemTapped(sighting) },
                onViewOnMap = onViewOnMap
            )
        }
    }
}

@Composable
private fun FlockSightingListItem(
    sighting: FlockSighting,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit,
    onViewOnMap: ((Double, Double) -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📷 Flock Safety Node", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    dateFormat.format(Date(sighting.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            ConfidenceBar(confidence = sighting.confidence)

            Text(
                String.format("%.5f, %.5f", sighting.lat, sighting.lon),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (onViewOnMap != null) {
                TextButton(
                    onClick = { onViewOnMap(sighting.lat, sighting.lon) },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("View on Map")
                }
            }
        }
    }
}

@Composable
private fun ConfidenceBar(confidence: Float) {
    val barColor = when {
        confidence >= 0.75f -> Color(0xFFE53935)
        confidence >= 0.5f  -> Color(0xFFFF9800)
        else                -> Color(0xFF9E9E9E)
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(modifier = Modifier.weight(1f).height(8.dp)) {
            drawRect(Color(0xFFE0E0E0))
            drawRect(barColor, size = size.copy(width = size.width * confidence))
        }
        Text(
            "${(confidence * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = barColor
        )
    }
}

// ── Sighting Detail Bottom Sheet ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlockSightingDetailSheet(
    sighting: FlockSighting,
    onDismiss: () -> Unit,
    onReport: (FlockSighting) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d yyyy, HH:mm:ss", Locale.US) }
    val matchedSignals = remember(sighting.matchedSignals) {
        try {
            val arr = org.json.JSONArray(sighting.matchedSignals)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: org.json.JSONException) { AppLog.w("FlockScreen", "Failed to parse matchedSignals: ${e.message}"); emptyList() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Flock Safety Node Detected", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            ConfidenceBar(confidence = sighting.confidence)
            HorizontalDivider()
            DetailRow("Timestamp", dateFormat.format(Date(sighting.timestamp)))
            DetailRow("Location", String.format("%.5f, %.5f", sighting.lat, sighting.lon))
            DetailRow("Confidence", "${(sighting.confidence * 100).toInt()}%")
            if (matchedSignals.isNotEmpty()) {
                Text("Matched Signals", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                matchedSignals.forEach { signal ->
                    Text("• $signal",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onReport(sighting) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
            ) {
                Text("Report as ALPR Camera")
            }
        }
        } // Surface
    } // Dialog
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
    }
}
