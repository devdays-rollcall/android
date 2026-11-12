package ru.pereklichka.ui.screens

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.pereklichka.R
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.ResolveKind
import ru.pereklichka.domain.rules.AlarmView
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.time.Age
import ru.pereklichka.ui.FakeGroupViews
import ru.pereklichka.ui.components.AppScreen
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.ButtonKind
import ru.pereklichka.ui.components.DirectionArrow
import ru.pereklichka.ui.components.IconCircle
import ru.pereklichka.ui.components.ListCard
import ru.pereklichka.ui.components.ListRow
import ru.pereklichka.ui.components.SectionHeader
import ru.pereklichka.ui.components.SymIcon
import ru.pereklichka.ui.components.formatAge
import ru.pereklichka.ui.components.formatDistance
import ru.pereklichka.ui.components.placeOf
import ru.pereklichka.ui.components.plural
import ru.pereklichka.ui.components.spokenPlace
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.statusColors

/** Заряд, с которого значок батареи на шапке показывает почти пустую. */
private const val LOW_BATTERY = 20

/**
 * Тревога: по странице на каждую активную тревогу из `view.alarms`. Страницы листаются свайпом, а плашка
 * «1 из N тревог» в шапке переключает на следующую. Сверху на красном фоне кто пропал, кто и когда объявил тревогу,
 * стрелка и расстояние до его последней позиции, последняя связь и заряд. Ниже подсказки «Что делать» и кнопки
 * снятия: «Я в порядке» у самого человека, «Вижу его, всё в порядке» у остальных, «Ложная тревога» у руководителя
 * и автора тревоги. Если мой телефон слышит пропавшего напрямую (`view.prompts`), первая подсказка говорит, что он рядом.
 *
 * Уйти с экрана можно, не снимая тревогу: руководителю нужны перекличка, карточка пропавшего и SOS, а пропустить
 * тревогу не дадут красный баннер на экране группы и уведомление. Строка «Карточка участника» открывает экран
 * участника ([onMember]). Когда тревог не остаётся, экран показывает это и кнопку [onClose].
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
    if (view.alarms.isEmpty()) {
        NoAlarms(view, onClose)
        return
    }
    WhiteStatusBarIcons()
    val pager = rememberPagerState { view.alarms.size }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.statusColors.alarm
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            key = { view.alarms[it].id.value },
            verticalAlignment = Alignment.Top,
        ) { page ->
            AlarmPage(view, view.alarms[page], onResolve, onMember)
        }
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderIconButton(R.drawable.sym_arrow_back, "Назад", colors.content, onClose)
            Spacer(Modifier.weight(1f))
            val count = view.alarms.size
            if (count > 1) {
                val current = pager.currentPage.coerceAtMost(count - 1) + 1
                val next = { scope.launch { pager.animateScrollToPage(current % count) }; Unit }
                Surface(
                    onClick = next,
                    modifier = Modifier.semantics { onClick(label = "Следующая тревога") { next(); true } },
                    shape = CircleShape,
                    color = colors.content.copy(alpha = 0.18f),
                    contentColor = colors.content,
                ) {
                    Text(
                        "$current из $count ${plural(count, "тревоги", "тревог", "тревог")}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

/** Одна тревога: красная шапка и под ней лист с подсказками, прокручиваются вместе; кнопки снятия прижаты к низу. */
@Composable
private fun AlarmPage(
    view: GroupView,
    alarm: AlarmView,
    onResolve: (target: MemberId, kind: ResolveKind) -> Unit,
    onMember: (MemberId) -> Unit,
) {
    val aboutMe = alarm.target == view.me
    val member = view.members.firstOrNull { it.id == alarm.target }
    // Ложную тревогу снимает руководитель или тот, кто её объявил (у SOS это сам человек).
    val canCancel = view.iAmLeader || alarm.raisedBy == view.me
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            AlarmHeader(view, alarm, member)
            Column(
                Modifier
                    .offset(y = (-14).dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionHeader("Что делать")
                ListCard(whatToDo(view, alarm, member, aboutMe, onMember))
            }
        }
        Column(
            Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (aboutMe) {
                BigButtonFull("Я в порядке", { onResolve(alarm.target, ResolveKind.SELF_OK) }, icon = R.drawable.sym_check, large = true)
            } else {
                BigButtonFull(
                    "Вижу его, всё в порядке",
                    { onResolve(alarm.target, ResolveKind.SEEN_OK) },
                    icon = R.drawable.sym_visibility,
                    large = true,
                )
            }
            if (canCancel) BigButtonFull("Ложная тревога", { onResolve(alarm.target, ResolveKind.FALSE_ALARM) }, ButtonKind.SECONDARY)
        }
    }
}

/** Красная шапка: метка, имя, кто объявил, стрелка с расстоянием, последняя связь и заряд. */
@Composable
private fun AlarmHeader(view: GroupView, alarm: AlarmView, member: MemberView?) {
    val content = MaterialTheme.statusColors.alarm.content
    val aboutMe = alarm.target == view.me
    val label = when {
        alarm.isSos && aboutMe -> "ВАШ SOS"
        alarm.isSos -> "SOS"
        aboutMe -> "ТРЕВОГА О ВАС"
        else -> "ТРЕВОГА"
    }
    val age = formatAge(alarm.age)
    val raisedBy = when {
        alarm.isSos && aboutMe -> "Вы отправили SOS $age"
        alarm.isSos -> "Отправил(а) SOS $age"
        alarm.raisedBy == view.me -> "Объявили вы $age"
        else -> "Объявил(а) ${alarm.raisedByName} $age"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(alarmHeaderBrush())
            .statusBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = 64.dp, bottom = 36.dp),
    ) {
        Row(
            Modifier.semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SymIcon(R.drawable.sym_warning_fill, tint = content.copy(alpha = 0.9f))
            Text(label, color = content.copy(alpha = 0.9f), fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
        }
        Text(
            alarm.targetName,
            color = content,
            fontSize = 40.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(raisedBy, color = content.copy(alpha = 0.9f), fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
        if (!aboutMe && member != null) {
            Direction(member, content, Modifier.padding(top = 22.dp))
        }
        Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!aboutMe && member != null) {
                HeaderLine(R.drawable.sym_schedule, contactLine(view, member), content)
            }
            member?.batteryPercent?.let { percent ->
                val icon = if (percent <= LOW_BATTERY) R.drawable.sym_battery_1_bar_fill else R.drawable.sym_battery_5_bar_fill
                HeaderLine(icon, "Заряд $percent %", content)
            }
        }
    }
}

/**
 * Круг со стрелкой на последнюю позицию, расстояние и сторона света словами. Без координат у меня или у него
 * вместо стрелки значок и надпись, что расстояние неизвестно; ближе 5 м вместо стрелки «рядом».
 */
@Composable
private fun Direction(member: MemberView, content: Color, modifier: Modifier = Modifier) {
    val place = placeOf(member)
    val bearing = member.bearingDeg
    val (big, words) = when {
        place == null -> "Расстояние неизвестно" to "Нет координат у вас или у него"
        place.meters == null -> "Рядом" to "ближе 5 м"
        else -> formatDistance(member.distanceM).orEmpty() to place.sector?.let(::directionWords)
    }
    Row(
        modifier.semantics(mergeDescendants = true) {
            contentDescription = spokenPlace(member)?.let { "Последняя позиция: $it" } ?: "$big. $words"
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(Modifier.size(96.dp).background(content.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            when {
                place?.meters != null && bearing != null -> DirectionArrow(bearing, size = 56.dp, tint = content)
                place == null -> SymIcon(R.drawable.sym_location_off, tint = content, size = 44.dp)
                else -> SymIcon(R.drawable.sym_location_on, tint = content, size = 44.dp)
            }
        }
        Column {
            Text(
                big,
                color = content,
                fontSize = if (place == null) 22.sp else 34.sp,
                lineHeight = if (place == null) 28.sp else 40.sp,
                fontWeight = FontWeight.Bold,
            )
            if (words != null) Text(words, color = content.copy(alpha = 0.9f), fontSize = 15.sp)
        }
    }
}

/** «Последняя связь 4 мин назад, слышал(а) Даниил». */
private fun contactLine(view: GroupView, member: MemberView): String {
    if (member.heardDirectlyNow) return "Ваш телефон слышит его прямо сейчас"
    val contact = member.lastContact ?: return "Группа его ещё не слышала"
    val heardBy = when (val id = member.lastHeardBy) {
        null -> null
        view.me -> "напрямую с вами"
        else -> view.members.firstOrNull { it.id == id }?.name?.let { "слышал(а) $it" }
    }
    return listOfNotNull("Последняя связь ${formatAge(contact)}", heardBy).joinToString(", ")
}

@Composable
private fun HeaderLine(@DrawableRes icon: Int, text: String, content: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SymIcon(icon, tint = content.copy(alpha = 0.88f))
        Text(text, color = content.copy(alpha = 0.88f), fontSize = 15.sp)
    }
}

/**
 * Строки «Что делать». О себе одна подсказка: как снять тревогу. О другом: что его телефон рядом (если есть
 * подсказка о близости), позвать голосом, идти по стрелке (когда известно направление) и карточка участника.
 */
private fun whatToDo(
    view: GroupView,
    alarm: AlarmView,
    member: MemberView?,
    aboutMe: Boolean,
    onMember: (MemberId) -> Unit,
): List<@Composable () -> Unit> = buildList {
    if (aboutMe) {
        val what = if (alarm.isSos) "SOS" else "тревога"
        add { HintRow(R.drawable.sym_check, "Помощь не нужна?", "Нажмите «Я в порядке», и $what снимется у всех") }
        return@buildList
    }
    if (view.prompts.any { it.member == alarm.target }) {
        add {
            val ok = MaterialTheme.statusColors.ok
            ListRow(
                "Его телефон рядом с вами",
                subtitle = "Если видите его, нажмите «Вижу его»",
                leading = { IconCircle(R.drawable.sym_bluetooth_searching, ok.container, ok.content) },
            )
        }
    }
    add { HintRow(R.drawable.sym_record_voice_over, "Позвать голосом", "Если вы рядом") }
    if (member?.bearingDeg != null && placeOf(member)?.meters != null) {
        add { HintRow(R.drawable.sym_explore, "Идти по стрелке", "Направление обновляется с каждой новой точкой") }
    }
    add {
        ListRow(
            "Карточка участника",
            subtitle = "Позиция, точность и кто слышал последним",
            onClick = { onMember(alarm.target) },
            leading = { IconCircle(R.drawable.sym_person) },
            trailing = { SymIcon(R.drawable.sym_chevron_right, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
    }
}

@Composable
private fun HintRow(@DrawableRes icon: Int, title: String, subtitle: String) {
    ListRow(title, subtitle = subtitle, leading = { IconCircle(icon) })
}

@Composable
private fun NoAlarms(view: GroupView, onClose: () -> Unit) {
    AppScreen(
        title = "Тревог нет",
        subtitle = view.group.name,
        onBack = onClose,
        bottom = { BigButtonFull("К списку группы", onClose) },
    ) {
        val ok = MaterialTheme.statusColors.ok
        Column(
            Modifier.fillMaxWidth().padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconCircle(R.drawable.sym_check, ok.container, ok.content, size = 72)
            Text("Активных тревог нет", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}

@Preview(name = "Тревога: руководитель, две тревоги")
@Preview(name = "Тревога: руководитель, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AlarmLeaderPreview() {
    val view = FakeGroupViews.leader
    val vasilisa = view.members.first { it.name == "Василиса" }
    val second = view.alarms.first().copy(
        id = EventId("9e8d7c6b-5a49-4837-a625-1f0e0d0c0b0a"),
        target = vasilisa.id,
        targetName = vasilisa.name,
        age = Age(30_000, approximate = false),
    )
    PereklichkaTheme { AlarmScreen(view.copy(alarms = view.alarms + second), { _, _ -> }, {}, {}) }
}

@Preview(name = "Тревога: участник")
@Composable
private fun AlarmParticipantPreview() {
    PereklichkaTheme { AlarmScreen(FakeGroupViews.participant, { _, _ -> }, {}, {}) }
}

@Preview(name = "Тревога: SOS о себе")
@Composable
private fun AlarmAboutMePreview() {
    val view = FakeGroupViews.participant
    val sos = view.alarms.first().copy(target = view.me, targetName = "Даниил", raisedBy = view.me, raisedByName = "Даниил", isSos = true)
    PereklichkaTheme { AlarmScreen(view.copy(alarms = listOf(sos)), { _, _ -> }, {}, {}) }
}

@Preview(name = "Тревога: тревог нет")
@Composable
private fun NoAlarmsPreview() {
    PereklichkaTheme { AlarmScreen(FakeGroupViews.participant.copy(alarms = emptyList()), { _, _ -> }, {}, {}) }
}
