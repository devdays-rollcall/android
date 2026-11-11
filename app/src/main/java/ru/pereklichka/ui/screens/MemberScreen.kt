package ru.pereklichka.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.rules.Presence
import ru.pereklichka.ui.FakeGroupViews
import ru.pereklichka.ui.components.AppScreen
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.ButtonKind
import ru.pereklichka.ui.components.CardLabel
import ru.pereklichka.ui.components.HintText
import ru.pereklichka.ui.components.InfoCard
import ru.pereklichka.ui.components.LeaderBadge
import ru.pereklichka.ui.components.StatusChip
import ru.pereklichka.ui.components.formatAge
import ru.pereklichka.ui.components.formatBattery
import ru.pereklichka.ui.components.formatPlace
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.spacing
import kotlin.math.roundToInt

/**
 * Карточка участника: статус и когда слышали, расстояние до последней точки, заряд, точность позиции,
 * кто слышал последним (`lastHeardBy`). Внизу «Объявить тревогу» (с подтверждением, чтобы не нажать случайно)
 * и «Вижу, в порядке», пока о человеке есть активная тревога.
 *
 * О себе «Объявить тревогу» не показывается: для себя есть SOS.
 *
 * Задача: docs/team/valentin-ui.md
 */
@Composable
fun MemberScreen(
    view: GroupView,
    memberId: MemberId,
    onRaiseAlarm: (MemberId) -> Unit,
    onSeenOk: (MemberId) -> Unit,
    onBack: () -> Unit,
) {
    val member = view.members.firstOrNull { it.id == memberId }
    if (member == null) {
        AppScreen(title = "Участник не найден", onBack = onBack) {
            HintText("Этого человека нет в группе.")
        }
        return
    }
    var confirmAlarm by rememberSaveable { mutableStateOf(false) }

    AppScreen(
        title = if (member.isMe) "${member.name} (вы)" else member.name,
        onBack = onBack,
        bottom = {
            if (member.hasActiveAlarm && !member.isMe) {
                BigButtonFull("Вижу, в порядке", { onSeenOk(member.id) })
            } else if (!member.isMe) {
                BigButtonFull("Объявить тревогу", { confirmAlarm = true }, ButtonKind.DANGER)
            }
        },
    ) {
        StatusCard(member)
        PositionCard(view, member)
        if (member.isMe) HintText("Если вам нужна помощь, нажмите SOS на экране группы.")
    }

    if (confirmAlarm) {
        AlertDialog(
            onDismissRequest = { confirmAlarm = false },
            title = { Text("Объявить тревогу?") },
            text = { Text("Все телефоны группы получат тревогу о человеке ${member.name} со звуком.") },
            confirmButton = {
                TextButton(onClick = { confirmAlarm = false; onRaiseAlarm(member.id) }) {
                    Text("Объявить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmAlarm = false }) { Text("Отмена") } },
        )
    }
}

/** Статус, когда слышали, расстояние и заряд: верхняя карточка макета. */
@Composable
private fun StatusCard(member: MemberView) {
    InfoCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s)) {
                StatusChip(member)
                if (member.isLeader) LeaderBadge()
            }
            if (!member.isMe) {
                Text(
                    heardText(member),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = MaterialTheme.spacing.m)) {
            if (!member.isMe) {
                BigValue(
                    "До последней точки",
                    formatPlace(member.distanceM, member.bearingDeg) ?: "нет позиции",
                    Modifier.weight(1f),
                )
            }
            BigValue("Заряд", formatBattery(member.batteryPercent) ?: "нет данных", Modifier.weight(1f))
        }
    }
}

/** «слышали 3 мин назад», для только что услышанного напрямую «на связи». */
private fun heardText(member: MemberView): String = when {
    member.heardDirectlyNow -> "на связи напрямую"
    member.lastContact == null -> "ещё не слышали"
    else -> "слышали ${formatAge(member.lastContact)}"
}

/** Позиция: точность, давность и кто слышал последним. */
@Composable
private fun PositionCard(view: GroupView, member: MemberView) {
    val heardBy = member.lastHeardBy?.let { id ->
        if (id == view.me) "вы" else view.members.firstOrNull { it.id == id }?.name
    }
    InfoCard {
        CardLabel("Точность позиции")
        Text(
            when (val accuracy = member.accuracyM) {
                null -> if (member.position == null) "Позиции нет" else "Точность неизвестна"
                else -> if (member.position == null) "Позиции нет" else "±${accuracy.roundToInt()} м"
            },
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = MaterialTheme.spacing.s),
        )
        if (member.position != null) {
            CardLabel("Позиция определена")
            Text(formatAge(member.positionAge), fontSize = 16.sp, modifier = Modifier.padding(bottom = MaterialTheme.spacing.s))
        }
        if (!member.isMe) {
            CardLabel("Кто слышал последним")
            Text(
                when {
                    heardBy != null -> heardBy
                    member.lastContact != null -> "${member.name} сам(а) передал(а) своё состояние"
                    else -> "Никто"
                },
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun BigValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        CardLabel(label)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Preview(name = "Карточка: не слышно")
@Preview(name = "Карточка: не слышно, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MemberLostPreview() {
    val view = FakeGroupViews.leader
    val lost = view.members.firstOrNull { it.presence == Presence.LOST } ?: view.members.last()
    PereklichkaTheme { MemberScreen(view, lost.id, {}, {}, {}) }
}

@Preview(name = "Карточка: тревога")
@Composable
private fun MemberAlarmPreview() {
    val view = FakeGroupViews.participant
    PereklichkaTheme { MemberScreen(view, view.alarms.first().target, {}, {}, {}) }
}

@Preview(name = "Карточка: я")
@Composable
private fun MemberMePreview() {
    val view = FakeGroupViews.participant
    PereklichkaTheme { MemberScreen(view, view.me, {}, {}, {}) }
}
