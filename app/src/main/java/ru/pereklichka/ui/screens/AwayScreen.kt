package ru.pereklichka.ui.screens

import androidx.compose.runtime.Composable
import ru.pereklichka.domain.rules.GroupView

/**
 * Отлучка (ФТ-14): 5, 10 или 20 минут и отмена. Сколько осталось, видно по `awayRemainingMs` у меня в `view.members`.
 *
 * Задача: docs/team/valentin-ui.md
 */
@Composable
fun AwayScreen(view: GroupView, onAway: (minutes: Int) -> Unit, onCancelAway: () -> Unit) {
    // TODO(Валентин): выбор срока, оставшееся время отлучки и «Я вернулся».
    //  docs/team/valentin-ui.md
    val myAwayRemainingMs = view.members.firstOrNull { it.isMe }?.awayRemainingMs
    StubScreen("Отлучка") {
        listOf(5, 10, 20).forEach { minutes -> StubButton("Отойду на $minutes мин") { onAway(minutes) } }
        if (myAwayRemainingMs != null) StubButton("Я вернулся", onCancelAway)
    }
}
