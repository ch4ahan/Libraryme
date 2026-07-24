package com.personal.novellibrary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF4B5D92),
    primaryContainer = Color(0xFFDCE1FF),
    secondary = Color(0xFF775A00),
    secondaryContainer = Color(0xFFFFDF8E),
    background = Color(0xFFFAF8FF),
    surface = Color(0xFFFAF8FF),
    surfaceVariant = Color(0xFFE3E2EC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB5C4FF),
    primaryContainer = Color(0xFF334477),
    secondary = Color(0xFFF1C048),
    secondaryContainer = Color(0xFF5A4300),
    background = Color(0xFF121318),
    surface = Color(0xFF121318),
)

private val LibraryTypography = Typography(
    displaySmall = Typography().displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = Typography().headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun NovelLibraryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = LibraryTypography,
        content = content,
    )
}
