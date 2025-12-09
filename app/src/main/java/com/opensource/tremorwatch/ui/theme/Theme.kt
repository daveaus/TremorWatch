package com.opensource.tremorwatch.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Colors

/**
 * Material 2 color scheme for TremorWatch Wear OS App
 *
 * Uses Wear OS-specific Material 2 colors optimized for watch displays
 * Note: Wear OS Compose currently uses Material 2, not Material 3
 */
private val WearColorPalette = Colors(
    primary = Color(0xFFD0BCFF),
    primaryVariant = Color(0xFF9A82DB),
    secondary = Color(0xFFCCC2DC),
    secondaryVariant = Color(0xFF958DA5),
    background = Color(0xFF1C1B1F),
    surface = Color(0xFF1C1B1F),
    error = Color(0xFFF2B8B5),
    onPrimary = Color(0xFF381E72),
    onSecondary = Color(0xFF332D41),
    onBackground = Color(0xFFE6E1E5),
    onSurface = Color(0xFFE6E1E5),
    onError = Color(0xFF601410)
)

/**
 * Material 2 Theme for Wear OS
 *
 * Features:
 * - Optimized for AMOLED watch displays
 * - High contrast for outdoor readability
 * - Dark theme by default (better for battery on AMOLED)
 */
@Composable
fun TremorWatchTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colors = WearColorPalette,
        content = content
    )
}
