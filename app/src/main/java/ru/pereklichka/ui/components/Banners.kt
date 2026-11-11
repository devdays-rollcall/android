package ru.pereklichka.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import ru.pereklichka.domain.rules.AlarmView
import ru.pereklichka.domain.rules.NearbyPrompt
import ru.pereklichka.domain.rules.RollcallView
import ru.pereklichka.ui.theme.spacing
import ru.pereklichka.ui.theme.statusColors

@Composable
private fun Banner(
    container: Color,
    content: Color,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(title, subtitle).joinToString(". ") },
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
    ) {
        Column(Modifier.padding(MaterialTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Активная тревога или SOS: сплошной красный блок, нажатие открывает экран тревог. */
@Composable
fun AlarmBanner(alarm: AlarmView, onClick: () -> Unit) {
    val color = MaterialTheme.statusColors.alarm
    Banner(
        container = color.container,
        content = color.content,
        title = if (alarm.isSos) "SOS: ${alarm.targetName}" else "Тревога: ${alarm.targetName}",
        subtitle = if (alarm.isSos) formatAge(alarm.age) else "Объявил(а) ${alarm.raisedByName} · ${formatAge(alarm.age)}",
        onClick = onClick,
    )
}

/** «Ещё N тревог» под тремя показанными. */
@Composable
fun MoreAlarmsBanner(count: Int, onClick: () -> Unit) {
    val color = MaterialTheme.statusColors.lost
    Banner(color.container, color.content, "Ещё $count: открыть все тревоги", null, onClick)
}

/** Телефон участника, о котором объявлена тревога, рядом. Подтверждает человек на карточке участника. */
@Composable
fun PromptBanner(prompt: NearbyPrompt, onClick: () -> Unit) {
    val color = MaterialTheme.statusColors.away
    Banner(
        container = color.container,
        content = color.content,
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
    Banner(color.container, color.content, warning.title, warning.subtitle, onClick)
}

/** Идёт перекличка: руководитель видит, сколько ответило, участник видит, сколько осталось времени. */
@Composable
fun RollcallBanner(rollcall: RollcallView, iAmLeader: Boolean, iAnswered: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.statusColors.ok
    val total = rollcall.answered.size + rollcall.silentNearby.size + rollcall.notHeard.size
    val (title, subtitle) = when {
        iAmLeader -> "Идёт перекличка" to "Ответили ${rollcall.answered.size} из $total · ${formatRemaining(rollcall.remainingMs)}"
        iAnswered -> "Вы ответили на перекличку" to formatRemaining(rollcall.remainingMs)
        else -> "Идёт перекличка: нажмите «Я здесь»" to formatRemaining(rollcall.remainingMs)
    }
    Banner(color.container, color.content, title, subtitle, onClick)
}
