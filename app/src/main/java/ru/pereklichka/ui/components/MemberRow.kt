package ru.pereklichka.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.rules.Presence
import ru.pereklichka.domain.time.Age
import ru.pereklichka.ui.theme.AvatarColors
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.spacing

private val AvatarSize = 40.dp
private val MinRowHeight = 72.dp
private val BorderWidth = 1.dp
private const val LOST_TINT_ALPHA = 0.35f

/**
 * Строка участника в списке группы: аватар, имя, под ним «когда слышали · расстояние · заряд» и статус справа.
 * Нажатие открывает карточку участника. Всё, что показано, пришло в [member] уже посчитанным.
 */
@Composable
fun MemberRow(member: MemberView, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val status = statusOf(member)
    val colors = MaterialTheme.colorScheme
    // Тревога заливается целиком, «Не слышно» только слегка: плашка «Не слышно» на заливке того же цвета пропала бы.
    val container = when (status.kind) {
        StatusKind.ALARM -> colors.errorContainer
        StatusKind.LOST -> colors.errorContainer.copy(alpha = LOST_TINT_ALPHA)
        else -> colors.surfaceContainerLowest
    }
    val description = spokenSummary(member, status)

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description },
        shape = MaterialTheme.shapes.medium,
        color = container,
        border = BorderStroke(BorderWidth, colors.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = MinRowHeight)
                .padding(horizontal = MaterialTheme.spacing.m, vertical = MaterialTheme.spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.m),
        ) {
            Avatar(member)
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (member.isMe) "${member.name} (вы)" else member.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = lastSeenLine(member),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // «Норма» у руководителя ничего не говорит, поэтому вместо неё пишем, что это руководитель.
            if (member.isLeader && status.kind == StatusKind.OK) LeaderBadge() else StatusChip(status)
        }
    }
}

@Composable
private fun Avatar(member: MemberView) {
    val color = AvatarColors[Math.floorMod(member.id.value.hashCode(), AvatarColors.size)]
    Box(
        modifier = Modifier.size(AvatarSize).background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = member.name.firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}