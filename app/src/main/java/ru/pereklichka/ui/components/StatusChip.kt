package ru.pereklichka.ui.components

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.pereklichka.R
import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.rules.Presence
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.spacing

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

/**
 * Плашка статуса участника: значок формы статуса и текст на цветном фоне. Цвет всегда дублируется значком и текстом,
 * тревога ещё и жирным.
 */
@Composable
fun StatusChip(member: MemberView, modifier: Modifier = Modifier) {
    StatusChip(statusOf(member), modifier)
}

/** Плашка статуса с готовым текстом: «Тревога · объявил(а) Ольга». Без [withIcon] только текст. */
@Composable
fun StatusChip(status: StatusInfo, modifier: Modifier = Modifier, withIcon: Boolean = true) {
    val color = statusColor(status.kind)
    Pill(
        text = status.text,
        container = color.container,
        content = color.content,
        bold = status.kind == StatusKind.ALARM,
        icon = if (withIcon) chipIcon(status.kind) else null,
        modifier = modifier,
    )
}

/** Значок в плашке статуса. Для «Нормы» галочка в круге, как в макете своей карточки, для тревоги треугольник. */
@DrawableRes
fun chipIcon(kind: StatusKind): Int = when (kind) {
    StatusKind.ALARM -> R.drawable.sym_warning_fill
    StatusKind.LOST -> R.drawable.sym_wifi_off_fill
    StatusKind.AWAY -> R.drawable.sym_schedule_fill
    StatusKind.OK -> R.drawable.sym_check_circle_fill
}

/** Нейтральная плашка «Руководитель» рядом с именем. Статус участника она не заменяет. */
@Composable
fun LeaderBadge(modifier: Modifier = Modifier) {
    Pill(
        text = "Руководитель",
        container = MaterialTheme.colorScheme.surfaceContainerHigh,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
        bold = false,
        icon = null,
        modifier = modifier,
    )
}

/** Плашка из макета: высота 28 dp, скругление на всю высоту, значок 16 dp перед текстом 13 sp. */
@Composable
fun Pill(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    @DrawableRes icon: Int? = null,
    height: Dp = 28.dp,
) {
    Row(
        modifier = modifier
            .heightIn(min = height)
            .background(container, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        if (icon != null) SymIcon(icon, tint = content, size = 16.dp)
        Text(
            text = text,
            color = content,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
