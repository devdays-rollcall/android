package ru.pereklichka.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.rules.GroupView

/**
 * Перекличка (ФТ-7, ФТ-13). У руководителя три списка из `view.rollcall`: ответили, рядом но молчат, не слышно.
 * У участника большая кнопка «Я здесь», пока его нет в `answered`.
 *
 * Задача: docs/team/valentin-ui.md
 */
@Composable
fun RollcallScreen(view: GroupView, onStartRollcall: () -> Unit, onAnswer: (EventId) -> Unit) {
    // TODO(Валентин): три списка с именами и оставшимся временем окна у руководителя, «Я здесь» у участника.
    //  docs/team/valentin-ui.md
    val rollcall = view.rollcall
    StubScreen("Перекличка") {
        if (rollcall == null) {
            Text("Переклички сейчас нет")
            if (view.iAmLeader) StubButton("Начать перекличку", onStartRollcall)
            return@StubScreen
        }
        val names = view.members.associate { it.id to it.name }
        Text("Ответили: " + rollcall.answered.joinToString { names[it].orEmpty() })
        Text("Рядом, но молчат: " + rollcall.silentNearby.joinToString { names[it].orEmpty() })
        Text("Не слышно: " + rollcall.notHeard.joinToString { names[it].orEmpty() })
        if (!view.iAmLeader && view.me !in rollcall.answered) StubButton("Я здесь") { onAnswer(rollcall.id) }
    }
}
