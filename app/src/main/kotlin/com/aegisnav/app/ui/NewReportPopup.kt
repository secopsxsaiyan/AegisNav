package com.aegisnav.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegisnav.app.p2p.IncomingReport

/**
 * Tracks new incoming P2P reports and shows a confirm/dismiss popup for each.
 * Each report is shown once; auto-dismissed after 30 seconds if no action taken.
 * Positioned externally via [modifier] — caller sets alignment and padding.
 */
@Composable
fun NewReportPopupHost(
    incomingReports: List<IncomingReport>,
    modifier: Modifier = Modifier
) {
    // In-memory set of keys for reports the user has already reviewed or that auto-dismissed.
    var reviewedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    // The report currently shown in the popup (null = no popup).
    var activeReport by remember { mutableStateOf<IncomingReport?>(null) }

    // Unique key for an incoming report (sourceNodeId + timestamp).
    fun IncomingReport.key() = "${sourceNodeId}_$timestamp"

    // Whenever the incoming list changes, surface the latest unreviewed report if none active.
    LaunchedEffect(incomingReports) {
        if (activeReport == null) {
            val pending = incomingReports.lastOrNull { it.key() !in reviewedKeys }
            if (pending != null) {
                activeReport = pending
            }
        }
    }

    // Auto-dismiss after 30 seconds.
    LaunchedEffect(activeReport) {
        val r = activeReport ?: return@LaunchedEffect
        kotlinx.coroutines.delay(30_000L)
        // Only dismiss if still the same report (user may have acted already).
        if (activeReport?.key() == r.key()) {
            reviewedKeys = reviewedKeys + r.key()
            activeReport = null
            // Surface next unreviewed report if any.
            val next = incomingReports.lastOrNull { it.key() !in reviewedKeys }
            activeReport = next
        }
    }

    val report = activeReport ?: return

    val dismiss: () -> Unit = {
        reviewedKeys = reviewedKeys + report.key()
        activeReport = null
        val next = incomingReports.lastOrNull { it.key() !in reviewedKeys }
        activeReport = next
    }

    NewReportPopup(
        report = report,
        modifier = modifier,
        onConfirm = dismiss,   // user confirmed — mark reviewed (no local DB write; P2P report)
        onDismiss = dismiss    // user dismissed
    )
}

@Composable
fun NewReportPopup(
    report: IncomingReport,
    modifier: Modifier = Modifier,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val category = ReportCategory.entries.firstOrNull { it.dbType == report.type }
    val threatColor = when (report.threatLevel) {
        "HIGH"   -> Color(0xFFE53935)
        "MEDIUM" -> Color(0xFFFFB300)
        else     -> Color(0xFF43A047)
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 4.dp,
        modifier = modifier.widthIn(max = 220.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(category?.emoji ?: "📍", fontSize = 18.sp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = category?.label ?: report.type,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "New nearby report",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .background(threatColor.copy(alpha = 0.15f), MaterialTheme.shapes.small)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = report.threatLevel.take(1),
                        fontSize = 9.sp,
                        color = threatColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            // Confirm / Dismiss buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Confirm button — green circle ✓
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
                // Dismiss button — red circle ✗
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
}
