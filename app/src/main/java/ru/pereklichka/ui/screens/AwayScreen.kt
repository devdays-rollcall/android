package ru.pereklichka.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.Presence
import ru.pereklichka.ui.FakeGroupViews
import ru.pereklichka.ui.components.AppScreen
import ru.pereklichka.ui.components.HintText
import ru.pereklichka.ui.components.BigButton
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.ButtonKind
import ru.pereklichka.ui.components.formatRemaining
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.spacing
import ru.pereklichka.ui.theme.statusColors
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Сроки отлучки, которые можно выбрать (ФТ-14). */
private val AWAY_MINUTES = listOf(5, 10, 20)

/** Срок, выбранный заранее, как в макете. */
private const val DEFAULT_MINUTES = 10

private val TileMinHeight = 76.dp
private val TileBorder = 2.dp
private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Отлучка: «Отлучусь на…», три плитки 5, 10 и 20 минут, карточка «Что увидят
 * остальные» и внизу «Подтвердить» и «Отмена». Подтверждение объявляет отлучку и закрывает экран.
 *
 * Если отлучка уже идёт (`awayRemainingMs` у меня в `view.members`), сверху видно, сколько осталось, главная кнопка
 * становится «Я вернулся», а плитками можно выбрать новый срок: новая отлучка заменяет прежнюю.
 *
 * Задача: docs/team/valentin-ui.md
 */
@Composable
fun AwayScreen(
    view: GroupView,
    onAway: (minutes: Int) -> Unit,
    onCancelAway: () -> Unit,
    onClose: () -> Unit,
) {
    val me = view.members.firstOrNull { it.isMe }
    val awayRemainingMs = me?.awayRemainingMs
    val isAway = awayRemainingMs != null
    var picked by rememberSaveable { mutableStateOf<Int?>(null) }
    // Без отлучки срок выбран всегда: если она кончилась, пока экран открыт, «Подтвердить» не закроет его впустую.
    val selected = picked ?: if (isAway) null else DEFAULT_MINUTES
    AppScreen(
        title = if (isAway) "Вы отошли" else "Отлучусь на…",
        onBack = onClose,
        bottom = {
            if (isAway) {
                BigButtonFull("Я вернулся", { onCancelAway(); onClose() })
                BigButton(
                    text = selected?.let { "Изменить срок на $it мин" } ?: "Выберите новый срок",
                    onClick = { selected?.let { onAway(it); onClose() } },
                    modifier = Modifier.fillMaxWidth(),
                    kind = ButtonKind.SECONDARY,
                    enabled = selected != null,
                )
            } else {
                BigButtonFull("Подтвердить", { selected?.let { onAway(it) }; onClose() })
                BigButtonFull("Отмена", onClose, ButtonKind.SECONDARY)
            }
        },
    ) {
        if (awayRemainingMs != null) {
            AwayStatus(awayRemainingMs)
            HintText("Новый срок заменит прежний")
        } else {
            HintText("Группа увидит, что вы отошли, и не станет тревожиться до конца срока")
        }
        DurationTiles(selected, onSelect = { picked = it })
        selected?.let { minutes -> WhatOthersSee(me?.name, minutes) }
    }
}

@Composable
private fun DurationTiles(selected: Int?, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AWAY_MINUTES.forEach { minutes ->
            DurationTile(
                minutes = minutes,
                isSelected = minutes == selected,
                onClick = { onSelect(minutes) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DurationTile(minutes: Int, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .heightIn(min = TileMinHeight)
            .selectable(selected = isSelected, onClick = onClick, role = Role.RadioButton)
            .semantics(mergeDescendants = true) { contentDescription = "$minutes минут" },
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) scheme.primaryContainer else scheme.surfaceContainerLowest,
        contentColor = scheme.onSurface,
        border = BorderStroke(TileBorder, if (isSelected) scheme.primary else scheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(vertical = MaterialTheme.spacing.m),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("$minutes", fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
            Text("минут", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun WhatOthersSee(myName: String?, minutes: Int) {
    val until = LocalTime.now().plusMinutes(minutes.toLong()).format(TimeFormat)
    val line = listOfNotNull(myName, "отлучка до $until").joinToString(" · ")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "Что увидят остальные: $line" },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(MaterialTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
            Text("Что увидят остальные", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(line, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AwayStatus(remainingMs: Long) {
    val color = MaterialTheme.statusColors.away
    val remaining = formatRemaining(remainingMs)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "Отлучка, $remaining" },
        shape = MaterialTheme.shapes.large,
        color = color.container,
        contentColor = color.content,
    ) {
        Column(Modifier.padding(MaterialTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
            Text("Отлучка", style = MaterialTheme.typography.labelMedium)
            Text(remaining, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Preview(name = "Отлучка: руководитель выбирает срок")
@Preview(name = "Отлучка: руководитель выбирает срок, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AwayLeaderPreview() {
    PereklichkaTheme { AwayScreen(FakeGroupViews.leader, {}, {}, {}) }
}

@Preview(name = "Отлучка: участник уже отошёл")
@Composable
private fun AwayParticipantPreview() {
    val view = FakeGroupViews.participant
    val away = view.copy(
        members = view.members.map { if (it.isMe) it.copy(presence = Presence.AWAY, awayRemainingMs = 6 * 60_000L) else it },
    )
    PereklichkaTheme { AwayScreen(away, {}, {}, {}) }
}

