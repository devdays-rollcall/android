package ru.pereklichka.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.ResolveKind
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.ui.components.AlarmBanner
import ru.pereklichka.ui.components.BigButton
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.ButtonKind
import ru.pereklichka.ui.theme.spacing


/**
 * Тревога: активные тревоги из `view.alarms` и кто их объявил. Кнопки снятия зависят от того,
 * кто смотрит: «Я в порядке» у самого человека, «Вижу, в порядке» у остальных, «Ложная тревога» у руководителя
 * и автора тревоги. Там же подсказки о близости `view.prompts`.
 *
 * Задача: docs/team/valentin-ui.md
 */
@Composable
fun AlarmScreen(view: GroupView, onResolve: (target: MemberId, kind: ResolveKind) -> Unit) {
    // TODO(Валентин): тревогу нельзя пропустить; подсказка «Рядом телефон участника <имя>. Вы видите этого человека?».
    //  docs/team/valentin-ui.md
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AlarmTopBar(view) },
    ) { innerPadding ->
        AlarmList(view, innerPadding, onResolve)
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmTopBar(view: GroupView) {
    TopAppBar(
        title = {
            Column {
                Text(view.group.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun AlarmList(
    view: GroupView,
    innerPadding: PaddingValues,
    onResolve: (target: MemberId, kind: ResolveKind) -> Unit
) {
    val spacing = MaterialTheme.spacing

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
        items(view.alarms, key = { "alarm-${it.id.value}" }) { alarm ->
            AlarmBanner(alarm, {}, modifier = Modifier.padding(vertical = spacing.m))

            if (alarm.target == view.me) {
                BigButtonFull(
                    "Я в порядке",
                    { onResolve(alarm.target, ResolveKind.SELF_OK) },
                    ButtonKind.SECONDARY
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                    BigButton(
                        "Вижу, в порядке",
                        { onResolve(alarm.target, ResolveKind.SEEN_OK) },
                        Modifier.weight(1f),
                        ButtonKind.SECONDARY
                    )
                    BigButton(
                        "Ложная тревога",
                        { onResolve(alarm.target, ResolveKind.FALSE_ALARM) },
                        Modifier.weight(1f),
                        ButtonKind.DANGER_OUTLINED
                    )

                }
            }
        }
    }
}
