package ru.pereklichka.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.pereklichka.ui.components.AppScreen
import ru.pereklichka.ui.components.BigButton
import ru.pereklichka.ui.components.CardLabel
import ru.pereklichka.ui.components.HintText
import ru.pereklichka.ui.components.InfoCard
import ru.pereklichka.ui.components.LabeledField
import ru.pereklichka.ui.theme.PereklichkaTheme

/** Самое длинное имя участника в полях ввода. Должно совпадать с пределом имени в событии JOINED. */
internal const val MEMBER_NAME_MAX_LENGTH = 40

/**
 * Создание группы по макету («Новая группа»): поля «Название группы» и «Ваше имя», карточка о токене и «Создать группу».
 * Кнопка неактивна, пока хоть одно поле пустое и пока группа создаётся ([creating]). Экран QR (`LobbyScreen`)
 * открывает навигация, когда группа появилась в базе.
 *
 * [onCreate] пишет группу и запускает службу. Разрешения спрашивает стартовый экран, а если их так и не выдали,
 * экран QR предупредит, что служба не запущена.
 *
 * Задача: docs/team/valentin-ui.md
 */
@Composable
fun CreateGroupScreen(creating: Boolean, onCreate: (groupName: String, myName: String) -> Unit, onBack: () -> Unit) {
    var groupName by rememberSaveable { mutableStateOf("") }
    var myName by rememberSaveable { mutableStateOf("") }
    val ready = groupName.isNotBlank() && myName.isNotBlank() && !creating
    val create = { if (ready) onCreate(groupName.trim(), myName.trim()) }

    AppScreen(
        title = "Новая группа",
        onBack = onBack,
        bottom = {
            BigButton(
                text = if (creating) "Создаём…" else "Создать группу",
                onClick = create,
                modifier = Modifier.fillMaxWidth(),
                enabled = ready,
            )
        },
    ) {
        LabeledField("Название группы", groupName, { groupName = it })
        LabeledField(
            "Ваше имя",
            myName,
            { myName = it.take(MEMBER_NAME_MAX_LENGTH) },
            imeAction = ImeAction.Done,
            onDone = create,
        )
        InfoCard {
            CardLabel("Токен группы создаст телефон")
            HintText("Интернет не нужен. Токен откроет карту группы на сайте.")
        }
        OfflineChip()
    }
}

/** Плашка «Без интернета» из макета. */
@Composable
private fun OfflineChip() {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            "Без интернета",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Preview(name = "Новая группа")
@Preview(name = "Новая группа, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CreateGroupPreview() {
    PereklichkaTheme { CreateGroupScreen(creating = false, { _, _ -> }, {}) }
}
