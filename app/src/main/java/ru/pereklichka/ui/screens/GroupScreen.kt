package ru.pereklichka.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.ui.FakeGroupViews
import ru.pereklichka.ui.components.AlarmBanner
import ru.pereklichka.ui.components.BigButton
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.ButtonKind
import ru.pereklichka.ui.components.MemberRow
import ru.pereklichka.ui.components.MoreAlarmsBanner
import ru.pereklichka.ui.components.PhoneWarning
import ru.pereklichka.ui.components.PromptBanner
import ru.pereklichka.ui.components.RollcallBanner
import ru.pereklichka.ui.components.WarningBanner
import ru.pereklichka.ui.components.rememberPhoneWarnings
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.spacing

private const val MAX_ALARM_BANNERS = 3

/**
 * Список группы, главный экран, пока группа есть. По каждому участнику: имя, статус текстом, когда слышали
 * последний раз (`lastContact`, «примерно», если `approximate`), расстояние и направление, заряд.
 *
 * Над списком баннеры тревог `view.alarms`, предупреждения [warnings] о телефоне и подсказки `view.prompts`.
 * Предупреждение о том, что служба не запущена ([serviceStarted] равен false) или не хватает разрешений,
 * открывает экран разрешений, остальные открывают системные настройки.
 *
 * Нижняя панель зависит от состояния маршрута. В «Сборе» руководитель видит «QR и «Выходим»». Пока маршрут идёт,
 * у всех «Отойду» и SOS, а у руководителя ещё «Перекличка». После завершения у всех «Выключить», пока служба
 * может работать, и «Новая группа». В меню диагностика, разрешения, у руководителя «QR группы» в любом состоянии
 * и «Завершить маршрут», пока маршрут идёт.
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
    onDiagnostics: () -> Unit,
    onPermissions: () -> Unit,
    onLobby: () -> Unit,
    onStopService: () -> Unit,
    onNewGroup: () -> Unit,
    serviceStarted: Boolean? = null,
    warnings: List<PhoneWarning> = rememberPhoneWarnings(view, serviceStarted),
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { GroupTopBar(view, onFinishRoute, onDiagnostics, onPermissions, onLobby) },
        bottomBar = { GroupBottomBar(view, serviceStarted, onRollcall, onAway, onSos, onLobby, onStopService, onNewGroup) },
    ) { innerPadding ->
        GroupList(view, warnings, innerPadding, onMember, onRollcall, onAlarms, onPermissions)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupTopBar(
    view: GroupView,
    onFinishRoute: () -> Unit,
    onDiagnostics: () -> Unit,
    onPermissions: () -> Unit,
    onLobby: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text(view.group.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text(
                    text = routeText(view.route),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            Box {
                // Иконок в макете нет, поэтому кнопка меню это обычный символ с описанием для TalkBack.
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.semantics { contentDescription = "Меню" },
                ) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // Участник вступает по QR с телефона руководителя в любом состоянии маршрута, не только в «Сборе».
                    if (view.iAmLeader) {
                        DropdownMenuItem(text = { Text("QR группы") }, onClick = { menuOpen = false; onLobby() })
                    }
                    DropdownMenuItem(text = { Text("Разрешения") }, onClick = { menuOpen = false; onPermissions() })
                    DropdownMenuItem(text = { Text("Диагностика") }, onClick = { menuOpen = false; onDiagnostics() })
                    // Завершить маршрут может только руководитель и только пока маршрут идёт.
                    if (view.iAmLeader && view.route == RouteState.ACTIVE) {
                        DropdownMenuItem(
                            text = { Text("Завершить маршрут", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onFinishRoute() },
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

private fun routeText(route: RouteState) = when (route) {
    RouteState.LOBBY -> "Сбор"
    RouteState.ACTIVE -> "Маршрут идёт"
    RouteState.FINISHED -> "Завершён"
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
    val spacing = MaterialTheme.spacing
    val alone = view.members.none { !it.isMe }
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.l,
            end = spacing.l,
            top = innerPadding.calculateTopPadding() + spacing.s,
            bottom = innerPadding.calculateBottomPadding() + spacing.l,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        items(view.alarms.take(MAX_ALARM_BANNERS), key = { "alarm-${it.id.value}" }) { alarm ->
            AlarmBanner(alarm, onClick = onAlarms)
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
                RollcallBanner(rollcall, view.iAmLeader, iAnswered = view.me in rollcall.answered, onClick = onRollcall)
            }
        }
        items(view.members, key = { it.id.value }) { member ->
            MemberRow(member, onClick = { onMember(member.id) })
        }
        if (alone && view.route != RouteState.FINISHED) {
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
                    modifier = Modifier.fillMaxWidth().padding(vertical = spacing.l),
                )
            }
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
    val spacing = MaterialTheme.spacing
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = spacing.l, vertical = spacing.m),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            when (view.route) {
                RouteState.FINISHED -> FinishedActions(view, serviceStarted, onStopService, onNewGroup)
                RouteState.LOBBY -> {
                    Hint("Маршрут ещё не начался")
                    // После перезапуска приложение открывается на этом экране, и без кнопки руководитель
                    // не вернулся бы к QR и «Выходим».
                    if (view.iAmLeader) BigButtonFull("QR и «Выходим»", onLobby)
                }
                RouteState.ACTIVE -> {
                    if (view.iAmLeader) BigButtonFull("Перекличка", onRollcall)
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                        BigButton("Отойду", onAway, Modifier.weight(1f), ButtonKind.SECONDARY)
                        BigButton("SOS", onSos, Modifier.weight(1f), ButtonKind.DANGER_TONAL)
                    }
                }
            }
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
        modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.s),
    )
}

@Preview(name = "Группа: руководитель")
@Preview(name = "Группа: руководитель, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GroupLeaderPreview() {
    PereklichkaTheme {
        GroupScreen(
            FakeGroupViews.leader, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
            warnings = listOf(PhoneWarning.SERVICE_NOT_STARTED, PhoneWarning.BLUETOOTH_OFF),
        )
    }
}

@Preview(name = "Группа: участник")
@Composable
private fun GroupParticipantPreview() {
    PereklichkaTheme { GroupScreen(FakeGroupViews.participant, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
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
