package ru.pereklichka.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties
import ru.pereklichka.domain.rules.GroupView

/**
 * Подтверждение «Выйти из группы» у участника. Если телефон сейчас никого из группы не слышит напрямую, диалог
 * предупреждает, что о выходе могут не узнать и группа увидит «Не слышно».
 */
@Composable
internal fun QuitGroupDialog(view: GroupView, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val nobodyHeard = view.members.none { !it.isMe && it.heardDirectlyNow }
    val warning = if (nobodyHeard) {
        "\n\nСейчас телефон никого из группы не слышит. Если о выходе никто не узнает, группа увидит у вас «Не слышно»."
    } else {
        ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выйти из группы?") },
        text = {
            Text(
                "Группа увидит, что вы вышли, и перестанет вас ждать. Группа «${view.group.name}» останется в прошлых " +
                    "маршрутах, а неотправленный трек уйдёт на сервер, когда появится интернет.$warning",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Выйти", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Остаться") } },
    )
}

/**
 * Подтверждение «Отменить сбор» у руководителя. Без вступивших группа удалится с телефона, а если кто-то уже вступил,
 * сбор закончится у всех, и группа останется на телефоне завершённой.
 */
@Composable
internal fun CancelGatheringDialog(view: GroupView, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val joined = view.members.count { !it.isMe }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Отменить сбор?") },
        text = {
            Text(
                if (joined == 0) {
                    "Никто ещё не вступил, поэтому группа «${view.group.name}» удалится с этого телефона."
                } else {
                    "Сбор закончится у всех, кто уже вступил, и они увидят «Сбор отменён». Группа «${view.group.name}» " +
                        "останется на телефоне завершённой."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Отменить сбор", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Не отменять") } },
    )
}

/**
 * Ожидание после «Выйти из группы»: служба ждёт, пока кто-то из группы подтвердит выход, до 30 секунд.
 * «Выйти сразу» переводит группу в прошлые маршруты, не дожидаясь подтверждения.
 */
@Composable
internal fun QuittingDialog(onLeaveNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Сообщаем группе о выходе") },
        text = { Text("Ждём, пока кто-то из группы получит сообщение. Это займёт не больше 30 секунд.") },
        confirmButton = { TextButton(onClick = onLeaveNow) { Text("Выйти сразу") } },
    )
}
