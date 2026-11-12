package ru.pereklichka.ui.screens

import android.content.res.Configuration
import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.pereklichka.R
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.rules.Presence
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.domain.time.Age
import ru.pereklichka.service.Exchange
import ru.pereklichka.ui.FakeGroupViews
import ru.pereklichka.ui.components.AppScreen
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.ButtonKind
import ru.pereklichka.ui.components.IconCircle
import ru.pereklichka.ui.components.ListCard
import ru.pereklichka.ui.components.ListRow
import ru.pereklichka.ui.components.SectionHeader
import ru.pereklichka.ui.components.SymIcon
import ru.pereklichka.ui.components.TrackStatus
import ru.pereklichka.ui.components.WarningBanner
import ru.pereklichka.ui.components.formatAge
import ru.pereklichka.ui.components.plural
import ru.pereklichka.ui.components.rememberPermissionWarning
import ru.pereklichka.ui.components.trackLine
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.StatusColor
import ru.pereklichka.ui.theme.spacing
import ru.pereklichka.ui.theme.statusColors

/**
 * Завершение маршрута у руководителя. Открывается из меню экрана группы, пока маршрут идёт.
 *
 * Пока маршрут идёт, сверху сводка «<группа> · в пути 2 ч 14 мин» с тремя плитками (на связи, в отлучке, не слышно),
 * под ней предупреждение о каждом, кого не слышно, и что случится после завершения. Внизу «Завершить для всей группы»
 * с подтверждением и «Вернуться к группе». Если служба не запущена или не хватает разрешений, над сводкой
 * предупреждение со входом на экран разрешений. После завершения сводка показывает, сколько шёл маршрут и когда
 * завершён, а внизу то же, что на экране группы ([FinishedActions]): «Выключить», пока обмен работает,
 * и «Новый маршрут».
 */
@Composable
fun FinishRouteScreen(
    view: GroupView,
    serviceStarted: Boolean?,
    exchange: Exchange,
    trackStatus: TrackStatus?,
    onFinish: () -> Unit,
    onStopService: () -> Unit,
    onNewRoute: () -> Unit,
    onPermissions: () -> Unit,
    onBack: () -> Unit,
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val finished = view.route == RouteState.FINISHED

    AppScreen(
        title = if (finished) "Маршрут завершён" else "Завершить маршрут",
        onBack = onBack,
        bottom = {
            if (finished) {
                FinishedActions(view, exchange, onStopService, onNewRoute)
            } else {
                BigButtonFull("Завершить для всей группы", { confirm = true }, ButtonKind.DANGER, R.drawable.sym_flag, large = true)
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Вернуться к группе", style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) {
        if (finished) {
            FinishedSummary(view, trackStatus)
        } else {
            rememberPermissionWarning(serviceStarted)?.let { WarningBanner(it, onPermissions) }
            SummaryCard(view)
            LostWarning(view.members.filter { !it.isMe && it.presence == Presence.LOST })
            AfterFinish()
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Завершить маршрут?") },
            text = {
                Text("Маршрут завершится у всех в группе: трек перестанет писаться, а «Не слышно» больше не придёт.")
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; onFinish() }) {
                    Text("Завершить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
        )
    }
}

/**
 * Низ экрана после завершения маршрута, общий для списка группы и экрана завершения. Пока служба передаёт
 * завершение остальным ([exchange] равен [Exchange.On]), видно, сколько минут она ещё может работать, и «Выключить»
 * останавливает её сразу, а группа остаётся на телефоне. «Новый маршрут» ведёт на старт, где можно создать группу
 * или вступить в другую. Сама группа при этом ничего не теряет: она станет прошлым маршрутом, когда появится новая.
 */
@Composable
internal fun FinishedActions(
    view: GroupView,
    exchange: Exchange,
    onStopService: () -> Unit,
    onNewRoute: () -> Unit,
) {
    val minutesLeft = rememberMinutesLeft((exchange as? Exchange.On)?.stopsAtElapsedMs)
    Text(
        finishedText(exchange, minutesLeft, hasOthers = view.members.any { !it.isMe }, cancelled = view.routeStartedAgo == null),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.s),
    )
    if (exchange is Exchange.On) BigButtonFull("Выключить", onStopService, ButtonKind.SECONDARY, R.drawable.sym_power_settings_new)
    BigButtonFull("Новый маршрут", onNewRoute, icon = R.drawable.sym_add)
}

/**
 * Подпись над кнопками после завершения: идёт ли ещё обмен и сколько минут он может продлиться. [minutesLeft] null,
 * пока служба не назначила срок. [cancelled] true, если руководитель отменил сбор до «Выходим».
 */
internal fun finishedText(exchange: Exchange, minutesLeft: Long?, hasOthers: Boolean, cancelled: Boolean = false): String {
    val what = if (cancelled) "Сбор отменён" else "Маршрут завершён"
    return when (exchange) {
        is Exchange.On -> if (minutesLeft == null) {
            "$what. Приложение передаёт это остальным."
        } else {
            "$what. Приложение передаёт это остальным, пока все не получат, но не дольше $minutesLeft мин."
        }
        is Exchange.Off -> if (exchange.finishConfirmedByAll && hasOthers) {
            "$what, все участники об этом знают. Обмен с группой выключен."
        } else {
            "$what, обмен с группой выключен."
        }
    }
}

/** Сколько минут с округлением вверх осталось до [stopsAtElapsedMs]; пересчёт раз в секунду, null без срока. */
@Composable
private fun rememberMinutesLeft(stopsAtElapsedMs: Long?): Long? {
    val leftMs by produceState<Long?>(null, stopsAtElapsedMs) {
        while (stopsAtElapsedMs != null) {
            value = stopsAtElapsedMs - SystemClock.elapsedRealtime()
            delay(1_000)
        }
        value = null
    }
    return leftMs?.let { ((it + 59_999) / 60_000).coerceAtLeast(1) }
}

/** «Эльбрус · в пути 2 ч 14 мин» и три плитки по участникам, кроме меня: на связи, в отлучке, не слышно. */
@Composable
private fun SummaryCard(view: GroupView) {
    val others = view.members.filterNot { it.isMe }
    val colors = MaterialTheme.statusColors
    val title = listOfNotNull(view.group.name, view.routeStartedAgo?.let { "в пути ${formatDuration(it)}" }).joinToString(" · ")
    ListCard {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(others.count { it.presence == Presence.OK }, "на связи", colors.ok, Modifier.weight(1f))
                StatTile(others.count { it.presence == Presence.AWAY }, "в отлучке", colors.away, Modifier.weight(1f))
                StatTile(others.count { it.presence == Presence.LOST }, "не слышно", colors.lost, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatTile(count: Int, label: String, color: StatusColor, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(color.container, RoundedCornerShape(18.dp))
            .padding(vertical = 14.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $count" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$count", color = color.content, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
        Text(label, color = color.content, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
    }
}

/**
 * Красная карточка о тех, кого не слышно: по строке на человека («Валентин не на связи 4 минуты») и что после
 * завершения «Не слышно» больше не придёт. Без таких участников карточки нет.
 */
@Composable
private fun LostWarning(lost: List<MemberView>) {
    if (lost.isEmpty()) return
    val colors = MaterialTheme.statusColors.lost
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = colors.container, contentColor = colors.content) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SymIcon(R.drawable.sym_warning_fill)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                lost.forEach { member ->
                    Text(lostLine(member), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                }
                Text(
                    "После завершения «Не слышно» больше не придёт, поэтому проверьте, что все с вами.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** «Валентин не на связи 4 минуты», «Валентин не на связи примерно 2 часа», «Валентин: связи ещё не было». */
private fun lostLine(member: MemberView): String {
    val age = member.lastContact ?: return "${member.name}: связи ещё не было"
    val minutes = (age.ms / 60_000).toInt()
    val span = when {
        minutes < 1 -> "меньше минуты"
        minutes < 60 -> "$minutes ${plural(minutes, "минуту", "минуты", "минут")}"
        else -> (minutes / 60).let { "$it ${plural(it, "час", "часа", "часов")}" }
    }
    return "${member.name} не на связи ${if (age.approximate) "примерно " else ""}$span"
}

/** Что произойдёт после завершения: трек остановится, обмен продлится до подтверждения всеми, трек уйдёт на сервер. */
@Composable
private fun AfterFinish() {
    SectionHeader("После завершения")
    ListCard(
        listOf(
            { ConsequenceRow(R.drawable.sym_location_off, "Трек остановится у всех") },
            { ConsequenceRow(R.drawable.sym_sync, "Обмен, пока все не получат завершение", "Не дольше 15 минут") },
            { ConsequenceRow(R.drawable.sym_cloud_upload, "Трек уйдёт на сервер", "Когда появится интернет") },
        ),
    )
}

@Composable
private fun ConsequenceRow(@DrawableRes icon: Int, title: String, subtitle: String? = null) {
    val scheme = MaterialTheme.colorScheme
    ListRow(title, subtitle = subtitle, leading = { IconCircle(icon, scheme.surfaceContainerHigh, scheme.onSurfaceVariant) })
}

/**
 * Сводка завершённого маршрута: сколько он шёл (от «Выходим» до завершения) и когда завершён, ниже что с отправкой
 * трека ([trackLine]). Без точек трека строки о нём нет.
 */
@Composable
private fun FinishedSummary(view: GroupView, trackStatus: TrackStatus?) {
    val ok = MaterialTheme.statusColors.ok
    val started = view.routeStartedAgo
    val finishedAgo = view.routeFinishedAgo
    val duration = if (started != null && finishedAgo != null) {
        "в пути ${formatDuration(Age((started.ms - finishedAgo.ms).coerceAtLeast(0), started.approximate || finishedAgo.approximate))}"
    } else {
        null
    }
    val subtitle = listOfNotNull(duration, finishedAgo?.let { "завершён ${formatAge(it)}" }).joinToString(" · ")
    val track = trackStatus?.let(::trackLine)
    ListCard(
        buildList<@Composable () -> Unit> {
            add {
                ListRow(
                    view.group.name,
                    subtitle = subtitle.ifEmpty { null },
                    leading = { IconCircle(R.drawable.sym_flag, ok.container, ok.content) },
                )
            }
            if (track != null) add { ConsequenceRow(R.drawable.sym_cloud_upload, track.replaceFirstChar(Char::uppercase)) }
        },
    )
}

/** «2 ч 14 мин», «14 мин», «меньше минуты»; с `approximate` впереди «примерно». */
private fun formatDuration(age: Age): String {
    val minutes = age.ms / 60_000
    val text = when {
        minutes < 1 -> "меньше минуты"
        minutes < 60 -> "$minutes мин"
        minutes % 60 == 0L -> "${minutes / 60} ч"
        else -> "${minutes / 60} ч ${minutes % 60} мин"
    }
    return if (age.approximate) "примерно $text" else text
}

@Preview(name = "Завершение: руководитель")
@Preview(name = "Завершение: руководитель, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FinishLeaderPreview() {
    PereklichkaTheme { FinishRouteScreen(FakeGroupViews.leader, serviceStarted = true, Exchange.On(), null, {}, {}, {}, {}, {}) }
}

@Preview(name = "Завершение: маршрут завершён")
@Composable
private fun FinishDonePreview() {
    PereklichkaTheme {
        FinishRouteScreen(
            FakeGroupViews.leader.copy(
                route = RouteState.FINISHED,
                routeFinishedAgo = Age(3 * 60_000, approximate = false),
                routeFinishedSeq = 9,
                rollcall = null,
            ),
            serviceStarted = true,
            Exchange.On(SystemClock.elapsedRealtime() + 12 * 60_000),
            TrackStatus(points = 240, unsent = 12, hasServer = true),
            {}, {}, {}, {}, {},
        )
    }
}
