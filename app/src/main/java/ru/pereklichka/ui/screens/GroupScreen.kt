package ru.pereklichka.ui.screens

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.pereklichka.R
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.rules.Presence
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.ui.FakeGroupViews
import ru.pereklichka.ui.components.AlarmBanner
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.CardShape
import ru.pereklichka.ui.components.ListCard
import ru.pereklichka.ui.components.MemberRow
import ru.pereklichka.ui.components.MoreAlarmsBanner
import ru.pereklichka.ui.components.PhoneWarning
import ru.pereklichka.ui.components.Pill
import ru.pereklichka.ui.components.PromptBanner
import ru.pereklichka.ui.components.RollcallBanner
import ru.pereklichka.ui.components.SectionHeader
import ru.pereklichka.ui.components.StatusAvatar
import ru.pereklichka.ui.components.StatusChip
import ru.pereklichka.ui.components.StatusKind
import ru.pereklichka.ui.components.SymIcon
import ru.pereklichka.ui.components.WarningBanner
import ru.pereklichka.ui.components.rememberPhoneWarnings
import ru.pereklichka.ui.components.routeChipText
import ru.pereklichka.ui.components.selfSubtitle
import ru.pereklichka.ui.components.spokenSummary
import ru.pereklichka.ui.components.statusOf
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.statusColors

private const val MAX_ALARM_BANNERS = 3

/**
 * Список группы, главный экран, пока группа есть. В шапке название группы и плашка состояния маршрута со временем
 * в пути (`routeStartedAgo`), у руководителя ещё кнопка QR. Под шапкой баннеры тревог `view.alarms`, предупреждения
 * [warnings] о телефоне, подсказки `view.prompts` и карточка переклички. Дальше своя карточка: роль и сколько
 * остальных мой телефон слышит напрямую. Остальные участники разбиты на «Нужна помощь» (SOS, тревога,
 * «Не слышно») и «В группе».
 *
 * Предупреждение о том, что служба не запущена ([serviceStarted] равен false) или не хватает разрешений,
 * открывает экран разрешений, остальные открывают системные настройки.
 *
 * Нижняя панель зависит от состояния маршрута. В «Сборе» руководитель видит «QR и «Выходим»». Пока маршрут идёт,
 * у всех «Отойду» и круглая SOS, а у руководителя ещё «Перекличка». После завершения у всех «Выключить», пока служба
 * может работать, и «Новая группа». В меню разрешения, настройки и у руководителя «Завершить маршрут», пока маршрут
 * идёт.
 */
@Composable
fun GroupScreen(
    view: GroupView,
    onMember: (MemberId) -> Unit,
    onRollcall: () -> Unit,
    onAway: () -> Unit,
    onAlarms: () -> Unit,
    onSos: () -> Unit,
    onFinishRoute: () -> Unit,
    onSettings: () -> Unit,
    onPermissions: () -> Unit,
    onLobby: () -> Unit,
    onStopService: () -> Unit,
    onNewGroup: () -> Unit,
    serviceStarted: Boolean? = null,
    warnings: List<PhoneWarning> = rememberPhoneWarnings(view, serviceStarted),
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { GroupTopBar(view, onFinishRoute, onSettings, onPermissions, onLobby) },
        bottomBar = { GroupBottomBar(view, serviceStarted, onRollcall, onAway, onSos, onLobby, onStopService, onNewGroup) },
    ) { innerPadding ->
        GroupList(view, warnings, innerPadding, onMember, onRollcall, onAlarms, onPermissions)
    }
}

@Composable
private fun GroupTopBar(
    view: GroupView,
    onFinishRoute: () -> Unit,
    onSettings: () -> Unit,
    onPermissions: () -> Unit,
    onLobby: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                view.group.name,
                style = MaterialTheme.typography.titleLarge,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            RouteChip(view, Modifier.padding(top = 4.dp))
        }
        // Участник вступает по QR с телефона руководителя в любом состоянии маршрута, не только в «Сборе».
        if (view.iAmLeader) {
            IconButton(onClick = onLobby) { SymIcon(R.drawable.sym_qr_code_2, contentDescription = "QR группы") }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) { SymIcon(R.drawable.sym_more_vert, contentDescription = "Меню") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Разрешения") }, onClick = { menuOpen = false; onPermissions() })
                DropdownMenuItem(text = { Text("Настройки") }, onClick = { menuOpen = false; onSettings() })
                // Завершить маршрут может только руководитель и только пока маршрут идёт.
                if (view.iAmLeader && view.route == RouteState.ACTIVE) {
                    DropdownMenuItem(
                        text = { Text("Завершить маршрут", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onFinishRoute() },
                    )
                }
            }
        }
    }
}

/** Плашка состояния маршрута: зелёная на маршруте, светло-зелёная в «Сборе», серая после завершения. */
@Composable
private fun RouteChip(view: GroupView, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (container, content, icon) = when (view.route) {
        RouteState.ACTIVE -> MaterialTheme.statusColors.ok.let { Triple(it.container, it.content, R.drawable.sym_hiking_fill) }
        RouteState.LOBBY -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, R.drawable.sym_groups_fill)
        RouteState.FINISHED -> Triple(scheme.surfaceContainerHigh, scheme.onSurfaceVariant, R.drawable.sym_flag)
    }
    Row(modifier) {
        Pill(routeChipText(view.route, view.routeStartedAgo), container, content, icon = icon, height = 24.dp)
    }
}

@Composable
private fun GroupList(
    view: GroupView,
    warnings: List<PhoneWarning>,
    innerPadding: PaddingValues,
    onMember: (MemberId) -> Unit,
    onRollcall: () -> Unit,
    onAlarms: () -> Unit,
    onPermissions: () -> Unit,
) {
    val me = view.members.firstOrNull { it.isMe }
    val others = view.members.filterNot { it.isMe }
    val (needHelp, rest) = others.partition(::needsHelp)
    val names = view.members.associate { it.id to it.name }
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = innerPadding.calculateTopPadding() + 4.dp,
            bottom = innerPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(view.alarms.take(MAX_ALARM_BANNERS), key = { "alarm-${it.id.value}" }) { alarm ->
            AlarmBanner(alarm, onClick = onAlarms, me = view.me)
        }
        if (view.alarms.size > MAX_ALARM_BANNERS) {
            item(key = "alarms-more") { MoreAlarmsBanner(view.alarms.size - MAX_ALARM_BANNERS, onAlarms) }
        }
        items(warnings, key = { "warning-${it.name}" }) { warning ->
            WarningBanner(warning, onClick = {
                val intent = warning.settingsIntent(context)
                if (intent == null) onPermissions() else context.startActivity(intent)
            })
        }
        items(view.prompts, key = { "prompt-${it.member.value}" }) { prompt ->
            PromptBanner(prompt, onClick = { onMember(prompt.member) })
        }
        view.rollcall?.let { rollcall ->
            item(key = "rollcall") {
                RollcallBanner(rollcall, view.iAmLeader, iAnswered = view.me in rollcall.answered, names = names, onClick = onRollcall)
            }
        }
        if (me != null) {
            item(key = "me") { SelfCard(view, me, onClick = { onMember(me.id) }) }
        }
        if (needHelp.isNotEmpty()) {
            item(key = "help-header") { SectionHeader("Нужна помощь") }
            item(key = "help") { MemberList(needHelp, onMember) }
        }
        if (rest.isNotEmpty()) {
            item(key = "group-header") { SectionHeader("В группе") }
            item(key = "group") { MemberList(rest, onMember) }
        }
        if (others.isEmpty() && view.route != RouteState.FINISHED) {
            item(key = "empty") {
                Text(
                    text = if (view.iAmLeader) {
                        "Пока никого нет. Покажите QR-код участникам."
                    } else {
                        "Пока никого не слышно. Список заполнится, когда телефон найдёт группу."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            }
        }
    }
}

/** В «Нужна помощь» попадают SOS, тревога и «Не слышно». */
private fun needsHelp(member: MemberView): Boolean =
    member.hasSos || member.hasActiveAlarm || member.presence == Presence.LOST

@Composable
private fun MemberList(members: List<MemberView>, onMember: (MemberId) -> Unit) {
    ListCard {
        members.forEachIndexed { index, member ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            MemberRow(member, onClick = { onMember(member.id) })
        }
    }
}

/** Своя карточка над списком: аватар без кольца, «Никита · вы», роль и «слышу 3 из 4», справа плашка, если статус не «Норма». */
@Composable
private fun SelfCard(view: GroupView, me: MemberView, onClick: () -> Unit) {
    val subtitle = selfSubtitle(view)
    val description = "${spokenSummary(me, statusOf(me))}. $subtitle"
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description },
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusAvatar(me, showStatus = false)
            Column(Modifier.weight(1f)) {
                Text("${me.name} · вы", fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // «Норма» о себе ничего не сообщает и сжимает подпись, поэтому плашка видна только при другом статусе.
            if (statusOf(me).kind != StatusKind.OK) StatusChip(me)
        }
    }
}

@Composable
private fun GroupBottomBar(
    view: GroupView,
    serviceStarted: Boolean?,
    onRollcall: () -> Unit,
    onAway: () -> Unit,
    onSos: () -> Unit,
    onLobby: () -> Unit,
    onStopService: () -> Unit,
    onNewGroup: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (view.route) {
                    RouteState.FINISHED -> FinishedActions(view, serviceStarted, onStopService, onNewGroup)
                    RouteState.LOBBY -> {
                        Hint("Маршрут ещё не начался")
                        // После перезапуска приложение открывается на этом экране, и без кнопки руководитель
                        // не вернулся бы к QR и «Выходим».
                        if (view.iAmLeader) BigButtonFull("QR и «Выходим»", onLobby, icon = R.drawable.sym_qr_code_2)
                    }
                    RouteState.ACTIVE -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (view.iAmLeader) {
                            BarButton("Перекличка", R.drawable.sym_campaign, onRollcall, outlined = false, Modifier.weight(1f))
                        }
                        BarButton(
                            "Отойду",
                            R.drawable.sym_directions_walk,
                            onAway,
                            outlined = true,
                            Modifier.weight(if (view.iAmLeader) 0.7f else 1f),
                        )
                        SosButton(onSos)
                    }
                }
            }
        }
    }
}

/**
 * Кнопка нижней панели высотой 64 dp. Текст в одну строку уменьшается до 13 sp, если трём кнопкам на узком экране
 * тесно.
 */
@Composable
private fun BarButton(text: String, @DrawableRes icon: Int, onClick: () -> Unit, outlined: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 64.dp),
        shape = CircleShape,
        colors = if (outlined) {
            ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = scheme.primary)
        } else {
            ButtonDefaults.buttonColors()
        },
        border = if (outlined) BorderStroke(1.dp, scheme.outline) else null,
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        SymIcon(icon, size = 22.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            maxLines = 1,
            fontWeight = FontWeight.Medium,
            autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = 18.sp),
        )
    }
}

/** Круглая красная SOS 64 dp справа в нижней панели. Открывает экран SOS, где сигнал отправляется удержанием. */
@Composable
private fun SosButton(onClick: () -> Unit) {
    val color = MaterialTheme.statusColors.alarm
    Surface(
        onClick = onClick,
        modifier = Modifier.size(64.dp),
        shape = CircleShape,
        color = color.container,
        contentColor = color.content,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("SOS", fontSize = 17.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

@Preview(name = "Группа: руководитель")
@Preview(name = "Группа: руководитель, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GroupLeaderPreview() {
    PereklichkaTheme {
        GroupScreen(
            FakeGroupViews.leader, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
            warnings = listOf(PhoneWarning.BLUETOOTH_OFF),
        )
    }
}

@Preview(name = "Группа: участник")
@Composable
private fun GroupParticipantPreview() {
    PereklichkaTheme { GroupScreen(FakeGroupViews.participant, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
}

@Preview(name = "Группа: сбор")
@Composable
private fun GroupLobbyPreview() {
    PereklichkaTheme {
        GroupScreen(
            FakeGroupViews.leader.copy(route = RouteState.LOBBY, routeStartedAgo = null, alarms = emptyList(), rollcall = null),
            {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(name = "Группа: маршрут завершён")
@Composable
private fun GroupFinishedPreview() {
    PereklichkaTheme {
        GroupScreen(
            FakeGroupViews.participant.copy(route = RouteState.FINISHED, alarms = emptyList(), rollcall = null),
            {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }
}
