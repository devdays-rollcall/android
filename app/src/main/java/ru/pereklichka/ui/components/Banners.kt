package ru.pereklichka.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.pereklichka.R
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.rules.AlarmView
import ru.pereklichka.domain.rules.NearbyPrompt
import ru.pereklichka.domain.rules.RollcallView
import ru.pereklichka.ui.theme.statusColors

/**
 * Баннер над списком из макета: иконка слева, заголовок и подпись, шеврон справа. Скругление 20 dp, как у карточек.
 * TalkBack читает заголовок и подпись одной фразой.
 */
@Composable
private fun Banner(
    container: Color,
    content: Color,
    @DrawableRes icon: Int,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    strong: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(title, subtitle).joinToString(". ") },
        shape = CardShape,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SymIcon(icon, size = if (strong) 28.dp else 24.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = if (strong) 17.sp else 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium,
                )
                if (subtitle != null) {
                    Text(subtitle, fontSize = 14.sp, lineHeight = 20.sp, color = content.copy(alpha = 0.92f))
                }
            }
            SymIcon(R.drawable.sym_chevron_right)
        }
    }
}

/**
 * Активная тревога или SOS: сплошной красный баннер, нажатие открывает экран тревог. Тревогу, объявленную мной
 * ([me]), подпись так и называет: «Объявили вы».
 */
@Composable
fun AlarmBanner(alarm: AlarmView, onClick: () -> Unit, me: MemberId? = null) {
    val color = MaterialTheme.statusColors.alarm
    val subtitle = when {
        alarm.isSos -> "Сигнал SOS · ${formatAge(alarm.age)}"
        alarm.raisedBy == me -> "Объявили вы · ${formatAge(alarm.age)}"
        else -> "Объявил(а) ${alarm.raisedByName} · ${formatAge(alarm.age)}"
    }
    Banner(
        container = color.container,
        content = color.content,
        icon = R.drawable.sym_warning_fill,
        title = if (alarm.isSos) "SOS: ${alarm.targetName}" else "Тревога: ${alarm.targetName}",
        subtitle = subtitle,
        onClick = onClick,
        strong = true,
    )
}

/** «Ещё N тревог» под тремя показанными. */
@Composable
fun MoreAlarmsBanner(count: Int, onClick: () -> Unit) {
    val color = MaterialTheme.statusColors.lost
    Banner(color.container, color.content, R.drawable.sym_warning_fill, "Ещё $count: открыть все тревоги", null, onClick)
}

/** Телефон участника, о котором объявлена тревога, рядом. Подтверждает человек на карточке участника. */
@Composable
fun PromptBanner(prompt: NearbyPrompt, onClick: () -> Unit) {
    val color = MaterialTheme.statusColors.away
    Banner(
        container = color.container,
        content = color.content,
        icon = R.drawable.sym_visibility,
        title = "Рядом телефон участника ${prompt.name}. Вы видите этого человека?",
        subtitle = "Откройте карточку, чтобы подтвердить, что он в порядке",
        onClick = onClick,
    )
}

/**
 * Предупреждение о телефоне: служба не запущена, не хватает разрешений, выключен Bluetooth или уведомления, включена
 * полная тишина. Нажатие открывает экран, где это исправляется.
 */
@Composable
fun WarningBanner(warning: PhoneWarning, onClick: () -> Unit) {
    val color = if (warning == PhoneWarning.SERVICE_NOT_STARTED) MaterialTheme.statusColors.lost else MaterialTheme.statusColors.away
    Banner(color.container, color.content, warning.icon, warning.title, warning.subtitle, onClick)
}

/**
 * Карточка идущей переклички: сколько осталось, полоса ответивших и строка под ней. Руководитель видит, сколько
 * ответило и кто рядом, но молчит; участник видит, ответил ли он сам. [names] переводит id в имена.
 */
@Composable
fun RollcallBanner(
    rollcall: RollcallView,
    iAmLeader: Boolean,
    iAnswered: Boolean,
    names: Map<MemberId, String>,
    onClick: () -> Unit,
) {
    val color = MaterialTheme.statusColors.ok
    val total = rollcall.answered.size + rollcall.silentNearby.size + rollcall.notHeard.size
    val remaining = if (rollcall.remainingMs > 0) "ещё ${formatClock(rollcall.remainingMs)}" else "время вышло"
    val line = when {
        iAmLeader -> rollcallSummary(rollcall, names)
        iAnswered -> "Вы ответили · ответили ${rollcall.answered.size} из $total"
        else -> "Нажмите, чтобы ответить «Я здесь»"
    }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "Перекличка, $remaining. $line" },
        shape = CardShape,
        color = color.container,
        contentColor = color.content,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Перекличка", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(remaining, fontSize = 14.sp)
            }
            ProgressLine(
                fraction = if (total == 0) 0f else rollcall.answered.size.toFloat() / total,
                color = color.content,
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            )
            Text(line, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}
