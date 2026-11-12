package ru.pereklichka.ui.components

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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.pereklichka.R
import ru.pereklichka.domain.rules.MemberView

/**
 * Стрелка на участника: азимут от севера, повёрнутая стрелка. Экран не знает, куда смотрит телефон, поэтому
 * стрелка показывает сторону света, как на карте, где север сверху. Без азимута стрелки нет.
 */
@Composable
fun DirectionArrow(bearingDeg: Double, size: Dp = 24.dp, tint: Color = MaterialTheme.colorScheme.onSurface) {
    SymIcon(R.drawable.sym_navigation_fill, Modifier.rotate(bearingDeg.toFloat()), tint = tint, size = size)
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
