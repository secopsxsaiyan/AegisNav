package com.aegisnav.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun StrobeEdgeOverlay(
    modifier: Modifier = Modifier,
    color: Color = Color.Red,
    onTap: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "strobe")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1500
                0f at 0
                1f at 750
                0f at 1500
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "strobe_alpha"
    )

    Box(
        modifier = modifier
            .border(8.dp, color.copy(alpha = alpha))
            .clickable(onClick = onTap)
    )
}
