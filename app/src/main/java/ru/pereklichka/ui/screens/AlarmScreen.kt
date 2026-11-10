package ru.pereklichka.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.ResolveKind
import ru.pereklichka.domain.rules.GroupView

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
    StubScreen("Тревоги") {
        if (view.alarms.isEmpty()) Text("Активных тревог нет")
        for (alarm in view.alarms) {
            Text("${alarm.targetName}: объявил ${alarm.raisedByName}${if (alarm.isSos) ", SOS" else ""}")
            if (alarm.target == view.me) {
                StubButton("Я в порядке") { onResolve(alarm.target, ResolveKind.SELF_OK) }
            } else {
                StubButton("Вижу, в порядке") { onResolve(alarm.target, ResolveKind.SEEN_OK) }
            }
            if (view.iAmLeader || alarm.raisedBy == view.me) {
                StubButton("Ложная тревога") { onResolve(alarm.target, ResolveKind.FALSE_ALARM) }
            }
        }
    }
}
