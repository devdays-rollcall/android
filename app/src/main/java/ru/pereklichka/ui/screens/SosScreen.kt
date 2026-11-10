package ru.pereklichka.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import ru.pereklichka.domain.rules.GroupView

/**
 * SOS (ФТ-16). Отправка удержанием кнопки 2 секунды, после отправки звонок в 112 через `ACTION_DIAL` и текст
 * с координатами из моей позиции в `view.members`.
 */
@Composable
fun SosScreen(view: GroupView, onSos: () -> Unit) {
    // TODO(Валентин): удержание 2 с, звонок 112, текст с координатами для копирования и подпись о границах:
    //  звонок пройдёт только при сотовой сети, а SOS дойдёт лишь до телефонов группы, связанных цепочкой.
    //  docs/team/valentin-ui.md
    val myPosition = view.members.firstOrNull { it.isMe }?.position
    StubScreen("SOS") {
        StubButton("Отправить SOS", onSos)
        Text("Моя позиция: ${myPosition ?: "нет фиксации"}")
    }
}
