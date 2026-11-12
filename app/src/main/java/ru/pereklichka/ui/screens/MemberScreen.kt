package ru.pereklichka.ui.screens

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.pereklichka.R
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.rules.Presence
import ru.pereklichka.ui.FakeGroupViews
import ru.pereklichka.ui.components.AppScreen
import ru.pereklichka.ui.components.BackButton
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.ButtonKind
import ru.pereklichka.ui.components.CardShape
import ru.pereklichka.ui.components.HintText
import ru.pereklichka.ui.components.LOW_BATTERY_PERCENT
import ru.pereklichka.ui.components.LeaderBadge
import ru.pereklichka.ui.components.StatusAvatar
import ru.pereklichka.ui.components.StatusChip
import ru.pereklichka.ui.components.StatusInfo
import ru.pereklichka.ui.components.StatusKind
import ru.pereklichka.ui.components.SymIcon
import ru.pereklichka.ui.components.directionWords
import ru.pereklichka.ui.components.formatAge
import ru.pereklichka.ui.components.formatDistance
import ru.pereklichka.ui.components.placeOf
import ru.pereklichka.ui.components.raisedByText
import ru.pereklichka.ui.components.spokenPlace
import ru.pereklichka.ui.components.statusColor
import ru.pereklichka.ui.components.statusOf
import ru.pereklichka.ui.theme.PereklichkaTheme
import kotlin.math.roundToInt

private val CompassSize = 220.dp

/**
 * Карточка участника: крупный аватар с кольцом статуса, имя, плашка статуса (у тревоги ещё кто её объявил),
 * круг со стрелкой на человека, расстоянием до его последней точки и стороной света словами. Ниже сведения: когда
 * слышали, кто слышал последним (`lastHeardBy`), точность и возраст позиции, заряд (красным, если низкий).
 *
 * Внизу «Вижу, всё в порядке», пока о человеке есть активная тревога, иначе «Объявить тревогу» с подтверждением,
 * чтобы не нажать случайно. О себе ни того ни другого: для себя есть SOS, и круга со стрелкой тоже нет.
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

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)) { BackButton(onBack) }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Header(view, member)
                Column(Modifier.padding(top = 18.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (!member.isMe) Compass(member)
                    Details(view, member)
                    if (member.isMe) HintText("Если вам нужна помощь, нажмите SOS на экране группы.")
                }
            }
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (member.hasActiveAlarm && !member.isMe) {
                    BigButtonFull("Вижу, всё в порядке", { onSeenOk(member.id) }, icon = R.drawable.sym_visibility, large = true)
                    Text(
                        "Нажмите, только если видите этого человека своими глазами",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                } else if (!member.isMe) {
                    BigButtonFull("Объявить тревогу", { confirmAlarm = true }, ButtonKind.DANGER, icon = R.drawable.sym_warning)
                }
            }
        }
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

/** Аватар 72 dp, имя 26 sp и плашки статуса и руководителя под ним. */
@Composable
private fun Header(view: GroupView, member: MemberView) {
    StatusAvatar(member, size = 72.dp)
    Text(
        if (member.isMe) "${member.name} · вы" else member.name,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 14.dp).semantics { heading() },
    )
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChip(chipStatus(view, member))
        if (member.isLeader) LeaderBadge()
    }
}

/** Статус для плашки: у тревоги (не SOS) после слова «Тревога» стоит, кто её объявил. */
private fun chipStatus(view: GroupView, member: MemberView): StatusInfo {
    val status = statusOf(member)
    if (member.hasSos || !member.hasActiveAlarm) return status
    val raisedBy = raisedByText(view.alarms.filter { it.target == member.id && !it.isSos }, view.me) ?: return status
    return StatusInfo(StatusKind.ALARM, "Тревога · $raisedBy")
}

/**
 * Круг 220 dp, где север сверху: стрелка цвета статуса повёрнута на азимут, под ней расстояние и сторона света.
 * Ближе 5 м вместо стрелки «рядом», а без позиции подпись, чьей позиции не хватает.
 */
@Composable
private fun Compass(member: MemberView) {
    val place = placeOf(member)
    val bearing = member.bearingDeg
    val accent = statusColor(statusOf(member).kind).accent
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val description = spokenPlace(member)?.let { "До последней точки $it" } ?: noPlaceText(member)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(CompassSize),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "С",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = secondary,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
                    when {
                        place == null -> Text(noPlaceText(member), fontSize = 17.sp, textAlign = TextAlign.Center, color = secondary)
                        place.meters == null -> {
                            Text("рядом", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            Text("ближе 5 м", fontSize = 14.sp, color = secondary)
                        }
                        else -> {
                            if (bearing != null) {
                                SymIcon(R.drawable.sym_navigation_fill, Modifier.rotate(bearing.toFloat()), tint = accent, size = 72.dp)
                            }
                            Text(
                                formatDistance(member.distanceM).orEmpty(),
                                fontSize = 30.sp,
                                lineHeight = 36.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            directionWords(member)?.let { Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = secondary) }
                        }
                    }
                }
            }
        }
    }
}

private fun noPlaceText(member: MemberView): String =
    if (member.position == null) "Позиции участника пока нет" else "Нет вашей позиции, расстояние не посчитать"

/** Сведения списком: иконка, подпись слева, значение справа. */
@Composable
private fun Details(view: GroupView, member: MemberView) {
    val heardBy = member.lastHeardBy?.let { id ->
        if (id == view.me) "вы" else view.members.firstOrNull { it.id == id }?.name
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            val rows = buildList<@Composable ColumnScope.() -> Unit> {
                if (!member.isMe) {
                    add { DetailRow(R.drawable.sym_schedule, "Слышали", heardText(member)) }
                    if (!member.heardDirectlyNow && member.lastContact != null) {
                        add { DetailRow(R.drawable.sym_cell_tower, "Кто слышал последним", heardBy ?: "сам участник") }
                    }
                }
                add { DetailRow(R.drawable.sym_my_location, "Точность позиции", accuracyText(member)) }
                if (member.position != null) {
                    add { DetailRow(R.drawable.sym_location_on, "Позиция определена", formatAge(member.positionAge)) }
                }
                add { BatteryRow(member.batteryPercent) }
            }
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                row()
            }
        }
    }
}

/** «сейчас, напрямую» для того, кого мой телефон слышит сейчас, иначе «4 мин назад». */
private fun heardText(member: MemberView): String = when {
    member.heardDirectlyNow -> "сейчас, напрямую"
    member.lastContact == null -> "ещё не слышали"
    else -> formatAge(member.lastContact)
}

private fun accuracyText(member: MemberView): String {
    if (member.position == null) return "позиции нет"
    return member.accuracyM?.let { "±${it.roundToInt()} м" } ?: "неизвестна"
}

@Composable
private fun BatteryRow(percent: Int?) {
    val low = percent != null && percent <= LOW_BATTERY_PERCENT
    val color = if (low) statusColor(StatusKind.LOST).content else null
    DetailRow(batteryIcon(percent), "Заряд", percent?.let { "$it %" } ?: "нет данных", color)
}

/** Батарейка с числом делений по заряду, чтобы иконка не обещала полный заряд при 30 %. */
@DrawableRes
private fun batteryIcon(percent: Int?): Int = when {
    percent == null -> R.drawable.sym_battery_3_bar_fill
    percent <= LOW_BATTERY_PERCENT -> R.drawable.sym_battery_1_bar_fill
    percent <= 50 -> R.drawable.sym_battery_3_bar_fill
    percent <= 85 -> R.drawable.sym_battery_5_bar_fill
    else -> R.drawable.sym_battery_full_fill
}

@Composable
private fun DetailRow(@DrawableRes icon: Int, label: String, value: String, valueColor: Color? = null) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SymIcon(icon, tint = valueColor ?: secondary)
        Text(label, fontSize = 15.sp, color = secondary, modifier = Modifier.weight(1f))
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

@Preview(name = "Карточка: тревога")
@Preview(name = "Карточка: тревога, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MemberAlarmPreview() {
    val view = FakeGroupViews.leader
    PereklichkaTheme { MemberScreen(view, view.alarms.first().target, {}, {}, {}) }
}

@Preview(name = "Карточка: отлучка, участник")
@Composable
private fun MemberAwayPreview() {
    val view = FakeGroupViews.participant
    val away = view.members.firstOrNull { it.presence == Presence.AWAY } ?: view.members.last()
    PereklichkaTheme { MemberScreen(view, away.id, {}, {}, {}) }
}

@Preview(name = "Карточка: рядом")
@Composable
private fun MemberNearPreview() {
    val view = FakeGroupViews.leader
    val near = view.members.first { it.heardDirectlyNow }
    PereklichkaTheme { MemberScreen(view, near.id, {}, {}, {}) }
}

@Preview(name = "Карточка: я")
@Composable
private fun MemberMePreview() {
    val view = FakeGroupViews.participant
    PereklichkaTheme { MemberScreen(view, view.me, {}, {}, {}) }
}
