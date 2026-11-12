package ru.pereklichka.ui.screens

import android.content.res.Configuration
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.Presence
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.ui.FakeGroupViews
import ru.pereklichka.ui.components.AppScreen
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.ButtonKind
import ru.pereklichka.ui.components.CardLabel
import ru.pereklichka.ui.components.InfoCard
import ru.pereklichka.ui.components.StatusChip
import ru.pereklichka.ui.components.StatusInfo
import ru.pereklichka.ui.components.StatusKind
import ru.pereklichka.ui.components.WarningBanner
import ru.pereklichka.ui.components.rememberPermissionWarning
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.spacing
import ru.pereklichka.ui.theme.statusColors

/**
 * Завершение маршрута у руководителя: сводка группы (на связи, отошли, не слышно), идущая перекличка
 * и «Список группы». Открывается из меню экрана группы, пока маршрут идёт.
 *
 * Пока маршрут идёт, внизу «Завершить маршрут» с подтверждением, а если служба не запущена или не хватает
 * разрешений, сверху предупреждение со входом на экран разрешений. После завершения внизу то же, что на экране
 * группы ([FinishedActions]): «Выключить», пока служба может работать, и «Новая группа».
 */
@Composable
fun FinishRouteScreen(
    view: GroupView,
    serviceStarted: Boolean?,
    onFinish: () -> Unit,
    onStopService: () -> Unit,
    onNewGroup: () -> Unit,
    onPermissions: () -> Unit,
    onBack: () -> Unit,
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val finished = view.route == RouteState.FINISHED

    AppScreen(
        title = view.group.name,
        subtitle = if (finished) "Маршрут завершён" else "Маршрут идёт",
        onBack = onBack,
        bottom = {
            if (finished) {
                FinishedActions(view, serviceStarted, onStopService, onNewGroup)
            } else {
                BigButtonFull("Завершить маршрут", { confirm = true }, ButtonKind.DANGER_TONAL)
            }
        },
    ) {
        if (!finished) rememberPermissionWarning(serviceStarted)?.let { WarningBanner(it, onPermissions) }
        SummaryCard(view, serviceStarted)
        view.rollcall?.let { rollcall ->
            val total = rollcall.answered.size + rollcall.silentNearby.size + rollcall.notHeard.size
            InfoCard {
                CardLabel("Идёт перекличка")
                Text("Ответили ${rollcall.answered.size} из $total", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        BigButtonFull("Список группы", onBack, ButtonKind.SECONDARY)
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
 * Низ экрана после завершения маршрута, общий для списка группы и экрана завершения. Если службу не выключали
 * ([serviceStarted] не false), она ещё 15 минут после завершения передаёт его остальным, и «Выключить» останавливает
 * её сразу, а группа остаётся на телефоне. «Новая группа» после подтверждения стирает группу и ведёт на старт.
 */
@Composable
internal fun FinishedActions(view: GroupView, serviceStarted: Boolean?, onStopService: () -> Unit, onNewGroup: () -> Unit) {
    var confirmNewGroup by rememberSaveable { mutableStateOf(false) }
    val stopped = serviceStarted == false
    Text(
        if (stopped) {
            "Маршрут завершён, приложение выключено."
        } else {
            "Маршрут завершён. 15 минут после завершения приложение передаёт это остальным и выключается само."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.s),
    )
    if (!stopped) BigButtonFull("Выключить", onStopService, ButtonKind.SECONDARY)
    BigButtonFull("Новая группа", { confirmNewGroup = true })

    if (confirmNewGroup) {
        AlertDialog(
            onDismissRequest = { confirmNewGroup = false },
            title = { Text("Начать новую группу?") },
            text = {
                Text(
                    "Группа «${view.group.name}» с её списком и треком сотрётся с этого телефона. Точки трека, " +
                        "которые ещё не ушли на сервер, уже не отправятся.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmNewGroup = false; onNewGroup() }) {
                    Text("Стереть", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmNewGroup = false }) { Text("Отмена") } },
        )
    }
}

/**
 * Сводка: работает ли приложение в фоне (после завершения вместо этого «Маршрут завершён») и три счётчика
 * участников, кроме меня.
 */
@Composable
private fun SummaryCard(view: GroupView, serviceStarted: Boolean?) {
    val others = view.members.filterNot { it.isMe }
    val colors = MaterialTheme.statusColors
    val finished = view.route == RouteState.FINISHED
    InfoCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (finished) "Маршрут завершён" else "Приложение в фоне",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            StatusChip(
                when {
                    finished -> StatusInfo(StatusKind.LOST, "Завершён")
                    serviceStarted == false -> StatusInfo(StatusKind.ALARM, "Не работает")
                    else -> StatusInfo(StatusKind.OK, "Работает")
                },
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = MaterialTheme.spacing.m)) {
            Counter(others.count { it.presence == Presence.OK }, "на связи", colors.ok.content, Modifier.weight(1f))
            Counter(others.count { it.presence == Presence.AWAY }, "отошли", colors.away.content, Modifier.weight(1f))
            Counter(others.count { it.presence == Presence.LOST }, "не слышно", colors.lost.content, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Counter(count: Int, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $count" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$count", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color, textAlign = TextAlign.Center)
        CardLabel(label)
    }
}

@Preview(name = "Завершение: руководитель")
@Preview(name = "Завершение: руководитель, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FinishLeaderPreview() {
    PereklichkaTheme { FinishRouteScreen(FakeGroupViews.leader, serviceStarted = true, {}, {}, {}, {}, {}) }
}

@Preview(name = "Завершение: маршрут завершён")
@Composable
private fun FinishDonePreview() {
    PereklichkaTheme {
        FinishRouteScreen(
            FakeGroupViews.leader.copy(route = RouteState.FINISHED, rollcall = null),
            serviceStarted = true, {}, {}, {}, {}, {},
        )
    }
}
