package com.neoremote.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF29577C),
    secondary = Color(0xFF6485A3),
    tertiary = Color(0xFF6A8CA8),
    background = Color(0xFFF4F7FB),
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF98C5F0),
    secondary = Color(0xFFB8CDE2),
    tertiary = Color(0xFF9FC7E8),
    background = Color(0xFF0D1420),
    surface = Color(0xFF121C2A),
)

@Composable
fun NeoRemoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

