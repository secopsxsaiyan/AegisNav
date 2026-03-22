package com.aegisnav.app.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegisnav.app.AppLifecycleTracker
import com.aegisnav.app.police.PoliceSighting
import com.aegisnav.app.signal.SignalTriangulator
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

private sealed interface DetectionPopupItem {
    data class PoliceSightingItem(val sighting: PoliceSighting) : DetectionPopupItem
    data class PoliceProximityItem(val result: SignalTriangulator.TriangulationResult) : DetectionPopupItem
    data class AlprProximityItem(val result: SignalTriangulator.TriangulationResult) : DetectionPopupItem
}

/** Returns true if any locked officer unit contains this MAC in its macSet. */
private fun isLockedByUnit(mac: String, officerUnits: List<com.aegisnav.app.police.OfficerUnit>): Boolean {
    val upperMac = mac.uppercase()
    return officerUnits.any { unit ->
        unit.verdictLocked && unit.macSet.uppercase().split(",").any { it.trim() == upperMac }
    }
}

@Composable
fun DetectionPopupHost(
    latestPoliceSighting: PoliceSighting?,
    triangulationResults: Collection<SignalTriangulator.TriangulationResult>,
    userLat: Double,
    userLon: Double,
    onConfirmPolice: (String, String?) -> Unit,
    onDismissPolice: (String, String?) -> Unit,
    onConfirmAlpr: (mac: String, camLat: Double, camLon: Double, camDesc: String) -> Unit,
    onDismissAlpr: (String) -> Unit,
    onExpirePolice: (String) -> Unit = {},
    reviewedTriangulationMacs: Set<String> = emptySet(),
    onReviewedTriangulationMacsChange: (Set<String>) -> Unit = {},
    officerUnits: List<com.aegisnav.app.police.OfficerUnit> = emptyList(),
    modifier: Modifier = Modifier
) {
    var activeItem by remember { mutableStateOf<DetectionPopupItem?>(null) }
    var lastHapticMs by remember { mutableLongStateOf(0L) }
    var expiredSightingIds by remember { mutableStateOf(setOf<String>()) }
    var expiredTriMacs by remember { mutableStateOf(setOf<String>()) }
    val context = LocalContext.current

    // Background queuing: track items that arrived while the app was backgrounded
    var backgroundQueuedCount by remember { mutableStateOf(0) }
    var wasInBackground by remember { mutableStateOf(false) }

    // Determine next item to show (priority: police detection > police proximity > ALPR proximity)
    fun nextItem(): DetectionPopupItem? {
        // 1. Police sighting (new scan detection) — only show if DB verdict is still null
        if (latestPoliceSighting != null && latestPoliceSighting.userVerdict == null &&
                latestPoliceSighting.id !in expiredSightingIds) {
            // Skip if officer unit has a locked verdict
            val unitId = latestPoliceSighting.officerUnitId
            val unit = if (unitId != null) officerUnits.firstOrNull { it.unitId == unitId } else null
            if (unit?.verdictLocked != true) {
                return DetectionPopupItem.PoliceSightingItem(latestPoliceSighting)
            }
        }
        // 2. POLICE proximity (triangulation markers within 100m)
        val nearPolice = triangulationResults.firstOrNull { r ->
            r.deviceCategory == "POLICE" &&
                    r.mac !in reviewedTriangulationMacs &&
                    r.mac !in expiredTriMacs &&
                    haversineDistanceMeters(userLat, userLon, r.estimatedLat, r.estimatedLon) <= 100.0 &&
                    !isLockedByUnit(r.mac, officerUnits)
        }
        if (nearPolice != null) return DetectionPopupItem.PoliceProximityItem(nearPolice)
        // 3. ALPR proximity (triangulation markers within 100m)
        val nearAlpr = triangulationResults.firstOrNull { r ->
            r.deviceCategory == "ALPR" &&
                    r.mac !in reviewedTriangulationMacs &&
                    r.mac !in expiredTriMacs &&
                    haversineDistanceMeters(userLat, userLon, r.estimatedLat, r.estimatedLon) <= 100.0
        }
        return nearAlpr?.let { DetectionPopupItem.AlprProximityItem(it) }
    }

    // Whenever the sighting or results change, surface the next unreviewed item.
    // Use latestPoliceSighting?.id as key (not the full object) to avoid re-queuing when
    // the same sighting's DB row updates (e.g. grid consolidation updating timestamp).
    LaunchedEffect(latestPoliceSighting?.id, triangulationResults, userLat, userLon) {
        val inForeground = AppLifecycleTracker.isInForeground

        if (!inForeground) {
            // App is backgrounded — queue instead of showing
            val next = nextItem()
            if (next != null) {
                backgroundQueuedCount++
                wasInBackground = true
            }
            return@LaunchedEffect
        }

        if (activeItem == null) {
            val next = nextItem()
            if (next != null) {
                // Fire haptic if cooldown has elapsed (5-minute cooldown)
                val now = System.currentTimeMillis()
                if (now - lastHapticMs >= 5 * 60_000L) {
                    lastHapticMs = now
                    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(200)
                    }
                }
            }
            activeItem = next
        }
    }

    // When app returns to foreground after being backgrounded, surface the next alert.
    // The backgroundQueuedCount is included in queueCount so the user sees the backlog.
    LaunchedEffect(AppLifecycleTracker.isInForeground) {
        if (AppLifecycleTracker.isInForeground && wasInBackground) {
            wasInBackground = false
            // Small delay to let Compose settle after foreground transition
            kotlinx.coroutines.delay(300L)
            if (activeItem == null) {
                activeItem = nextItem()
            }
        }
    }

    // Auto-dismiss after 15s.
    LaunchedEffect(activeItem) {
        val current = activeItem ?: return@LaunchedEffect
        kotlinx.coroutines.delay(15_000L)
        if (activeItem == current) {
            // Add to local expired sets FIRST — prevents GPS re-queue race before DB propagates
            when (current) {
                is DetectionPopupItem.PoliceSightingItem -> {
                    expiredSightingIds = expiredSightingIds + current.sighting.id
                    onExpirePolice(current.sighting.id)
                }
                is DetectionPopupItem.PoliceProximityItem -> {
                    expiredTriMacs = expiredTriMacs + current.result.mac
                    onReviewedTriangulationMacsChange(reviewedTriangulationMacs + current.result.mac)
                }
                is DetectionPopupItem.AlprProximityItem -> {
                    expiredTriMacs = expiredTriMacs + current.result.mac
                    onReviewedTriangulationMacsChange(reviewedTriangulationMacs + current.result.mac)
                }
            }
            // Clear without immediately re-queuing — let the data-watching
            // LaunchedEffect surface the next item once DB/state propagates
            activeItem = null
        }
    }

    val item = activeItem ?: return

    val dismiss: () -> Unit = {
        // Add to local expired sets to prevent re-queue before DB/state propagates
        when (item) {
            is DetectionPopupItem.PoliceSightingItem -> {
                expiredSightingIds = expiredSightingIds + item.sighting.id
            }
            is DetectionPopupItem.PoliceProximityItem -> {
                expiredTriMacs = expiredTriMacs + item.result.mac
                onReviewedTriangulationMacsChange(reviewedTriangulationMacs + item.result.mac)
            }
            is DetectionPopupItem.AlprProximityItem -> {
                expiredTriMacs = expiredTriMacs + item.result.mac
                onReviewedTriangulationMacsChange(reviewedTriangulationMacs + item.result.mac)
            }
        }
        // Decrement background queue count if applicable
        if (backgroundQueuedCount > 0) backgroundQueuedCount--
        // Clear without immediately re-queuing — let the data-watching
        // LaunchedEffect surface the next item once DB/state propagates
        activeItem = null
    }

    // Compute how many additional unreviewed items are pending (excluding the current one)
    val pendingSightings = if (latestPoliceSighting != null && latestPoliceSighting.userVerdict == null &&
        latestPoliceSighting.id !in expiredSightingIds &&
        (item !is DetectionPopupItem.PoliceSightingItem || item.sighting.id != latestPoliceSighting.id)) 1 else 0
    val pendingTriangulations = triangulationResults.count { r ->
        r.mac !in reviewedTriangulationMacs &&
        r.mac !in expiredTriMacs &&
        haversineDistanceMeters(userLat, userLon, r.estimatedLat, r.estimatedLon) <= 100.0 &&
        when (item) {
            is DetectionPopupItem.PoliceProximityItem -> item.result.mac != r.mac
            is DetectionPopupItem.AlprProximityItem -> item.result.mac != r.mac
            else -> true
        }
    }
    // Include any items that arrived while backgrounded (will be cleared as user dismisses)
    val queueCount = pendingSightings + pendingTriangulations +
            (if (wasInBackground) backgroundQueuedCount.coerceAtLeast(0) else 0)

    when (item) {
        is DetectionPopupItem.PoliceSightingItem -> {
            val sighting = item.sighting
            DetectionPopup(
                emoji = "🚔",
                title = "Police Equipment",
                subtitle = "${(sighting.confidence * 100).toInt()}% — ${sighting.detectionCategory}",
                queueCount = queueCount,
                modifier = modifier,
                onConfirm = {
                    onConfirmPolice(sighting.id, sighting.deviceMacs?.split(",")?.firstOrNull())
                    dismiss()
                },
                onDismiss = {
                    onDismissPolice(sighting.id, sighting.deviceMacs?.split(",")?.firstOrNull())
                    dismiss()
                }
            )
        }
        is DetectionPopupItem.PoliceProximityItem -> {
            val result = item.result
            DetectionPopup(
                emoji = "🚔",
                title = "Police Nearby",
                subtitle = "Triangulated signal ±${result.radiusMeters.toInt()}m",
                queueCount = queueCount,
                modifier = modifier,
                onConfirm = {
                    onConfirmPolice(result.mac, result.mac)
                    dismiss()
                },
                onDismiss = {
                    onDismissPolice(result.mac, result.mac)
                    dismiss()
                }
            )
        }
        is DetectionPopupItem.AlprProximityItem -> {
            val result = item.result
            DetectionPopup(
                emoji = "📷",
                title = "ALPR Detected",
                subtitle = "Triangulated signal ±${result.radiusMeters.toInt()}m",
                queueCount = queueCount,
                modifier = modifier,
                onConfirm = {
                    onConfirmAlpr(
                        result.mac,
                        result.estimatedLat,
                        result.estimatedLon,
                        "Detected ALPR Camera (±${result.radiusMeters.toInt()}m)"
                    )
                    dismiss()
                },
                onDismiss = {
                    onDismissAlpr(result.mac)
                    dismiss()
                }
            )
        }
    }
}

@Composable
private fun DetectionPopup(
    emoji: String,
    title: String,
    subtitle: String,
    queueCount: Int = 0,
    modifier: Modifier = Modifier,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(modifier = modifier.widthIn(max = 220.dp)) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(emoji, fontSize = 18.sp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF43A047))
                            .clickable(onClick = onConfirm),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✓", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE53935))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (queueCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 8.dp, y = (-8).dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "+$queueCount",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
