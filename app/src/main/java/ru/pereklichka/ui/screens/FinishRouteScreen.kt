package ru.pereklichka.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.RouteState

/**
 * Завершение маршрута (ФТ-9): подтверждение у руководителя. После завершения служба работает ещё 15 минут,
 * «Выключить» останавливает её сразу.
 *
 * Задача: docs/team/valentin-ui.md
 */
@Composable
fun FinishRouteScreen(view: GroupView, onFinish: () -> Unit, onStopService: () -> Unit) {
    // TODO(Валентин): подтверждение завершения, «Выключить» после него.
    //  docs/team/valentin-ui.md
    StubScreen("Завершение маршрута") {
        if (view.route == RouteState.FINISHED) {
            Text("Маршрут завершён")
            StubButton("Выключить", onStopService)
        } else if (view.iAmLeader) {
            StubButton("Завершить маршрут", onFinish)
        }
    }
}
