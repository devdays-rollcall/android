package ru.pereklichka.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.QrPayload
import ru.pereklichka.domain.sync.ControlEventDraft
import ru.pereklichka.qr.QrFormat
import ru.pereklichka.qr.QrParseResult
import ru.pereklichka.qr.rememberQrScanner
import ru.pereklichka.ui.components.AppScreen
import ru.pereklichka.ui.components.BigButton
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.ButtonKind
import ru.pereklichka.ui.components.CardLabel
import ru.pereklichka.ui.components.HintText
import ru.pereklichka.ui.components.InfoCard
import ru.pereklichka.ui.components.LabeledField
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.statusColors

/**
 * Вступление: кнопка сканирования, карточка «Найдена группа» с токеном,
 * поле «Как вас зовут» и «Вступить». Для чужого или битого кода показывается `QrParseResult.Invalid.reason`
 * и предложение отсканировать ещё раз. Пока идёт вступление ([joining]), кнопка неактивна, а к списку группы
 * переводит навигация, когда группа появилась в базе.
 *
 * Работает в авиарежиме: сканер `rememberQrScanner` открывает камеру без интернета, а разбор кода делает `QrFormat`.
 * Отсканированный код переживает поворот экрана: годный хранится строкой QR и после поворота разбирается заново.
 *
 * Задача: docs/team/valentin-ui.md
 */
@Composable
fun JoinScreen(joining: Boolean, onJoin: (qr: QrPayload, myName: String) -> Unit, onBack: () -> Unit) {
    var scanned by rememberSaveable(stateSaver = ScanSaver) { mutableStateOf<QrParseResult?>(null) }
    val scan = rememberQrScanner { scanned = it }
    JoinContent(scanned, joining, scan, onJoin, onBack)
}

private const val VALID = "valid"
private const val INVALID = "invalid"

private val ScanSaver = Saver<QrParseResult?, List<String>>(
    save = { result ->
        when (result) {
            null -> null
            is QrParseResult.Valid -> listOf(VALID, QrFormat.encode(result.payload))
            is QrParseResult.Invalid -> listOf(INVALID, result.reason)
        }
    },
    restore = { (kind, text) -> if (kind == VALID) QrFormat.parse(text) else QrParseResult.Invalid(text) },
)

@Composable
private fun JoinContent(
    scanned: QrParseResult?,
    joining: Boolean,
    onScan: () -> Unit,
    onJoin: (qr: QrPayload, myName: String) -> Unit,
    onBack: () -> Unit,
) {
    var myName by rememberSaveable { mutableStateOf("") }
    val valid = scanned as? QrParseResult.Valid
    val ready = valid != null && myName.isNotBlank() && !joining
    val join = {
        if (ready) onJoin(valid.payload, myName.trim())
    }

    AppScreen(
        title = "Вступить в группу",
        onBack = onBack,
        bottom = {
            if (valid != null) {
                BigButton(
                    text = if (joining) "Вступаем…" else "Вступить",
                    onClick = join,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = ready,
                )
                BigButtonFull("Сканировать другой код", onScan, ButtonKind.SECONDARY)
            } else {
                BigButtonFull(if (scanned == null) "Сканировать QR" else "Сканировать ещё раз", onScan)
            }
        },
    ) {
        when (scanned) {
            null -> {
                InfoCard {
                    Text("Наведите камеру на QR-код руководителя", style = MaterialTheme.typography.titleMedium)
                    HintText("Код на телефоне руководителя, на экране «QR и сбор». Интернет не нужен.")
                }
            }
            is QrParseResult.Invalid -> {
                val color = MaterialTheme.statusColors.lost
                InfoCard(container = color.container) {
                    Text("Это не код группы", style = MaterialTheme.typography.titleMedium, color = color.content)
                    Text(scanned.reason, style = MaterialTheme.typography.bodyMedium, color = color.content)
                }
            }
            is QrParseResult.Valid -> {
                InfoCard {
                    CardLabel("Найдена группа")
                    Text(scanned.payload.groupName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    HintText("Токен ${scanned.payload.token}")
                }
                LabeledField(
                    "Как вас зовут",
                    myName,
                    { myName = it.take(ControlEventDraft.MAX_NAME_LENGTH) },
                    imeAction = ImeAction.Done,
                    onDone = join,
                )
                HintText("Имя увидит вся группа в списке и на карте")
            }
        }
    }
}

@Preview(name = "Вступление: до сканирования")
@Composable
private fun JoinEmptyPreview() {
    PereklichkaTheme { JoinContent(null, joining = false, {}, { _, _ -> }, {}) }
}

@Preview(name = "Вступление: группа найдена")
@Preview(name = "Вступление: группа найдена, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun JoinValidPreview() {
    val payload = QrPayload("ЛДГ-7К4-М92", "Поход на Ладогу", MemberId("8c1e5b0a-3f2d-4c6e-9a7b-1d2e3f4a5b6c"))
    PereklichkaTheme { JoinContent(QrParseResult.Valid(payload), joining = false, {}, { _, _ -> }, {}) }
}

@Preview(name = "Вступление: чужой код")
@Composable
private fun JoinInvalidPreview() {
    PereklichkaTheme { JoinContent(QrParseResult.Invalid("Код не от Переклички"), joining = false, {}, { _, _ -> }, {}) }
}
