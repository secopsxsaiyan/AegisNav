package com.aegisnav.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aegisnav.app.data.model.Report
import com.aegisnav.app.police.OfficerUnit
import com.aegisnav.app.police.PoliceSighting
import com.aegisnav.app.ui.ReportCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Report History Screen - shows all locally submitted reports, newest first.
 * Local-only in Phase 1; no backend sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportHistoryScreen(
    onBack: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val policeSightings by viewModel.policeSightings.collectAsStateWithLifecycle()
    val officerUnits by viewModel.officerUnits.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Report History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        val totalCount = reports.size + policeSightings.size
        if (totalCount == 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📋", fontSize = 48.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No reports yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Submitted reports will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                item {
                    Text(
                        "$totalCount report${if (totalCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                // Police detection sightings (from police_sightings table)
                if (policeSightings.isNotEmpty()) {
                    item {
                        Text(
                            "👮 Police Equipment Detections",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(policeSightings, key = { "police_${it.id}" }) { sighting ->
                        val unit = officerUnits.firstOrNull { it.unitId == sighting.officerUnitId }
                        // If this unit was EVER confirmed (confirmCount > 0), always show as confirmed
                        val effectiveVerdict = if (unit != null && unit.confirmCount > 0 && sighting.userVerdict != "dismissed") "confirmed" else sighting.userVerdict
                        val isExpired = sighting.verdictDeadlineMs > 0 && System.currentTimeMillis() > sighting.verdictDeadlineMs && effectiveVerdict == null

                        PoliceDetectionItem(
                            sighting = sighting,
                            officerUnit = unit,
                            effectiveVerdict = if (isExpired) "expired" else effectiveVerdict,
                            onConfirm = { viewModel.confirmPoliceSighting(sighting.id) },
                            onDismiss = { viewModel.dismissPoliceSighting(sighting.id, sighting.deviceMacs?.split(",")?.firstOrNull()) }
                        )
                    }
                }

                if (reports.isNotEmpty()) {
                    item {
                        Text(
                            "📋 User Reports",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                }
                items(reports, key = { it.id }) { report ->
                    ReportHistoryItem(
                        report = report,
                        onConfirm = { viewModel.confirmReport(report.id) },
                        onClear = { viewModel.dismissReport(report.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PoliceDetectionItem(
    sighting: PoliceSighting,
    officerUnit: OfficerUnit? = null,
    effectiveVerdict: String? = null,
    onConfirm: () -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    val dateStr = remember(sighting.timestamp) {
        SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
            .format(Date(sighting.timestamp))
    }

    val isDismissed = effectiveVerdict == "dismissed"
    val isExpired   = effectiveVerdict == "expired"
    val isConfirmed = effectiveVerdict == "confirmed"

    val cardAlpha = if (isDismissed || isExpired) 0.5f else 1f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(cardAlpha),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isDismissed -> MaterialTheme.colorScheme.surfaceVariant
                else        -> Color(0xFF1565C0).copy(alpha = 0.08f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Top row: emoji + category + confidence badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("👮", fontSize = 20.sp)
                Text(
                    text = sighting.detectionCategory ?: "Unknown",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    textDecoration = if (isDismissed) TextDecoration.LineThrough else TextDecoration.None,
                    modifier = Modifier.weight(1f)
                )
                // Confidence badge
                val confidence = sighting.confidence
                Box(
                    modifier = Modifier
                        .background(
                            when {
                                confidence >= 0.75f -> Color(0xFFE53935).copy(alpha = 0.15f)
                                confidence >= 0.5f  -> Color(0xFFFFB300).copy(alpha = 0.15f)
                                else                -> Color(0xFF9E9E9E).copy(alpha = 0.15f)
                            },
                            MaterialTheme.shapes.small
                        )
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "${(confidence * 100).toInt()}%",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            confidence >= 0.75f -> Color(0xFFE53935)
                            confidence >= 0.5f  -> Color(0xFFFFB300)
                            else                -> Color(0xFF9E9E9E)
                        }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Officer unit line
            if (officerUnit != null) {
                Text(
                    text = "Unit: ${officerUnit.unitId} · confirmed ${officerUnit.confirmCount} times",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Device count (if > 1)
            if (sighting.deviceCount > 1) {
                Text(
                    text = "${sighting.deviceCount} devices detected at this location",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    textDecoration = if (isDismissed) TextDecoration.LineThrough else TextDecoration.None
                )
            }

            // Timestamp
            Text(
                text = dateStr,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Location
            Text(
                text = String.format("%.5f, %.5f", sighting.lat, sighting.lon),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(6.dp))

            // Verdict display
            when {
                isConfirmed -> {
                    Text(
                        text = "✅ Confirmed",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF43A047)
                    )
                }
                isDismissed -> {
                    // No buttons, card is already dimmed + strikethrough via cardAlpha / TextDecoration above
                }
                isExpired -> {
                    Text(
                        text = "Unconfirmed",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    // effectiveVerdict == null — show active buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onConfirm) {
                            Text("✓ Confirm", color = Color(0xFF43A047))
                        }
                        TextButton(onClick = onDismiss) {
                            Text("✗ Dismiss", color = Color(0xFFE53935))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportHistoryItem(
    report: Report,
    onConfirm: (Int) -> Unit = {},
    onClear: (Int) -> Unit = {}
) {
    val category = ReportCategory.entries.firstOrNull { it.dbType == report.type }
    val dateStr  = remember(report.timestamp) {
        SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
            .format(Date(report.timestamp))
    }
    val threatColor = when (report.threatLevel) {
        "HIGH"   -> Color(0xFFE53935)
        "MEDIUM" -> Color(0xFFFFB300)
        else     -> Color(0xFF43A047)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Category emoji badge
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        MaterialTheme.colorScheme.surface,
                        androidx.compose.foundation.shape.CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(category?.emoji ?: "📍", fontSize = 20.sp)
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category?.label ?: report.type,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                if (!report.subtype.isNullOrBlank()) {
                    Text(
                        text = report.subtype,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = dateStr,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = String.format("%.5f, %.5f", report.latitude, report.longitude),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                // Threat level indicator
                Box(
                    modifier = Modifier
                        .background(threatColor.copy(alpha = 0.15f), MaterialTheme.shapes.small)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = report.threatLevel,
                        fontSize = 9.sp,
                        color = threatColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(4.dp))
                // Community counts + user verdict buttons
                // If userVerdict is set, show the verdict state and disable buttons
                val verdict = report.userVerdict
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { if (verdict == null) onConfirm(report.id) },
                        enabled = verdict == null,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        val confirmedColor = if (verdict == "confirmed") Color(0xFF43A047)
                                             else Color(0xFF43A047).copy(alpha = if (verdict == null) 1f else 0.38f)
                        Text(
                            text = if (verdict == "confirmed") "✓ ${report.confirmedCount} ✅" else "✓ ${report.confirmedCount}",
                            fontSize = 11.sp,
                            color = confirmedColor
                        )
                    }
                    TextButton(
                        onClick = { if (verdict == null) onClear(report.id) },
                        enabled = verdict == null,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        val clearedColor = if (verdict == "dismissed") Color(0xFFE53935)
                                           else Color(0xFFE53935).copy(alpha = if (verdict == null) 1f else 0.38f)
                        Text(
                            text = if (verdict == "dismissed") "✕ ${report.clearedCount} ❌" else "✕ ${report.clearedCount}",
                            fontSize = 11.sp,
                            color = clearedColor
                        )
                    }
                }
            }
        }
    }
}
