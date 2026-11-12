package ru.pereklichka.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.pereklichka.R
import ru.pereklichka.domain.sync.ControlEventDraft
import ru.pereklichka.ui.components.AppScreen
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.CardShape
import ru.pereklichka.ui.components.IconTextField
import ru.pereklichka.ui.components.ListCard
import ru.pereklichka.ui.components.ListRow
import ru.pereklichka.ui.components.SectionHeader
import ru.pereklichka.ui.components.SymIcon
import ru.pereklichka.ui.theme.PereklichkaTheme

/**
 * Создание группы («Новая группа»): поля «Название похода» и «Ваше имя», карточка «Интернет не нужен» о ключе группы,
 * два шага «Что будет дальше» и «Создать группу». Кнопка неактивна, пока хоть одно поле пустое и пока группа
 * создаётся ([creating]). Экран сбора с QR (`LobbyScreen`) открывает навигация, когда группа появилась в базе.
 *
 * [onCreate] пишет группу и запускает службу. Разрешения спрашивает стартовый экран, а если их так и не выдали,
 * экран сбора предупредит, что служба не запущена.
 */
@Composable
fun CreateGroupScreen(creating: Boolean, onCreate: (groupName: String, myName: String) -> Unit, onBack: () -> Unit) {
    val groupName = rememberTextFieldState()
    val myName = rememberTextFieldState()
    val ready = groupName.text.isNotBlank() && myName.text.isNotBlank() && !creating
    val create = { if (ready) onCreate(groupName.text.trim().toString(), myName.text.trim().toString()) }

    AppScreen(
        title = "Новая группа",
        onBack = onBack,
        bottom = {
            BigButtonFull(if (creating) "Создаём…" else "Создать группу", create, large = true, enabled = ready)
        },
    ) {
        IconTextField("Название похода", groupName, R.drawable.sym_landscape)
        IconTextField(
            "Ваше имя",
            myName,
            R.drawable.sym_person,
            maxLength = ControlEventDraft.MAX_NAME_LENGTH,
            placeholder = "Как вас увидит группа",
            imeAction = ImeAction.Done,
            onDone = create,
        )
        OfflineCard()
        Column {
            SectionHeader("Что будет дальше", Modifier.padding(bottom = 10.dp))
            ListCard {
                StepRow(1, "Покажете QR-код", "Участники сканируют его по очереди")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StepRow(2, "Нажмёте «Выходим»", "Начнётся трек и проверка связи")
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** Тональная карточка «Интернет не нужен»: ключ группы создаёт сам телефон. */
@Composable
private fun OfflineCard() {
    val scheme = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = CardShape, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SymIcon(R.drawable.sym_wifi_off_fill, tint = scheme.primary, size = 28.dp)
            Column {
                Text("Интернет не нужен", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Телефон создаст ключ группы сам. Тот же ключ потом откроет карту похода на сайте.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.85f),
                )
            }
        }
    }
}

/** Шаг «Что будет дальше» с номером в круге. */
@Composable
private fun StepRow(number: Int, title: String, text: String) {
    ListRow(
        title = title,
        subtitle = text,
        leading = {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Text(
                    number.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

@Preview(name = "Новая группа")
@Preview(name = "Новая группа, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CreateGroupPreview() {
    PereklichkaTheme { CreateGroupScreen(creating = false, { _, _ -> }, {}) }
}
