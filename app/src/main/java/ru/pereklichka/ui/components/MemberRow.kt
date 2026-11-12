package ru.pereklichka.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.pereklichka.R
import ru.pereklichka.domain.rules.MemberView

private const val BATTERY_ICON = "battery"

/**
 * Строка участника в карточке-списке группы: аватар с кольцом и значком статуса, имя, строка статуса цветом статуса
 * ([memberStatusLine], заряд добавляется, только когда он не выше 20 %) и справа стрелка с расстоянием.
 * Нажатие открывает карточку участника. TalkBack читает строку целиком через [spokenSummary].
 */
@Composable
fun MemberRow(member: MemberView, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val status = statusOf(member)
    val description = spokenSummary(member, status)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StatusAvatar(member)
        Column(Modifier.weight(1f)) {
            MemberName(member)
            StatusLine(member, statusLineColor(status.kind))
        }
        DirectionBadge(member)
    }
}

/** Имя 16 sp; у руководителя после имени серым «руководитель». */
@Composable
private fun MemberName(member: MemberView) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val text = buildAnnotatedString {
        append(member.name)
        if (member.isLeader) {
            withStyle(SpanStyle(color = secondary, fontWeight = FontWeight.Normal, fontSize = 14.sp)) { append(" · руководитель") }
        }
    }
    Text(text, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Строка статуса: «Тревога · 4 мин назад · [батарейка] 12%». */
@Composable
private fun StatusLine(member: MemberView, color: Color) {
    val battery = lowBattery(member)
    val text = buildAnnotatedString {
        append(memberStatusLine(member))
        if (battery != null) {
            append(" · ")
            appendInlineContent(BATTERY_ICON, "заряд")
            append("$battery%")
        }
    }
    val inline = mapOf(
        BATTERY_ICON to InlineTextContent(Placeholder(16.sp, 16.sp, PlaceholderVerticalAlign.TextCenter)) {
            SymIcon(R.drawable.sym_battery_1_bar_fill, tint = color, size = 16.dp)
        },
    )
    Text(
        text,
        inlineContent = inline,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Цвет строки статуса: тревога и «Не слышно» красным, отлучка жёлто-коричневым, норма серым. */
@Composable
fun statusLineColor(kind: StatusKind): Color = when (kind) {
    StatusKind.ALARM, StatusKind.LOST -> statusColor(StatusKind.LOST).content
    StatusKind.AWAY -> statusColor(StatusKind.AWAY).content
    StatusKind.OK -> MaterialTheme.colorScheme.onSurfaceVariant
}
