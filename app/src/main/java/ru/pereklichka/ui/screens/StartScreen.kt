package ru.pereklichka.ui.screens

import androidx.compose.runtime.Composable

/**
 * Старт (ФТ-1, ФТ-10): создать группу или вступить. Первый экран, пока группы нет.
 * Вход в диагностику и разрешения отсюда тоже нужен: без группы их больше неоткуда открыть.
 *
 * Задача: docs/team/valentin-ui.md
 */
@Composable
fun StartScreen(
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit,
    onPermissions: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    // TODO(Валентин): экран старта: две крупные кнопки «Создать группу» и «Вступить по QR».
    //  Если разрешения не выданы, сначала вести на экран разрешений.
    //  docs/team/valentin-ui.md
    StubScreen("Перекличка") {
        StubButton("Создать группу", onCreateGroup)
        StubButton("Вступить по QR", onJoinGroup)
        StubButton("Разрешения", onPermissions)
        StubButton("Диагностика", onDiagnostics)
    }
}
