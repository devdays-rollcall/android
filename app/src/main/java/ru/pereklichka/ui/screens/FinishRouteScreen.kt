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
import ru.pereklichka.ui.components.HintText
import ru.pereklichka.ui.components.InfoCard
import ru.pereklichka.ui.components.StatusChip
import ru.pereklichka.ui.components.StatusInfo
import ru.pereklichka.ui.components.StatusKind
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.spacing
import ru.pereklichka.ui.theme.statusColors

/**
 * Завершение маршрута: сводка группы (на связи, отошли, не слышно), идущая
 * перекличка, «Список группы» и «Завершить маршрут» у руководителя с подтверждением.
 *
 * После завершения служба работает ещё 15 минут, чтобы завершение дошло до всех, и у всех появляется «Выключить»:
 * она останавливает службу сразу.
 *
 */
@Composable
fun FinishRouteScreen(view: GroupView, onFinish: () -> Unit, onStopService: () -> Unit, onBack: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val finished = view.route == RouteState.FINISHED

    AppScreen(
        title = view.group.name,
        subtitle = when (view.route) {
            RouteState.LOBBY -> "Маршрут ещё не начался"
            RouteState.ACTIVE -> "Маршрут идёт"
            RouteState.FINISHED -> "Маршрут завершён"
        },
        onBack = onBack,
        bottom = {
            when {
                finished -> BigButtonFull("Выключить", onStopService)
                view.iAmLeader && view.route == RouteState.ACTIVE ->
                    BigButtonFull("Завершить маршрут", { confirm = true }, ButtonKind.DANGER_OUTLINED)
            }
        },
    ) {
        SummaryCard(view)
        view.rollcall?.let { rollcall ->
            val total = rollcall.answered.size + rollcall.silentNearby.size + rollcall.notHeard.size
            InfoCard {
                CardLabel("Идёт перекличка")
                Text("Ответили ${rollcall.answered.size} из $total", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        when {
            finished -> HintText(
                "Приложение ещё 15 минут передаёт завершение остальным и потом выключится само. " +
                    "«Выключить» остановит его сразу.",
            )
            !view.iAmLeader -> HintText("Завершить маршрут может только руководитель.")
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

/** Карточка «Приложение работает в фоне»: состояние маршрута и три счётчика участников, кроме меня. */
@Composable
private fun SummaryCard(view: GroupView) {
    val others = view.members.filterNot { it.isMe }
    val colors = MaterialTheme.statusColors
    InfoCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (view.route == RouteState.FINISHED) "Маршрут завершён" else "Приложение работает в фоне",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            StatusChip(
                when (view.route) {
                    RouteState.ACTIVE -> StatusInfo(StatusKind.OK, "Идёт")
                    RouteState.LOBBY -> StatusInfo(StatusKind.AWAY, "Сбор")
                    RouteState.FINISHED -> StatusInfo(StatusKind.LOST, "Завершён")
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
    PereklichkaTheme { FinishRouteScreen(FakeGroupViews.leader, {}, {}, {}) }
}

@Preview(name = "Завершение: маршрут завершён")
@Composable
private fun FinishDonePreview() {
    PereklichkaTheme {
        FinishRouteScreen(FakeGroupViews.participant.copy(route = RouteState.FINISHED, rollcall = null), {}, {}, {})
    }
}
