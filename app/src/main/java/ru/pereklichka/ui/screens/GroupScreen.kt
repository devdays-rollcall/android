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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import ru.pereklichka.ui.components.PromptBanner
import ru.pereklichka.ui.components.RollcallBanner
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.spacing

private const val MAX_ALARM_BANNERS = 3

/**
 * Список группы. По каждому участнику: имя, статус текстом, когда слышали последний раз (`lastContact`,
 * «примерно», если `approximate`), расстояние и направление, заряд. Сверху баннеры `view.alarms` и `view.prompts`.
 * Это главный экран, пока группа есть.
 *
 * Экран только рисует [view] и вызывает колбэки, ничего не считает и не знает про ViewModel.
 *
 * Задача: docs/team/valentin-ui.md
 */
@OptIn(ExperimentalMaterial3Api::class)
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
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { GroupTopBar(view, onFinishRoute, onDiagnostics) },
        bottomBar = { GroupBottomBar(view, onRollcall, onAway, onSos) },
    ) { innerPadding ->
        GroupList(view, innerPadding, onMember, onRollcall, onAlarms)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupTopBar(view: GroupView, onFinishRoute: () -> Unit, onDiagnostics: () -> Unit) {
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
                    DropdownMenuItem(
                        text = { Text("Диагностика") },
                        onClick = { menuOpen = false; onDiagnostics() },
                    )
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
    innerPadding: PaddingValues,
    onMember: (MemberId) -> Unit,
    onRollcall: () -> Unit,
    onAlarms: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val alone = view.members.none { !it.isMe }
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
                    text = "Пока никого нет. Покажите QR-код участникам.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = spacing.l),
                )
            }
        }
    }
}

/** Нижняя панель: набор кнопок зависит от состояния маршрута и роли. */
@Composable
private fun GroupBottomBar(view: GroupView, onRollcall: () -> Unit, onAway: () -> Unit, onSos: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = spacing.l, vertical = spacing.m),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            when (view.route) {
                RouteState.FINISHED -> Hint("Маршрут завершён")
                RouteState.LOBBY -> Hint("Маршрут ещё не начался")
                RouteState.ACTIVE -> {
                    if (view.iAmLeader) BigButtonFull("Перекличка", onRollcall)
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                        BigButton("Отойду", onAway, Modifier.weight(1f), ButtonKind.SECONDARY)
                        BigButton("SOS", onSos, Modifier.weight(1f), ButtonKind.DANGER_OUTLINED)
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

