package com.aegisnav.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Circular compass toggle button positioned in the top-right corner of the map.
 *
 * Displays "N" when in north-up mode and "⬆" when in course-up mode.
 *
 * @param courseUpEnabled Whether the map is currently in course-up orientation.
 * @param onToggle        Invoked when the button is tapped; callers flip [courseUpEnabled] and re-enable auto-center.
 * @param modifier        Caller controls positioning (alignment + padding).
 */
@Composable
internal fun CompassButton(
    courseUpEnabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (courseUpEnabled) "⬆" else "N",
            fontSize = if (courseUpEnabled) 18.sp else 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
