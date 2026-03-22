package com.aegisnav.app.ui.alpr

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegisnav.app.data.model.ALPRBlocklist

/**
 * Bottom sheet shown when the user taps an ALPR camera on the map.
 *
 * Features:
 * - Source badge chip(s), color-matched to map circle color
 * - Distance from current user location
 * - "Report this camera" button - delegates to onReport
 * - "Ignore this camera" button - delegates to onIgnore
 */
@Composable
fun AlprDetailBottomSheet(
    camera: ALPRBlocklist,
    userLocation: Location?,
    onDismiss: () -> Unit,
    onReport: (ALPRBlocklist) -> Unit,
    onIgnore: (ALPRBlocklist) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // ── Header ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📷 ALPR Camera",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss")
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Description ──────────────────────────────────────────────────
            Text(
                text = camera.desc.ifBlank { "ALPR Camera" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            // ── Coordinates ──────────────────────────────────────────────────
            Text(
                text = "%.5f, %.5f".format(camera.lat, camera.lon),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(Modifier.height(12.dp))

            // ── Source badges ─────────────────────────────────────────────────
            val sources = camera.source.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Source:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                sources.forEach { src ->
                    SourceBadge(source = src)
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Distance ─────────────────────────────────────────────────────
            val distanceText = remember(userLocation, camera) {
                if (userLocation != null) {
                    val results = FloatArray(1)
                    Location.distanceBetween(
                        userLocation.latitude, userLocation.longitude,
                        camera.lat, camera.lon,
                        results
                    )
                    val distM = results[0]
                    if (distM < 1000f) {
                        "%.0f m away".format(distM)
                    } else {
                        "%.1f km away".format(distM / 1000f)
                    }
                } else {
                    "Location unavailable"
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "📍 $distanceText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Verified badge ────────────────────────────────────────────────
            if (camera.verified) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF43A047))
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Verified",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF43A047)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Action buttons ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { onReport(camera) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("📢 Report", fontSize = 13.sp)
                }

                Button(
                    onClick = { onIgnore(camera); onDismiss() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text("🚫 Ignore", fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(8.dp))
        }
        } // Surface
    } // Dialog
}

/**
 * A colored chip displaying a single source label.
 * Colors match the MapLibre CircleLayer expression in MainActivity.
 */
@Composable
fun SourceBadge(source: String) {
    val (bgColor, label) = when (source.uppercase()) {
        "OSM"    -> Color(0xFF2196F3) to "OSM"
        "EFF"    -> Color(0xFFFF9800) to "EFF"
        "ARCGIS" -> Color(0xFF9C27B0) to "ARCGIS"
        "USER"   -> Color(0xFF4CAF50) to "USER"
        else     -> Color(0xFFFFEB3B) to source  // multi-source or unknown → yellow
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
