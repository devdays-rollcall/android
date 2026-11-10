package ru.pereklichka.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.rules.GroupView

/**
 * Список группы (ФТ-12). По каждому участнику: имя, статус текстом, когда слышали последний раз (`lastContact`,
 * «примерно», если `approximate`), расстояние и направление, заряд. Сверху баннеры `view.alarms` и `view.prompts`.
 * Это главный экран, пока группа есть.
 *
 * Задача: docs/team/valentin-ui.md
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
) {
    // TODO(Валентин): список участников со статусом текстом рядом с цветом, баннеры тревог и подсказок о близости,
    //  кнопки «Перекличка» (руководитель), «Отойду», SOS, вход в диагностику из меню.
    //  docs/team/valentin-ui.md
    StubScreen("${view.group.name}: ${view.route}") {
        if (view.alarms.isNotEmpty()) StubButton("Тревоги: ${view.alarms.size}", onAlarms)
        for (member in view.members) {
            StubButton("${member.name}: ${member.presence}") { onMember(member.id) }
        }
        if (view.iAmLeader || view.rollcall != null) StubButton("Перекличка", onRollcall)
        StubButton("Отойду", onAway)
        StubButton("SOS", onSos)
        if (view.iAmLeader) StubButton("Завершить маршрут", onFinishRoute)
        StubButton("Диагностика", onDiagnostics)
        Text("Подсказок о близости: ${view.prompts.size}")
    }
}
