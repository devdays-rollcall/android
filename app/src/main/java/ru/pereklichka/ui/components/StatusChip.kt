package ru.pereklichka.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.rules.Presence
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.spacing
import ru.pereklichka.ui.theme.statusColors

/** Какой статус показывать: тревога, «Не слышно», отлучка или норма. */
enum class StatusKind { ALARM, LOST, AWAY, OK }

/** Статус для показа: вид (от него зависит цвет) и текст. */
data class StatusInfo(val kind: StatusKind, val text: String)

/**
 * Выбирает статус участника. Приоритет: SOS, тревога, «Не слышно», отлучка, иначе норма. Чистая функция без Compose,
 * её проверяет `StatusOfTest`.
 */
fun statusOf(member: MemberView): StatusInfo = when {
    member.hasSos -> StatusInfo(StatusKind.ALARM, "SOS")
    member.hasActiveAlarm -> StatusInfo(StatusKind.ALARM, "Тревога")
    member.presence == Presence.LOST -> StatusInfo(StatusKind.LOST, "Не слышно")
    member.presence == Presence.AWAY -> {
        val remaining = member.awayRemainingMs
        val text = if (remaining != null && remaining > 0) "Отлучка ${formatRemaining(remaining)}" else "Отлучка"
        StatusInfo(StatusKind.AWAY, text)
    }
    else -> StatusInfo(StatusKind.OK, "Норма")
}

/** Плашка статуса участника: текст на цветном фоне. Цвет всегда дублируется текстом, тревога ещё и жирным. */
@Composable
fun StatusChip(member: MemberView, modifier: Modifier = Modifier) {
    StatusChip(statusOf(member), modifier)
}

@Composable
fun StatusChip(status: StatusInfo, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.statusColors
    val color = when (status.kind) {
        StatusKind.ALARM -> colors.alarm
        StatusKind.LOST -> colors.lost
        StatusKind.AWAY -> colors.away
        StatusKind.OK -> colors.ok
    }
    Pill(
        text = status.text,
        container = color.container,
        content = color.content,
        bold = status.kind == StatusKind.ALARM,
        modifier = modifier,
    )
}

/** Нейтральная плашка «Руководитель» рядом с именем. Статус участника она не заменяет. */
@Composable
fun LeaderBadge(modifier: Modifier = Modifier) {
    Pill(
        text = "Руководитель",
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
        bold = false,
        modifier = modifier,
    )
}

@Composable
private fun Pill(
    text: String,
    container: Color,
    content: Color,
    bold: Boolean,
    modifier: Modifier,
) {
    Row(
        modifier = modifier
            .background(container, MaterialTheme.shapes.small)
            .padding(horizontal = MaterialTheme.spacing.s, vertical = MaterialTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        Text(
            text = text,
            color = content,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (bold) FontWeight.Bold else null,
            maxLines = 1,
        )
    }
}

@Composable
private fun ChipsPreviewContent() {
    PereklichkaTheme {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(StatusInfo(StatusKind.OK, "Норма"))
                StatusChip(StatusInfo(StatusKind.AWAY, "Отлучка ещё 8 мин"))
                StatusChip(StatusInfo(StatusKind.LOST, "Не слышно"))
                StatusChip(StatusInfo(StatusKind.ALARM, "Тревога"))
                StatusChip(StatusInfo(StatusKind.ALARM, "SOS"))
                LeaderBadge()
            }
        }
    }
}

@Preview(name = "Светлая", showBackground = true)
@Composable
private fun ChipsLightPreview() = ChipsPreviewContent()

@Preview(name = "Тёмная", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChipsDarkPreview() = ChipsPreviewContent()
