package ru.pereklichka.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.ResolveKind
import ru.pereklichka.domain.rules.AlarmView
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.NearbyPrompt
import ru.pereklichka.ui.FakeGroupViews
import ru.pereklichka.ui.components.BackButton
import ru.pereklichka.ui.components.BigButton
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.ButtonKind
import ru.pereklichka.ui.components.formatAge
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.spacing
import ru.pereklichka.ui.theme.statusColors

/**
 * Тревога: активные тревоги из `view.alarms` и кто их объявил. Кнопки снятия зависят от того,
 * кто смотрит: «Я в порядке» у самого человека, «Вижу, в порядке» у остальных, «Ложная тревога» у руководителя
 * и автора тревоги. Там же подсказки о близости `view.prompts`.
 *
 * Пока есть хоть одна активная тревога, экран красный. Уйти с него можно, не снимая тревогу: руководителю
 * нужны перекличка, карточка пропавшего и SOS, а пропустить тревогу не дадут красный баннер на экране группы
 * и уведомление. Нажатие на карточку тревоги открывает экран участника ([onMember]). Когда тревог не остаётся,
 * экран показывает это и кнопку [onClose].
 *
 * Задача: docs/team/valentin-ui.md
 */
@Composable
fun AlarmScreen(
    view: GroupView,
    onResolve: (target: MemberId, kind: ResolveKind) -> Unit,
    onMember: (MemberId) -> Unit,
    onClose: () -> Unit,
) {
    val active = view.alarms.isNotEmpty()

    val background = if (active) MaterialTheme.statusColors.alarm.container else MaterialTheme.colorScheme.background
    val content = if (active) MaterialTheme.statusColors.alarm.content else MaterialTheme.colorScheme.onBackground
    Scaffold(
        containerColor = background,
        contentColor = content,
        topBar = { AlarmTopBar(view, background, content, onClose) },
    ) { innerPadding ->
        if (active) AlarmList(view, innerPadding, onResolve, onMember) else NoAlarms(innerPadding, onClose)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmTopBar(view: GroupView, background: Color, content: Color, onClose: () -> Unit) {
    val title = when (view.alarms.size) {
        0 -> "Тревог нет"
        1 -> "Тревога"
        else -> "Тревоги: ${view.alarms.size}"
    }
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(view.group.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        },
        navigationIcon = { BackButton(onClose, tint = content) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = background,
            titleContentColor = content,
        ),
    )
}

@Composable
private fun AlarmList(
    view: GroupView,
    innerPadding: PaddingValues,
    onResolve: (target: MemberId, kind: ResolveKind) -> Unit,
    onMember: (MemberId) -> Unit,
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
        verticalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        // Подсказки о близости первыми: человек рядом, и ответить на них можно прямо сейчас.
        items(view.prompts, key = { "prompt-${it.member.value}" }) { prompt ->
            PromptCard(prompt, onSeenOk = { onResolve(prompt.member, ResolveKind.SEEN_OK) })
        }
        items(view.alarms, key = { "alarm-${it.id.value}" }) { alarm ->
            AlarmCard(view, alarm, onResolve, onOpen = { onMember(alarm.target) })
        }
    }
}

/**
 * Белая карточка на красном фоне: кто, кем объявлена, когда, и кнопки снятия по роли смотрящего. Нажатие на заголовок
 * открывает экран участника с последней позицией и тем, кто слышал его последним.
 */
@Composable
private fun AlarmCard(
    view: GroupView,
    alarm: AlarmView,
    onResolve: (target: MemberId, kind: ResolveKind) -> Unit,
    onOpen: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val aboutMe = alarm.target == view.me
    val title = when {
        alarm.isSos && aboutMe -> "Вы отправили SOS"
        alarm.isSos -> "SOS: ${alarm.targetName}"
        aboutMe -> "Тревога о вас"
        else -> "Тревога: ${alarm.targetName}"
    }
    val raisedBy = when {
        alarm.isSos -> null
        alarm.raisedBy == view.me -> "Объявили вы"
        else -> "Объявил(а) ${alarm.raisedByName}"
    }
    val subtitle = listOfNotNull(raisedBy, formatAge(alarm.age)).joinToString(" · ")
    // Ложную тревогу снимает руководитель или тот, кто её объявил (у SOS это сам человек).
    val canCancel = view.iAmLeader || alarm.raisedBy == view.me

    Card {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Открыть карточку участника", onClick = onOpen)
                .semantics(mergeDescendants = true) { contentDescription = "$title. $subtitle" },
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (aboutMe) {
            BigButtonFull("Я в порядке", { onResolve(alarm.target, ResolveKind.SELF_OK) }, ButtonKind.PRIMARY)
            if (canCancel) {
                BigButtonFull("Ложная тревога", { onResolve(alarm.target, ResolveKind.FALSE_ALARM) }, ButtonKind.DANGER_OUTLINED)
            }
        } else if (canCancel) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                BigButton("Вижу, в порядке", { onResolve(alarm.target, ResolveKind.SEEN_OK) }, Modifier.weight(1f))
                BigButton(
                    "Ложная тревога",
                    { onResolve(alarm.target, ResolveKind.FALSE_ALARM) },
                    Modifier.weight(1f),
                    ButtonKind.DANGER_OUTLINED,
                )
            }
        } else {
            BigButtonFull("Вижу, в порядке", { onResolve(alarm.target, ResolveKind.SEEN_OK) })
        }
    }
}

/** «Рядом телефон участника <имя>»: ответ снимает тревогу о нём как SEEN_OK. Радиоконтакт сам ничего не снимает. */
@Composable
private fun PromptCard(prompt: NearbyPrompt, onSeenOk: () -> Unit) {
    Card {
        Text(
            "Рядом телефон участника ${prompt.name}. Вы видите этого человека?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        BigButtonFull("Вижу, в порядке", onSeenOk)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.padding(MaterialTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.m),
        ) { content() }
    }
}

@Composable
private fun NoAlarms(innerPadding: PaddingValues, onClose: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(spacing.l),
        verticalArrangement = Arrangement.spacedBy(spacing.l),
    ) {
        Text(
            "Активных тревог нет",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.xl),
        )
        BigButtonFull("К списку группы", onClose)
    }
}

