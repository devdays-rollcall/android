package ru.pereklichka.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.pereklichka.R
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.QrPayload
import ru.pereklichka.domain.sync.ControlEventDraft
import ru.pereklichka.qr.QrFormat
import ru.pereklichka.qr.QrParseResult
import ru.pereklichka.ui.components.AppScreen
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.IconCircle
import ru.pereklichka.ui.components.IconTextField
import ru.pereklichka.ui.components.ListCard
import ru.pereklichka.ui.components.ListRow
import ru.pereklichka.ui.components.QrViewfinder
import ru.pereklichka.ui.components.SymIcon
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.statusColors

/**
 * Вступление по QR. До сканирования на экране видоискатель камеры ([QrViewfinder]), под ним бледные заготовки
 * карточки группы и поля имени, кнопка «Вступить» неактивна. Чужой или битый код показывается карточкой
 * с `QrParseResult.Invalid.reason`, а камера продолжает искать код. Годный код заменяет видоискатель карточкой
 * «Код прочитан» с названием группы и ключом, ниже поле «Ваше имя» и «Сканировать другой код».
 *
 * Имени руководителя в QR нет (`QrPayload` несёт ключ, название и id руководителя), поэтому третьей строкой
 * карточки показан ключ группы.
 *
 * Пока идёт вступление ([joining]), кнопка неактивна, а к списку группы переводит навигация, когда группа появилась
 * в базе. Работает в авиарежиме: камера и разбор кода (`QrFormat`) интернета не требуют. Отсканированный код
 * переживает поворот экрана: годный хранится строкой QR и после поворота разбирается заново. Камера отдаёт один
 * и тот же код много раз в секунду, поэтому разбирается только новый текст.
 */
@Composable
fun JoinScreen(joining: Boolean, onJoin: (qr: QrPayload, myName: String) -> Unit, onBack: () -> Unit) {
    var scanned by rememberSaveable(stateSaver = ScanSaver) { mutableStateOf<QrParseResult?>(null) }
    var lastText by remember { mutableStateOf<String?>(null) }
    JoinContent(
        scanned = scanned,
        joining = joining,
        onCode = { text ->
            if (text != lastText) {
                lastText = text
                scanned = QrFormat.parse(text)
            }
        },
        onRescan = {
            lastText = null
            scanned = null
        },
        onJoin = onJoin,
        onBack = onBack,
    )
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
    onCode: (String) -> Unit,
    onRescan: () -> Unit,
    onJoin: (qr: QrPayload, myName: String) -> Unit,
    onBack: () -> Unit,
) {
    val myName = rememberTextFieldState()
    val valid = scanned as? QrParseResult.Valid
    val ready = valid != null && myName.text.isNotBlank() && !joining
    val join = { if (ready) onJoin(valid.payload, myName.text.trim().toString()) }

    AppScreen(
        title = "Вступить в группу",
        onBack = onBack,
        bottom = {
            BigButtonFull(if (joining) "Вступаем…" else "Вступить", join, large = true, enabled = ready)
        },
    ) {
        if (valid != null) {
            ScannedGroup(valid.payload, myName, join, onRescan)
        } else {
            QrViewfinder(onCode)
            CenteredHint("Код на телефоне руководителя, на экране «Сбор». Интернет не нужен.", Modifier.padding(horizontal = 8.dp))
            if (scanned is QrParseResult.Invalid) InvalidCode(scanned.reason)
            ListCard(Modifier.alpha(0.55f)) {
                ListRow(
                    title = "Группа появится здесь",
                    subtitle = "после сканирования",
                    leading = {
                        IconCircle(
                            R.drawable.sym_group,
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            size = 44,
                        )
                    },
                )
            }
            IconTextField(
                "Ваше имя",
                myName,
                R.drawable.sym_person,
                placeholder = "Как вас увидит группа",
                enabled = false,
            )
        }
    }
}

/** Карточка прочитанного кода, поле имени и «Сканировать другой код». Поле имени получает фокус, пока пустое. */
@Composable
private fun ScannedGroup(
    payload: QrPayload,
    myName: TextFieldState,
    onJoin: () -> Unit,
    onRescan: () -> Unit,
) {
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(payload) { if (myName.text.isEmpty()) nameFocus.requestFocus() }
    val ok = MaterialTheme.statusColors.ok
    ListCard {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(Modifier.size(72.dp), shape = CircleShape, color = ok.container, contentColor = ok.content) {
                SymIcon(R.drawable.sym_check_fill, modifier = Modifier.padding(16.dp), size = 40.dp)
            }
            Spacer(Modifier.size(12.dp))
            CenteredHint("Код прочитан")
            Text(
                payload.groupName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
            CenteredHint("Ключ группы ${payload.token}", Modifier.padding(top = 4.dp))
        }
    }
    IconTextField(
        "Ваше имя",
        myName,
        R.drawable.sym_person,
        modifier = Modifier.focusRequester(nameFocus),
        maxLength = ControlEventDraft.MAX_NAME_LENGTH,
        placeholder = "Как вас увидит группа",
        imeAction = ImeAction.Done,
        onDone = onJoin,
    )
    TextButton(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            SymIcon(R.drawable.sym_qr_code_scanner, size = 22.dp)
            Spacer(Modifier.width(8.dp))
            Text("Сканировать другой код", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Серая подпись по центру. */
@Composable
private fun CenteredHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Чужой или битый код: камера продолжает искать, а здесь объяснение, что прочитано не то. */
@Composable
private fun InvalidCode(reason: String) {
    val color = MaterialTheme.statusColors.lost
    ListCard {
        ListRow(
            title = "Это не код группы",
            subtitle = reason,
            leading = { IconCircle(R.drawable.sym_error_fill, color.container, color.content) },
        )
    }
}

@Preview(name = "Вступление: до сканирования")
@Composable
private fun JoinEmptyPreview() {
    PereklichkaTheme { JoinContent(null, joining = false, {}, {}, { _, _ -> }, {}) }
}

@Preview(name = "Вступление: код прочитан")
@Preview(name = "Вступление: код прочитан, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun JoinValidPreview() {
    val payload = QrPayload("ЛДГ-7К4-М92", "Эльбрус, южный склон", MemberId("8c1e5b0a-3f2d-4c6e-9a7b-1d2e3f4a5b6c"))
    PereklichkaTheme { JoinContent(QrParseResult.Valid(payload), joining = false, {}, {}, { _, _ -> }, {}) }
}

@Preview(name = "Вступление: чужой код")
@Composable
private fun JoinInvalidPreview() {
    PereklichkaTheme { JoinContent(QrParseResult.Invalid("Это не QR-код Переклички"), joining = false, {}, {}, { _, _ -> }, {}) }
}
