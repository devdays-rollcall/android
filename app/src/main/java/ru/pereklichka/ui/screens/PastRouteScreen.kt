package ru.pereklichka.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.pereklichka.R
import ru.pereklichka.data.PastRoute
import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.ui.FakeGroupViews
import ru.pereklichka.ui.PastRouteDetails
import ru.pereklichka.ui.PastRouteItem
import ru.pereklichka.ui.components.AppScreen
import ru.pereklichka.ui.components.HistoryLine
import ru.pereklichka.ui.components.IconCircle
import ru.pereklichka.ui.components.ListCard
import ru.pereklichka.ui.components.ListRow
import ru.pereklichka.ui.components.SectionHeader
import ru.pereklichka.ui.components.StatusAvatar
import ru.pereklichka.ui.components.TrackStatus
import ru.pereklichka.ui.components.formatDay
import ru.pereklichka.ui.components.formatTime
import ru.pereklichka.ui.components.pastRouteDuration
import ru.pereklichka.ui.components.pastRouteOutcome
import ru.pereklichka.ui.components.plural
import ru.pereklichka.ui.components.trackLine
import ru.pereklichka.ui.theme.PereklichkaTheme
import java.time.Instant
import java.time.ZoneId

/**
 * Прошлый маршрут только для просмотра: чем он кончился и сколько шёл, моя роль, что с треком, участники и история
 * событий по времени этого телефона. Открывается со стартового экрана. Внизу «Удалить маршрут»: после
 * подтверждения маршрут со всеми записями удаляется с телефона ([onDelete]), и экран закрывается.
 */
@Composable
fun PastRouteScreen(details: PastRouteDetails, nowWallMs: Long, onDelete: () -> Unit, onBack: () -> Unit) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val route = details.item.route
    AppScreen(
        title = route.group.name,
        subtitle = routeSpan(route, nowWallMs),
        onBack = onBack,
        bottom = {
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Удалить маршрут", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            }
        },
    ) {
        Summary(details.item)
        Members(details.members)
        History(details.history)
    }

    if (confirmDelete) {
        DeleteRouteDialog(
            details.item,
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** «12 ноября, 10:42–13:15» от начала до конца маршрута; без начала или конца только день создания группы. */
private fun routeSpan(route: PastRoute, nowWallMs: Long): String {
    val started = route.startedAtWallMs
    val finished = route.finishedAtWallMs
    val day = formatDay(started ?: route.group.createdAtWallMs, nowWallMs)
    return when {
        started == null -> day
        finished == null -> "$day, с ${formatTime(started)}"
        else -> "$day, ${formatTime(started)}–${formatTime(finished)}"
    }
}

/** Чем кончился маршрут и сколько шёл, моя роль и строка о треке, если точки есть. */
@Composable
private fun Summary(item: PastRouteItem) {
    val route = item.route
    val scheme = MaterialTheme.colorScheme
    val role = if (route.group.me == route.group.leaderId) "вы руководитель" else "вы участник"
    val track = trackLine(item.track)
    ListCard(
        buildList<@Composable () -> Unit> {
            add {
                ListRow(
                    pastRouteOutcome(route),
                    subtitle = listOfNotNull(pastRouteDuration(route)?.let { "в пути $it" }, role)
                        .joinToString(" · ")
                        .replaceFirstChar(Char::uppercase),
                    leading = { IconCircle(R.drawable.sym_flag, scheme.surfaceContainerHigh, scheme.onSurfaceVariant) },
                )
            }
            if (track != null) {
                add {
                    ListRow(
                        track.replaceFirstChar(Char::uppercase),
                        leading = { IconCircle(R.drawable.sym_cloud_upload, scheme.surfaceContainerHigh, scheme.onSurfaceVariant) },
                    )
                }
            }
        },
    )
}

/** Участники маршрута, кроме вышедших: имя и роль. Вышедших видно в истории. */
@Composable
private fun Members(members: List<MemberView>) {
    if (members.isEmpty()) return
    SectionHeader("Участники", trailing = "${members.size}")
    ListCard(
        members.map { member ->
            {
                ListRow(
                    if (member.isMe) "${member.name} · вы" else member.name,
                    subtitle = if (member.isLeader) "Руководитель" else "Участник",
                    leading = { StatusAvatar(member, size = 40.dp, showStatus = false) },
                )
            }
        },
    )
}

/**
 * История событий: время и что случилось. Если маршрут перешёл через полночь, у строк следующих дней перед временем
 * стоит день.
 */
@Composable
private fun History(history: List<HistoryLine>) {
    if (history.isEmpty()) return
    val zone = ZoneId.systemDefault()
    val firstDay = Instant.ofEpochMilli(history.first().atWallMs).atZone(zone).toLocalDate()
    SectionHeader("События")
    ListCard(
        history.map { line ->
            {
                val day = Instant.ofEpochMilli(line.atWallMs).atZone(zone).toLocalDate()
                val time = formatTime(line.atWallMs).let { if (day == firstDay) it else "${formatDay(line.atWallMs, line.atWallMs)}, $it" }
                ListRow(line.title, subtitle = listOfNotNull(time, line.detail).joinToString(" · "))
            }
        },
    )
}

/**
 * Подтверждение удаления маршрута. Если часть трека ещё не ушла на сервер, диалог говорит, сколько точек
 * пропадёт; ушедший трек остаётся на сервере.
 */
@Composable
private fun DeleteRouteDialog(item: PastRouteItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val unsent = item.track.unsent
    val track = when {
        unsent > 0 -> " $unsent ${plural(unsent, "точка", "точки", "точек")} трека ещё не " +
            "${plural(unsent, "ушла", "ушли", "ушли")} на сервер и ${plural(unsent, "пропадёт", "пропадут", "пропадут")}."
        item.track.points > 0 && item.track.hasServer -> " Трек, который уже ушёл на сервер, останется там."
        else -> ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить маршрут?") },
        text = { Text("Маршрут «${item.route.group.name}» с участниками и историей удалится с этого телефона.$track") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Preview(name = "Прошлый маршрут")
@Preview(name = "Прошлый маршрут, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PastRoutePreview() {
    val view = FakeGroupViews.leader
    val start = view.group.createdAtWallMs
    val route = PastRoute(view.group, start + 600_000, start + 8_640_000, members = 3, points = 240, unsentPoints = 0, iLeft = false)
    PereklichkaTheme {
        PastRouteScreen(
            PastRouteDetails(
                PastRouteItem(route, TrackStatus(240, 0, hasServer = true)),
                members = view.members.sortedWith(compareBy({ !it.isMe }, { !it.isLeader }, { it.name })),
                history = listOf(
                    HistoryLine(start, "Группа создана", "вы"),
                    HistoryLine(start + 300_000, "Вступление в группу", "Ольга"),
                    HistoryLine(start + 600_000, "Маршрут начат"),
                    HistoryLine(start + 3_600_000, "Перекличка", "ответили 2"),
                    HistoryLine(start + 8_640_000, "Маршрут завершён"),
                ),
            ),
            nowWallMs = start,
            onDelete = {},
            onBack = {},
        )
    }
}
