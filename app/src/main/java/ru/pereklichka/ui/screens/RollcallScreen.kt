package ru.pereklichka.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.RollcallView
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.ui.FakeGroupViews
import ru.pereklichka.ui.components.AppScreen
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.ButtonKind
import ru.pereklichka.ui.components.CardLabel
import ru.pereklichka.ui.components.HintText
import ru.pereklichka.ui.components.InfoCard
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.spacing
import ru.pereklichka.ui.theme.statusColors

private val HereButtonSize = 220.dp
private val HereButtonRing = 10.dp

/**
 * Перекличка: кто запустил и сколько осталось, большая круглая «Я здесь»,
 * пока его нет в `answered`, сколько уже ответило, а внизу «Отлучусь ненадолго» и SOS.
 *
 * У руководителя три списка из `view.rollcall`: ответили, рядом но молчат, не слышно, и оставшееся время окна
 * (`RollcallView.remainingMs`). Если переклички нет, руководитель может её начать.
 *
 * Задача: docs/team/valentin-ui.md
 */
@Composable
fun RollcallScreen(
    view: GroupView,
    onStartRollcall: () -> Unit,
    onAnswer: (EventId) -> Unit,
    onAway: () -> Unit,
    onSos: () -> Unit,
    onBack: () -> Unit,
) {
    val rollcall = view.rollcall
    val leaderName = view.members.firstOrNull { it.isLeader }?.name
    val subtitle = rollcall?.let {
        listOfNotNull(leaderName?.let { name -> "Запустил(а) $name" }, "осталось ${clock(it.remainingMs)}").joinToString(" · ")
    }
    AppScreen(
        title = "Перекличка",
        subtitle = subtitle,
        onBack = onBack,
        bottom = {
            when {
                view.iAmLeader && rollcall == null && view.route == RouteState.ACTIVE ->
                    BigButtonFull("Запустить перекличку", onStartRollcall)
                !view.iAmLeader && view.route == RouteState.ACTIVE -> {
                    BigButtonFull("Отлучусь ненадолго", onAway, ButtonKind.SECONDARY)
                    BigButtonFull("SOS", onSos, ButtonKind.DANGER_TONAL)
                }
            }
        },
    ) {
        when {
            rollcall == null -> NoRollcall(view)
            view.iAmLeader -> LeaderLists(view, rollcall)
            else -> ParticipantAnswer(view, rollcall, onAnswer)
        }
    }
}

private fun clock(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun NoRollcall(view: GroupView) {
    InfoCard {
        Text("Переклички сейчас нет", style = MaterialTheme.typography.titleMedium)
        HintText(
            if (view.iAmLeader) {
                "Все участники получат уведомление и отметятся одной кнопкой."
            } else {
                "Когда руководитель её начнёт, придёт уведомление."
            },
        )
    }
}

@Composable
private fun ParticipantAnswer(view: GroupView, rollcall: RollcallView, onAnswer: (EventId) -> Unit) {
    val answered = view.me in rollcall.answered
    val total = rollcall.answered.size + rollcall.silentNearby.size + rollcall.notHeard.size
    Column(
        Modifier.fillMaxWidth().padding(top = MaterialTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Text(
            if (answered) "Вы отметились. Группа знает, что вы рядом" else "Отметьтесь, чтобы группа знала, что вы рядом",
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HereButton(answered, onClick = { onAnswer(rollcall.id) })
        Text("Ответили ${rollcall.answered.size} из $total", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HereButton(answered: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ok = MaterialTheme.statusColors.ok
    Surface(
        onClick = onClick,
        enabled = !answered,
        modifier = Modifier
            .size(HereButtonSize)
            .semantics {
                role = Role.Button
                contentDescription = if (answered) "Вы уже отметились" else "Я здесь"
            },
        shape = CircleShape,
        color = if (answered) ok.container else scheme.primary,
        contentColor = if (answered) ok.content else scheme.onPrimary,
        border = BorderStroke(HereButtonRing, scheme.primaryContainer),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(if (answered) "Отмечено" else "Я здесь", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LeaderLists(view: GroupView, rollcall: RollcallView) {
    val names = view.members.associate { it.id to it.name }
    val total = rollcall.answered.size + rollcall.silentNearby.size + rollcall.notHeard.size
    val colors = MaterialTheme.statusColors
    InfoCard {
        CardLabel("Осталось ${clock(rollcall.remainingMs)}")
        Text("Ответили ${rollcall.answered.size} из $total", style = MaterialTheme.typography.titleLarge)
    }
    NameList("Не слышно", rollcall.notHeard, names, colors.lost.content)
    NameList("Рядом, но молчат", rollcall.silentNearby, names, colors.away.content)
    NameList("Ответили", rollcall.answered, names, colors.ok.content)
}

@Composable
private fun NameList(title: String, ids: List<MemberId>, names: Map<MemberId, String>, accent: Color) {
    InfoCard {
        Text("$title · ${ids.size}", style = MaterialTheme.typography.titleMedium, color = accent)
        if (ids.isEmpty()) {
            HintText("никого")
        } else {
            ids.forEach { id -> Text(names[id] ?: "Неизвестный участник", style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Preview(name = "Перекличка: участник")
@Preview(name = "Перекличка: участник, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RollcallParticipantPreview() {
    PereklichkaTheme { RollcallScreen(FakeGroupViews.participant, {}, {}, {}, {}, {}) }
}

@Preview(name = "Перекличка: руководитель")
@Composable
private fun RollcallLeaderPreview() {
    PereklichkaTheme { RollcallScreen(FakeGroupViews.leader, {}, {}, {}, {}, {}) }
}

@Preview(name = "Перекличка: нет")
@Composable
private fun RollcallNonePreview() {
    PereklichkaTheme { RollcallScreen(FakeGroupViews.leader.copy(rollcall = null), {}, {}, {}, {}, {}) }
}
