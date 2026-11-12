package ru.pereklichka.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.DrawableRes
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import ru.pereklichka.ui.components.SymIcon
import ru.pereklichka.ui.theme.statusColors

/** Насколько низ красной шапки темнее верха: из #B93A0B макета выходит #8E2A06. */
private const val HEADER_SHADE = 0.23f

/**
 * Фон красной шапки экранов тревоги: цвет тревоги из темы сверху и он же, затемнённый на 23 %, снизу.
 * Берётся `statusColors.alarm`, потому что в тёмной теме `colorScheme.error` светлый и белый текст на нём не читается.
 */
@Composable
internal fun alarmHeaderBrush(): Brush {
    val top = MaterialTheme.statusColors.alarm.container
    return Brush.verticalGradient(listOf(top, lerp(top, Color.Black, HEADER_SHADE)))
}

/**
 * Делает значки строки состояния белыми, пока экран с красной шапкой на виду, и возвращает прежние при уходе с него.
 * В превью окна нет, и эффект ничего не делает.
 */
@Composable
internal fun WhiteStatusBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        if (window == null || view.isInEditMode) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        val wasLight = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = wasLight }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Кнопка-значок в шапке: зона нажатия 48 dp, [description] читает TalkBack. */
@Composable
internal fun HeaderIconButton(@DrawableRes icon: Int, description: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        SymIcon(icon, contentDescription = description, tint = tint)
    }
}

private val SIDES_TO = listOf(
    "на север", "на северо-восток", "на восток", "на юго-восток", "на юг", "на юго-запад", "на запад", "на северо-запад",
)

/** «на северо-запад» для сектора 0..7 из `placeOf`. */
internal fun directionWords(sector: Int): String = SIDES_TO[sector]
