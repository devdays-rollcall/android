package ru.pereklichka.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Палитра из макета (первый вариант): тёмно-зелёный основной цвет, тёплый фон, оранжево-красный только для тревог.
// Контраст высокий, потому что экраны читают на солнце. В экранах hex не пишем: берём MaterialTheme.colorScheme
// и MaterialTheme.statusColors.

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F3D36),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EFE9),
    onPrimaryContainer = Color(0xFF0F2520),
    secondary = Color(0xFF3A423E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E8E4),
    onSecondaryContainer = Color(0xFF1B1F1D),
    background = Color(0xFFF6F5F0),
    onBackground = Color(0xFF1B1F1D),
    surface = Color(0xFFF6F5F0),
    onSurface = Color(0xFF1B1F1D),
    surfaceVariant = Color(0xFFE6E8E4),
    onSurfaceVariant = Color(0xFF5A625E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFECEBE4),
    outline = Color(0xFF6F746F),
    outlineVariant = Color(0xFFDDD9CE),
    error = Color(0xFFB93A0B),
    onError = Color.White,
    errorContainer = Color(0xFFFBDDD7),
    onErrorContainer = Color(0xFF6E1B05),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8D5C2),
    onPrimary = Color(0xFF0F2520),
    primaryContainer = Color(0xFF2C5A4C),
    onPrimaryContainer = Color(0xFFD3EBE0),
    secondary = Color(0xFFC5CCC7),
    onSecondary = Color(0xFF1B1F1D),
    secondaryContainer = Color(0xFF3A423E),
    onSecondaryContainer = Color(0xFFE6E8E4),
    background = Color(0xFF111512),
    onBackground = Color(0xFFE8EBE8),
    surface = Color(0xFF111512),
    onSurface = Color(0xFFE8EBE8),
    surfaceVariant = Color(0xFF2A302C),
    onSurfaceVariant = Color(0xFFB7BEB9),
    surfaceContainerLowest = Color(0xFF0D100E),
    surfaceContainerLow = Color(0xFF171C19),
    surfaceContainer = Color(0xFF1B201D),
    surfaceContainerHigh = Color(0xFF252B27),
    outline = Color(0xFF8A918C),
    outlineVariant = Color(0xFF3A423E),
    error = Color(0xFFFFB4A1),
    onError = Color(0xFF5B1500),
    errorContainer = Color(0xFF7A2408),
    onErrorContainer = Color(0xFFFFDAD1),
)

/** Пара «фон и текст» для статуса. Рядом со статусом всегда пишется текст: цвета на солнце различаются плохо. */
data class StatusColor(val container: Color, val content: Color)

/** Цвета статусов участников и тревог. «Норма», «Отлучка», «Не слышно» и тревога (сплошной красный фон для AlarmScreen). */
data class StatusColors(
    val ok: StatusColor,
    val away: StatusColor,
    val lost: StatusColor,
    val alarm: StatusColor,
)

private val LightStatus = StatusColors(
    ok = StatusColor(Color(0xFFDDF1E4), Color(0xFF17603B)),
    away = StatusColor(Color(0xFFFBEBC8), Color(0xFF7A4B00)),
    lost = StatusColor(Color(0xFFFBDDD7), Color(0xFF9B2A0A)),
    alarm = StatusColor(Color(0xFFB93A0B), Color.White),
)

private val DarkStatus = StatusColors(
    ok = StatusColor(Color(0xFF1F4A33), Color(0xFFBFE8CF)),
    away = StatusColor(Color(0xFF4D3A0A), Color(0xFFF5DEA3)),
    lost = StatusColor(Color(0xFF6B2310), Color(0xFFFFD1C5)),
    alarm = StatusColor(Color(0xFF9B2A0A), Color.White),
)

private val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** Цвета статусов текущей темы: `MaterialTheme.statusColors.lost.container`. */
val MaterialTheme.statusColors: StatusColors
    @Composable @ReadOnlyComposable get() = LocalStatusColors.current

// Шрифт системный, без загрузки из сети: приложение работает без интернета. Размеры чуть крупнее стандартных.
private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
)

// Кнопки и поля скруглены на 14 dp, карточки на 16 dp, как в макете.
private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * Цвета аватаров: у каждого контраст с белой буквой не ниже 4,5:1. Статус не передают, это только отличие людей
 * друг от друга. Цвет выбирается по хэшу id участника, поэтому у человека он на всех телефонах один и тот же.
 */
val AvatarColors = listOf(
    Color(0xFF1F3D36),
    Color(0xFF7A4E2D),
    Color(0xFF4C5F8A),
    Color(0xFF7B3F6B),
    Color(0xFF2F6F73),
    Color(0xFF8A3B2A),
)

/** Отступы приложения: `MaterialTheme.spacing.m`. Числа в экранах и компонентах не пишем. */
data class Spacing(
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
)

private val LocalSpacing = staticCompositionLocalOf { Spacing() }

val MaterialTheme.spacing: Spacing
    @Composable @ReadOnlyComposable get() = LocalSpacing.current

/** Тема Material 3 для всех экранов, светлая или тёмная по настройке системы. Динамические цвета системы не используем. */
@Composable
fun PereklichkaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(
        LocalStatusColors provides if (dark) DarkStatus else LightStatus,
        LocalSpacing provides Spacing(),
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}