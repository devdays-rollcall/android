package ru.pereklichka.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Контраст выше обычного, потому что экраны читают на солнце.
private val LightColors = lightColorScheme(
    primary = Color(0xFF0B4F8A),
    onPrimary = Color.White,
    error = Color(0xFFB3261E),
    onError = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CCBFF),
    onPrimary = Color(0xFF00213F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
)

/** Тема Material 3 для всех экранов, светлая или тёмная по настройке системы. Цвета статусов и тревог добавляются вместе с экранами. */
@Composable
fun PereklichkaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
