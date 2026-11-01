package com.silm.live.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Primary = Color(0xFF00A1D6)     // B 站蓝
val PrimaryDark = Color(0xFF0087B3)

/** TV 端背景 */
val TvBackground = Color(0xFF0E1116)
val TvSurface = Color(0xFF1A1F26)

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5F0F9),
    secondary = Color(0xFF4A6269),
    surface = Color(0xFFF8FAFC),
)

private val DarkColors = darkColorScheme(
    primary = Primary,
    primaryContainer = Color(0xFF004A61),
    background = TvBackground,
    surface = TvSurface,
)

@Composable
fun SilmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    forceDark: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme || forceDark) DarkColors else LightColors,
        content = content
    )
}
