package com.personal.novellibrary.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    primaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFF006A60),
    secondaryContainer = Color(0xFF9EF2E4),
    background = Color(0xFFFFF8FF),
    surface = Color(0xFFFFF8FF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCFBCFF),
    primaryContainer = Color(0xFF4F378B),
    secondary = Color(0xFF82D5C8),
    background = Color(0xFF141218),
    surface = Color(0xFF141218),
)

@Composable
fun NovelLibraryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
