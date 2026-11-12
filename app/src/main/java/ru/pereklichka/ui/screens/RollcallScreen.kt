package ru.pereklichka.ui.screens

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.pereklichka.R
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.rules.RollcallView
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.ui.FakeGroupViews
import ru.pereklichka.ui.components.AppScreen
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.ButtonKind
import ru.pereklichka.ui.components.HintText
import ru.pereklichka.ui.components.InfoCard
import ru.pereklichka.ui.components.ListCard
import ru.pereklichka.ui.components.ListRow
import ru.pereklichka.ui.components.SectionHeader
import ru.pereklichka.ui.components.StatusKind
import ru.pereklichka.ui.components.SymIcon
import ru.pereklichka.ui.components.avatarColor
import ru.pereklichka.ui.components.formatClock
import ru.pereklichka.ui.components.receivedBy
import ru.pereklichka.ui.components.rollcallPendingLine
import ru.pereklichka.ui.components.statusColor
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.statusColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HereButtonSize = 220.dp
private val HereButtonRing = 10.dp
private val RingSize = 190.dp
private val RingWidth = 15.dp
private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Перекличка. В шапке кто и во сколько её запустил: время это сейчас минус `RollcallView.age`.
 *
 * Руководитель видит кольцо «1 из 4 ответили» с оставшимся временем окна (`RollcallView.remainingMs`), ниже
 * «Ещё не ответили» (сначала неслышимые, потом молчащие рядом) и «Ответили». Для неответивших строка говорит, дошло ли
 * уведомление: это видно по сводкам соседей [reportedBySeq] (см. `receivedBy`), где уже есть событие переклички.
 * Если переклички нет, руководитель может её начать, но только когда в группе есть кто-то, кроме него: без участников
 * отвечать некому, и перекличка три минуты висела бы с «0 из 0».
 *
 * Участник видит большую круглую «Я здесь», пока его нет в `answered`, и внизу «Отойду ненадолго». SOS здесь нет,
 * он остаётся на экране группы.
 */
@Composable
fun RollcallScreen(
    view: GroupView,
    onStartRollcall: () -> Unit,
    onAnswer: (EventId) -> Unit,
    onAway: () -> Unit,
    onBack: () -> Unit,
    reportedBySeq: Map<MemberId, Map<MemberId, Long>> = emptyMap(),
) {
    val rollcall = view.rollcall
    AppScreen(
        title = "Перекличка",
        subtitle = rollcall?.let { startedLine(view, it) },
        onBack = onBack,
        bottom = {
            when {
                view.iAmLeader && rollcall == null && view.route == RouteState.ACTIVE ->
                    BigButtonFull(
                        "Запустить перекличку",
                        onStartRollcall,
                        icon = R.drawable.sym_campaign,
                        large = true,
                        enabled = view.members.any { !it.isMe },
                    )
                !view.iAmLeader && view.route == RouteState.ACTIVE ->
                    BigButtonFull("Отойду ненадолго", onAway, ButtonKind.SECONDARY, icon = R.drawable.sym_directions_walk)
            }
        },
    ) {
        when {
            rollcall == null -> NoRollcall(view)
            view.iAmLeader -> LeaderLists(view, rollcall, reportedBySeq)
            else -> ParticipantAnswer(view, rollcall, onAnswer)
        }
    }
}

/** «Вы запустили в 12:50» или «Запустил(а) Никита в 12:50»; после перезагрузки телефона «примерно в». */
private fun startedLine(view: GroupView, rollcall: RollcallView): String {
    val at = Instant.ofEpochMilli(System.currentTimeMillis() - rollcall.age.ms).atZone(ZoneId.systemDefault()).format(TimeFormat)
    val time = if (rollcall.age.approximate) "примерно в $at" else "в $at"
    if (view.iAmLeader) return "Вы запустили $time"
    val leader = view.members.firstOrNull { it.id == view.group.leaderId }?.name ?: return "Запущена $time"
    return "Запустил(а) $leader $time"
}

private fun remainingText(rollcall: RollcallView): String =
    if (rollcall.remainingMs > 0) "ещё ${formatClock(rollcall.remainingMs)}" else "время вышло"

@Composable
private fun NoRollcall(view: GroupView) {
    InfoCard {
        Text("Переклички сейчас нет", style = MaterialTheme.typography.titleMedium)
        HintText(
            when {
                view.iAmLeader && view.members.none { !it.isMe } ->
                    "В группе пока никого нет. Перекличку можно запустить, когда участники вступят."
                view.iAmLeader -> "Все участники получат уведомление и отметятся одной кнопкой."
                else -> "Когда руководитель её начнёт, придёт уведомление."
            },
        )
    }
}

@Composable
private fun ParticipantAnswer(view: GroupView, rollcall: RollcallView, onAnswer: (EventId) -> Unit) {
    val answered = view.me in rollcall.answered
    val total = rollcall.answered.size + rollcall.silentNearby.size + rollcall.notHeard.size
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp),
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
        Text(
            "Ответили ${rollcall.answered.size} из $total · ${remainingText(rollcall)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun LeaderLists(view: GroupView, rollcall: RollcallView, reportedBySeq: Map<MemberId, Map<MemberId, Long>>) {
    val members = view.members.associateBy { it.id }
    val received = receivedBy(reportedBySeq, view.group.leaderId, rollcall.seq).toSet()
    val lost = statusColor(StatusKind.LOST)
    ProgressRing(rollcall)
    val pending = rollcall.notHeard.map { it to true } + rollcall.silentNearby.map { it to false }
    if (pending.isNotEmpty()) {
        SectionHeader("Ещё не ответили")
        RollcallList(
            pending.map { (id, notHeard) ->
                RollcallRowData(
                    id = id,
                    member = members[id],
                    subtitle = rollcallPendingLine(members[id], notHeard, id in received),
                    subtitleColor = if (notHeard) lost.content else null,
                    kind = if (notHeard) StatusKind.LOST else StatusKind.AWAY,
                    icon = if (notHeard) R.drawable.sym_wifi_off_fill else R.drawable.sym_hourglass_top_fill,
                )
            },
        )
    }
    if (rollcall.answered.isNotEmpty()) {
        SectionHeader("Ответили")
        RollcallList(
            rollcall.answered.map { id ->
                RollcallRowData(id, members[id], "Ответ получен", null, StatusKind.OK, R.drawable.sym_check_fill)
            },
        )
    }
}

/** Кольцо прогресса 190 dp: доля ответивших зелёным, в центре «1 из 4», «ответили» и оставшееся время. */
@Composable
private fun ProgressRing(rollcall: RollcallView) {
    val answered = rollcall.answered.size
    val total = answered + rollcall.silentNearby.size + rollcall.notHeard.size
    val ok = MaterialTheme.statusColors.ok.content
    val remaining = remainingText(rollcall)
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Ответили $answered из $total, $remaining"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(RingSize)) {
            val stroke = RingWidth.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(ok.copy(alpha = 0.15f), 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
            if (total > 0 && answered > 0) {
                drawArc(ok, -90f, 360f * answered / total, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                buildAnnotatedString {
                    append("$answered")
                    withStyle(SpanStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium, color = secondary)) { append(" из $total") }
                },
                fontSize = 44.sp,
                lineHeight = 48.sp,
                fontWeight = FontWeight.Bold,
            )
            Text("ответили",fontSize = 14.sp, lineHeight = 20.sp, color = secondary)
            Text(remaining, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ok, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private data class RollcallRowData(
    val id: MemberId,
    val member: MemberView?,
    val subtitle: String,
    val subtitleColor: Color?,
    val kind: StatusKind,
    @DrawableRes val icon: Int,
)

@Composable
private fun RollcallList(rows: List<RollcallRowData>) {
    ListCard {
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val name = row.member?.name ?: "Неизвестный участник"
            ListRow(
                title = name,
                subtitle = row.subtitle,
                subtitleColor = row.subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics(mergeDescendants = true) {},
                leading = { MiniAvatar(row.id, name) },
                trailing = { StatusMark(row.kind, row.icon) },
            )
        }
    }
}

/** Маленький аватар 36 dp без кольца: статус в этой строке показывает значок справа. */
@Composable
private fun MiniAvatar(id: MemberId, name: String) {
    Box(Modifier.size(36.dp).background(avatarColor(id.value), CircleShape), contentAlignment = Alignment.Center) {
        Text(name.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** Значок состояния ответа на цветной плашке 36 × 28 dp. Смысл его дублирует подпись строки. */
@Composable
private fun StatusMark(kind: StatusKind, @DrawableRes icon: Int) {
    val color = statusColor(kind)
    Box(
        Modifier.width(36.dp).height(28.dp).background(color.container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        SymIcon(icon, tint = color.content, size = 16.dp)
    }
}

@Preview(name = "Перекличка: участник")
@Preview(name = "Перекличка: участник, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RollcallParticipantPreview() {
    PereklichkaTheme { RollcallScreen(FakeGroupViews.participant, {}, {}, {}, {}) }
}

/** Даниил подтвердил перекличку в своей сводке, остальные ещё нет. */
@Preview(name = "Перекличка: руководитель")
@Preview(name = "Перекличка: руководитель, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RollcallLeaderPreview() {
    val view = FakeGroupViews.leader
    val reported = view.members.firstOrNull { it.name == "Даниил" }
        ?.let { mapOf(it.id to mapOf(view.group.leaderId to view.rollcall!!.seq)) }
        .orEmpty()
    PereklichkaTheme { RollcallScreen(view, {}, {}, {}, {}, reportedBySeq = reported) }
}

@Preview(name = "Перекличка: нет")
@Composable
private fun RollcallNonePreview() {
    PereklichkaTheme { RollcallScreen(FakeGroupViews.leader.copy(rollcall = null), {}, {}, {}, {}) }
}
