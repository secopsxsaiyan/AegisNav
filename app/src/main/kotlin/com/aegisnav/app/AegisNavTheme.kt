package com.aegisnav.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

private val AegisNavDarkColors = darkColorScheme(
    primary            = Color(0xFF00D4FF),
    onPrimary          = Color(0xFF003544),
    primaryContainer   = Color(0xFF004E63),
    onPrimaryContainer = Color(0xFF9EEFFF),
    secondary          = Color(0xFF00A8C8),
    onSecondary        = Color(0xFF002B35),
    background         = Color(0xFF0D1117),
    onBackground       = Color(0xFFDDE3EA),
    surface            = Color(0xFF131920),
    onSurface          = Color(0xFFDDE3EA),
    surfaceVariant     = Color(0xFF1E2930),
    onSurfaceVariant   = Color(0xFF9DAAB5),
    error              = Color(0xFFFF5252),
    onError            = Color(0xFF690000),
    outline            = Color(0xFF4A5568),
)

private val AegisNavLightColors = lightColorScheme(
    primary            = Color(0xFF0077A8),
    onPrimary          = Color(0xFFFFFFFF),
    primaryContainer   = Color(0xFFCCEAF7),
    onPrimaryContainer = Color(0xFF002030),
    secondary          = Color(0xFF0064A0),
    onSecondary        = Color(0xFFFFFFFF),
    background         = Color(0xFFF5F7FA),
    onBackground       = Color(0xFF1A1C1E),
    surface            = Color(0xFFFFFFFF),
    onSurface          = Color(0xFF1A1C1E),
    surfaceVariant     = Color(0xFFE8EDF2),
    onSurfaceVariant   = Color(0xFF41484F),
    error              = Color(0xFFBA1A1A),
    onError            = Color(0xFFFFFFFF),
    outline            = Color(0xFF72787E),
)

private fun lerpColorScheme(light: ColorScheme, dark: ColorScheme, fraction: Float): ColorScheme {
    return light.copy(
        primary                    = lerp(light.primary,                    dark.primary,                    fraction),
        onPrimary                  = lerp(light.onPrimary,                  dark.onPrimary,                  fraction),
        primaryContainer           = lerp(light.primaryContainer,           dark.primaryContainer,           fraction),
        onPrimaryContainer         = lerp(light.onPrimaryContainer,         dark.onPrimaryContainer,         fraction),
        secondary                  = lerp(light.secondary,                  dark.secondary,                  fraction),
        onSecondary                = lerp(light.onSecondary,                dark.onSecondary,                fraction),
        secondaryContainer         = lerp(light.secondaryContainer,         dark.secondaryContainer,         fraction),
        onSecondaryContainer       = lerp(light.onSecondaryContainer,       dark.onSecondaryContainer,       fraction),
        tertiary                   = lerp(light.tertiary,                   dark.tertiary,                   fraction),
        onTertiary                 = lerp(light.onTertiary,                 dark.onTertiary,                 fraction),
        tertiaryContainer          = lerp(light.tertiaryContainer,          dark.tertiaryContainer,          fraction),
        onTertiaryContainer        = lerp(light.onTertiaryContainer,        dark.onTertiaryContainer,        fraction),
        error                      = lerp(light.error,                      dark.error,                      fraction),
        onError                    = lerp(light.onError,                    dark.onError,                    fraction),
        errorContainer             = lerp(light.errorContainer,             dark.errorContainer,             fraction),
        onErrorContainer           = lerp(light.onErrorContainer,           dark.onErrorContainer,           fraction),
        background                 = lerp(light.background,                 dark.background,                 fraction),
        onBackground               = lerp(light.onBackground,               dark.onBackground,               fraction),
        surface                    = lerp(light.surface,                    dark.surface,                    fraction),
        onSurface                  = lerp(light.onSurface,                  dark.onSurface,                  fraction),
        surfaceVariant             = lerp(light.surfaceVariant,             dark.surfaceVariant,             fraction),
        onSurfaceVariant           = lerp(light.onSurfaceVariant,           dark.onSurfaceVariant,           fraction),
        outline                    = lerp(light.outline,                    dark.outline,                    fraction),
        outlineVariant             = lerp(light.outlineVariant,             dark.outlineVariant,             fraction),
        scrim                      = lerp(light.scrim,                      dark.scrim,                      fraction),
        inverseSurface             = lerp(light.inverseSurface,             dark.inverseSurface,             fraction),
        inverseOnSurface           = lerp(light.inverseOnSurface,           dark.inverseOnSurface,           fraction),
        inversePrimary             = lerp(light.inversePrimary,             dark.inversePrimary,             fraction),
        surfaceTint                = lerp(light.surfaceTint,                dark.surfaceTint,                fraction),
    )
}

@Composable
fun AegisNavTheme(isDark: Boolean, content: @Composable () -> Unit) {
    val darkFraction by animateFloatAsState(
        targetValue = if (isDark) 1f else 0f,
        animationSpec = tween(durationMillis = 1000),
        label = "an_dark_fraction"
    )
    val colorScheme = lerpColorScheme(AegisNavLightColors, AegisNavDarkColors, darkFraction)
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
