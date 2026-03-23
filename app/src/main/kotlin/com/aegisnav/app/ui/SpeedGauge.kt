package com.aegisnav.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compact digital speed readout displayed above the center FAB.
 *
 * @param speedMph Current speed in miles-per-hour (already converted from m/s).
 * @param speedLimitMph Current road speed limit in mph (null = unknown). When non-null and
 *   [speedMph] exceeds it, the speed text turns red as a visual warning. No TTS is triggered.
 * @param modifier Caller controls positioning (alignment + padding).
 */
@Composable
internal fun SpeedGauge(
    speedMph: Int,
    speedLimitMph: Int? = null,
    modifier: Modifier = Modifier
) {
    // Red if over the speed limit; white/normal otherwise
    val speedColor: Color = if (speedLimitMph != null && speedMph > speedLimitMph) {
        Color.Red
    } else {
        Color.Unspecified  // inherits from MaterialTheme
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "$speedMph",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = speedColor
                )
                Text(
                    text = "mph",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}
