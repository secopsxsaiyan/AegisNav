package com.aegisnav.app.routing

import android.location.Address
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

// ── Navigation FAB ─────────────────────────────────────────────────────────────

/**
 * Floating action button that opens/closes the navigation bottom sheet.
 * Place in the map Box composable.
 */
@Composable
fun NavigationFab(
    isNavigating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = if (isNavigating) MaterialTheme.colorScheme.tertiary
                         else MaterialTheme.colorScheme.primary
    ) {
        Icon(
            imageVector = if (isNavigating) Icons.Default.Close else Icons.Default.Place,
            contentDescription = if (isNavigating) "Stop Navigation" else "Navigate"
        )
    }
}

// ── Route Overview Card ───────────────────────────────────────────────────────

/**
 * Summary card shown after a route is calculated but before navigation starts.
 * Shows distance, estimated time, and camera count.
 * User taps "Go" to begin turn-by-turn navigation.
 */
@Composable
fun RouteOverviewCard(
    routeResult: RouteResult,
    cameraCount: Int,
    onStartNavigation: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1565C0).copy(alpha = 0.95f),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Route Overview",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                androidx.compose.material3.IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Distance
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatDistance(routeResult.distanceMeters),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Text(
                        text = "Distance",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // Time
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatDuration(routeResult.durationSeconds),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Text(
                        text = "Est. Time",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // Cameras
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$cameraCount",
                        color = if (cameraCount > 0) Color(0xFFFFB300) else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Text(
                        text = "Cameras",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                onClick = onStartNavigation,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF43A047)
                )
            ) {
                Text(
                    text = "Go",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color.White
                )
            }
        }
    }
}

// ── Snackbar helper ───────────────────────────────────────────────────────────

/**
 * Show routing-unavailable snackbar if graph is not loaded.
 * Call this once in a LaunchedEffect keyed on routingAvailable.
 */
@Composable
fun RoutingUnavailableSnackbar(
    snackbarHostState: SnackbarHostState,
    viewModel: NavigationViewModel = hiltViewModel()
) {
    val routingAvailable by viewModel.routingAvailable.collectAsStateWithLifecycle()
    var snackbarShown by remember { mutableStateOf(false) }

    LaunchedEffect(routingAvailable) {
        if (!routingAvailable && !snackbarShown) {
            snackbarShown = true
            snackbarHostState.showSnackbar(
                message = "Routing data not loaded - see Settings for setup instructions",
                duration = SnackbarDuration.Long
            )
        }
    }
}

// ── Distance helper ───────────────────────────────────────────────────────

/** Haversine distance in meters between two lat/lon pairs. Used to geo-rank search results. */
internal fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2).let { it * it }
    return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a))
}

// ── Geocoder helpers ──────────────────────────────────────────────────────────

internal fun buildAddressLine(addr: Address, fallback: String): String = buildString {
    if (!addr.thoroughfare.isNullOrBlank()) append(addr.thoroughfare)
    if (!addr.locality.isNullOrBlank()) { if (isNotEmpty()) append(", "); append(addr.locality) }
    if (!addr.adminArea.isNullOrBlank()) { if (isNotEmpty()) append(", "); append(addr.adminArea) }
    if (isEmpty()) append(addr.getAddressLine(0) ?: fallback)
}

// ── Formatters ────────────────────────────────────────────────────────────────

@androidx.annotation.VisibleForTesting
internal fun formatDistance(meters: Double): String {
    val feet = meters * 3.28084
    val miles = meters / 1609.344
    return when {
        miles >= 0.1 -> if (miles < 10) String.format("%.1f mi", miles) else "${miles.roundToInt()} mi"
        else -> "${feet.roundToInt()} ft"
    }
}

internal fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0  -> "${h}h ${m}m"
        m > 0  -> "${m} min"
        else   -> "<1 min"
    }
}
