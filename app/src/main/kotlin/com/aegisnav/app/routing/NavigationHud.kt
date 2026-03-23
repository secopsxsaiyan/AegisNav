package com.aegisnav.app.routing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// ── Route Progress Bar ────────────────────────────────────────────────────────

/**
 * Thin horizontal progress bar at the top of the navigation HUD.
 * Shows what fraction of the route distance has been completed.
 */
@Composable
fun RouteProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color.Gray.copy(alpha = 0.3f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .background(Color(0xFF43A047))
        )
    }
}

// ── Compass Heading Toggle Button ─────────────────────────────────────────────

/**
 * Small toggle button in the nav HUD to switch between heading-up and north-up mode.
 */
@Composable
fun HeadingToggleButton(
    headingUp: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(
                if (headingUp) Color(0xFF1565C0).copy(alpha = 0.85f)
                else Color(0xFF555555).copy(alpha = 0.85f)
            )
            .clickable { onToggle() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (headingUp) "↑" else "N",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── Active Navigation HUD ─────────────────────────────────────────────────────

/**
 * Navigation HUD shown at the top of the screen when navigating.
 * Includes:
 *  - Route progress bar (thin green strip at very top)
 *  - Current instruction + turn arrow
 *  - Bottom bar: total distance, live ETA, heading-up toggle, stop button
 */
@Composable
fun NavigationHud(
    viewModel: NavigationViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val isNavigating by viewModel.isNavigating.collectAsStateWithLifecycle()
    val currentInstruction by viewModel.currentInstruction.collectAsStateWithLifecycle()
    val distanceToNext by viewModel.distanceToNext.collectAsStateWithLifecycle()
    val routeResult by viewModel.routeResult.collectAsStateWithLifecycle()
    val routeProgress by viewModel.routeProgress.collectAsStateWithLifecycle()
    val headingUp by viewModel.headingUp.collectAsStateWithLifecycle()
    val eta by viewModel.eta.collectAsStateWithLifecycle()

    if (!isNavigating) return

    Column(modifier = modifier.fillMaxWidth()) {
        // ── Route progress bar: very top, full width ──────────────────────────
        RouteProgressBar(progress = routeProgress)

        // ── Top banner: current instruction ───────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1565C0).copy(alpha = 0.93f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Turn arrow
            Text(
                text = turnArrow(currentInstruction?.sign ?: 0),
                fontSize = 32.sp,
                color = Color.White
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currentInstruction?.text ?: "Follow the route",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (distanceToNext > 0) {
                    Text(
                        text = "In ${formatDistance(distanceToNext)}",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // ── Bottom bar: ETA + heading toggle + stop ────────────────────────────
        routeResult?.let { result ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D47A1).copy(alpha = 0.88f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = eta ?: "ETA: ${formatDuration(result.durationSeconds)}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Total: ${formatDistance(result.distanceMeters)}",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Heading-up toggle button
                    HeadingToggleButton(
                        headingUp = headingUp,
                        onToggle = { viewModel.toggleHeadingUp() }
                    )
                    TextButton(
                        onClick = { viewModel.stopNavigation() },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF9A9A))
                    ) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
