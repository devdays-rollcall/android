package ru.pereklichka.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.pereklichka.R
import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.ui.theme.StatusColor
import ru.pereklichka.ui.theme.statusColors

/** Цвета аватаров: по id участника, чтобы у одного человека цвет не менялся между экранами и телефонами. */
private val AvatarColors = listOf(
    Color(0xFF4A5D8C),
    Color(0xFF7A3E6E),
    Color(0xFF8E4A2A),
    Color(0xFF3F5A8A),
    Color(0xFF2F6B5E),
    Color(0xFF8A3B2A),
)

/** Цвет аватара участника с этим id. */
fun avatarColor(memberId: String): Color = AvatarColors[Math.floorMod(memberId.hashCode(), AvatarColors.size)]

/** Цвета статуса из темы. */
@Composable
@ReadOnlyComposable
fun statusColor(kind: StatusKind): StatusColor {
    val colors = MaterialTheme.statusColors
    return when (kind) {
        StatusKind.ALARM -> colors.alarm
        StatusKind.LOST -> colors.lost
        StatusKind.AWAY -> colors.away
        StatusKind.OK -> colors.ok
    }
}

/** Значок статуса: у каждого статуса своя форма, чтобы его различали и без цвета. */
@DrawableRes
fun statusIcon(kind: StatusKind): Int = when (kind) {
    StatusKind.ALARM -> R.drawable.sym_priority_high_fill
    StatusKind.LOST -> R.drawable.sym_wifi_off_fill
    StatusKind.AWAY -> R.drawable.sym_schedule_fill
    StatusKind.OK -> R.drawable.sym_check_fill
}

/**
 * Круг с первой буквой имени, кольцом цвета статуса и значком статуса в правом нижнем углу. Кольцо и значок
 * не рисуются, если [showStatus] равен false: например, у своей карточки статус показан плашкой рядом.
 */
@Composable
fun StatusAvatar(member: MemberView, size: Dp = 48.dp, showStatus: Boolean = true) {
    val kind = statusOf(member).kind
    val color = statusColor(kind)
    val ringWidth = 3.dp
    val gap = 3.dp
    Box(Modifier.size(size + (ringWidth + gap) * 2)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(if (showStatus) Modifier.border(ringWidth, color.accent, CircleShape) else Modifier)
                .padding(ringWidth + gap)
                .background(avatarColor(member.id.value), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = member.name.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontSize = (size.value * 0.4f).sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (showStatus) {
            val badge = size * 0.46f
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp)
                    .size(badge)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest, CircleShape)
                    .padding(2.dp)
                    .background(color.container, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                SymIcon(statusIcon(kind), tint = color.content, size = badge * 0.62f)
            }
        }
    }
}
