package ru.pereklichka.ui.screens

import androidx.compose.runtime.Composable

/**
 * Создание группы (ФТ-1): название группы и своё имя. После создания сразу открывается экран QR (`LobbyScreen`).
 *
 * Задача: docs/team/valentin-ui.md
 */
@Composable
fun CreateGroupScreen(onCreate: (groupName: String, myName: String) -> Unit) {
    // TODO(Валентин): поля «Название группы» и «Ваше имя», кнопка «Создать» неактивна, пока поля пустые.
    //  onCreate пишет группу и запускает службу; разрешения к этому моменту уже выданы.
    //  docs/team/valentin-ui.md
    StubScreen("Создание группы") {
        StubButton("Создать «Поход», я «Руководитель»") { onCreate("Поход", "Руководитель") }
    }
}
