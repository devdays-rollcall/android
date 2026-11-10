package ru.pereklichka.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Контраст выше обычного: экраны читают на солнце. Статус никогда не передаётся одним цветом, рядом всегда текст.
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

/** Тема приложения. Цвета статусов и тревог добавляет Валентин вместе с экранами. */
@Composable
fun PereklichkaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
