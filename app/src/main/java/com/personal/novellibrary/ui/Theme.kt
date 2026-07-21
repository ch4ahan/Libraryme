package com.personal.novellibrary.ui

import androidx.compose.material3.MaterialTheme
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

@Composable
fun NovelLibraryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
