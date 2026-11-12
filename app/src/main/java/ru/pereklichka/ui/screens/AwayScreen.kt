package ru.pereklichka.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.pereklichka.R
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.rules.Presence
import ru.pereklichka.ui.FakeGroupViews
import ru.pereklichka.ui.components.BigButton
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.ButtonKind
import ru.pereklichka.ui.components.CardShape
import ru.pereklichka.ui.components.StatusAvatar
import ru.pereklichka.ui.components.SymIcon
import ru.pereklichka.ui.components.formatRemaining
import ru.pereklichka.ui.components.plural
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.statusColors
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Сроки отлучки, которые можно выбрать (ФТ-14). */
private val AWAY_MINUTES = listOf(5, 10, 20)

/** Срок, выбранный заранее, как в макете. */
private const val DEFAULT_MINUTES = 10

private val TileShape = RoundedCornerShape(24.dp)
private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Отлучка: крестик «Закрыть», «Отойду ненадолго», три крупные плитки 5, 10 и 20 минут, карточка «Так вас увидит
 * группа» с аватаром и временем возвращения, карточка о напоминании за минуту до конца срока и внизу кнопка, которая
 * называет выбранный срок: «Отойти на 10 минут». Она объявляет отлучку и закрывает экран.
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
    // Без отлучки срок выбран всегда: если она кончилась, пока экран открыт, главная кнопка не закроет его впустую.
    val selected = picked ?: if (isAway) null else DEFAULT_MINUTES

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Header(if (isAway) "Вы отошли" else "Отойду ненадолго", onClose)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (awayRemainingMs != null) {
                    AwayStatus(awayRemainingMs)
                    Hint("Новый срок заменит прежний.")
                } else {
                    Hint("Пока вы отошли, группа не поднимет тревогу, даже если перестанет вас слышать.")
                }
                DurationTiles(selected, onSelect = { picked = it })
                if (me != null && selected != null) SeenByGroup(me, selected)
                ReminderCard()
            }
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (isAway) {
                    BigButtonFull("Я вернулся", { onCancelAway(); onClose() }, icon = R.drawable.sym_check, large = true)
                    BigButton(
                        text = selected?.let { "Отойти заново на ${minutesText(it)}" } ?: "Выберите новый срок",
                        onClick = { selected?.let { onAway(it); onClose() } },
                        modifier = Modifier.fillMaxWidth(),
                        kind = ButtonKind.SECONDARY,
                        enabled = selected != null,
                    )
                } else {
                    BigButtonFull(
                        "Отойти на ${minutesText(selected ?: DEFAULT_MINUTES)}",
                        { onAway(selected ?: DEFAULT_MINUTES); onClose() },
                        icon = R.drawable.sym_directions_walk,
                        large = true,
                    )
                }
            }
        }
    }
}

private fun minutesText(minutes: Int) = "$minutes ${plural(minutes, "минуту", "минуты", "минут")}"

/** Шапка с крестиком слева: экран закрывается без отлучки. */
@Composable
private fun Header(title: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onClose) { SymIcon(R.drawable.sym_close, contentDescription = "Закрыть") }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
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

/** Плитка 120 dp: число 40 sp и «минут» под ним. Выбранная залита основным цветом. */
@Composable
private fun DurationTile(minutes: Int, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .height(120.dp)
            .selectable(selected = isSelected, onClick = onClick, role = Role.RadioButton)
            .semantics(mergeDescendants = true) { contentDescription = minutesText(minutes) },
        shape = TileShape,
        color = if (isSelected) scheme.primary else scheme.surfaceContainerLowest,
        contentColor = if (isSelected) scheme.onPrimary else scheme.onSurface,
        border = if (isSelected) null else BorderStroke(1.dp, scheme.outline),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("$minutes", fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium)
            Text(
                plural(minutes, "минута", "минуты", "минут"),
                fontSize = 14.sp,
                color = if (isSelected) scheme.onPrimary.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
            )
        }
    }
}

/** «Так вас увидит группа»: мой аватар с кольцом отлучки и время, до которого я отошёл. */
@Composable
private fun SeenByGroup(me: MemberView, minutes: Int) {
    val until = LocalTime.now().plusMinutes(minutes.toLong()).format(TimeFormat)
    val line = "${me.name} · отлучка до $until"
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "Так вас увидит группа: $line" },
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusAvatar(me.copy(presence = Presence.AWAY, awayRemainingMs = minutes * 60_000L))
            Column {
                Text("Так вас увидит группа", fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(line, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Напоминание за минуту до конца срока: его показывает уведомление, экран только предупреждает о нём. */
@Composable
private fun ReminderCard() {
    val color = MaterialTheme.statusColors.away
    Surface(modifier = Modifier.fillMaxWidth(), shape = CardShape, color = color.container, contentColor = color.content) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SymIcon(R.drawable.sym_notifications_fill)
            Text(
                "За минуту до конца телефон спросит, вернулись ли вы. Если не ответите и группа вас не слышит, " +
                    "будет «Не слышно».",
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

/** Сколько осталось от идущей отлучки и до которого часа. */
@Composable
private fun AwayStatus(remainingMs: Long) {
    val color = MaterialTheme.statusColors.away
    val remaining = formatRemaining(remainingMs)
    val until = LocalTime.now().plusSeconds((remainingMs.coerceAtLeast(0) + 999) / 1000).format(TimeFormat)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "Отлучка, $remaining, до $until" },
        shape = CardShape,
        color = color.container,
        contentColor = color.content,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SymIcon(R.drawable.sym_schedule_fill, size = 28.dp)
            Column {
                Text("Отлучка до $until", fontSize = 14.sp, lineHeight = 20.sp)
                Text(remaining, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

@Preview(name = "Отлучка: выбор срока")
@Preview(name = "Отлучка: выбор срока, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
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
