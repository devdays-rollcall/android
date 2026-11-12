package ru.pereklichka.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.pereklichka.R
import ru.pereklichka.domain.rules.MemberView

/**
 * Стрелка на участника: азимут от севера, повёрнутая стрелка. Экран не знает, куда смотрит телефон, поэтому
 * стрелка показывает сторону света, как на карте, где север сверху. Без азимута стрелки нет.
 */
@Composable
fun DirectionArrow(
    bearingDeg: Double,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    // Стрелка рисуется и поворачивается внутри Canvas. Векторная иконка в повёрнутом слое (Modifier.rotate)
    // на эмуляторе Android 15 и в Robolectric рисовалась под случайным углом при азимутах вроде 333°.
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val arrow = Path().apply {
            moveTo(w * 0.5f, h * 0.08f)
            lineTo(w * 0.82f, h * 0.88f)
            lineTo(w * 0.5f, h * 0.72f)
            lineTo(w * 0.18f, h * 0.88f)
            close()
        }
        rotate(bearingDeg.toFloat()) { drawPath(arrow, tint) }
    }
}

/**
 * Стрелка в круге и расстояние под ней для правого края строки участника. Ближе 5 м вместо стрелки
 * пишется «рядом». Для меня самого и без расстояния ничего не рисуется.
 */
@Composable
fun DirectionBadge(member: MemberView) {
    val place = placeOf(member) ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val bearing = member.bearingDeg
            if (place.meters != null && bearing != null) {
                DirectionArrow(bearing, size = 22.dp)
            } else {
                SymIcon(R.drawable.sym_groups, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 22.dp)
            }
        }
        Text(
            text = formatDistance(member.distanceM) ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
