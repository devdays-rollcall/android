package ru.pereklichka.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ru.pereklichka.domain.model.QrPayload
import ru.pereklichka.qr.QrParseResult
import ru.pereklichka.qr.rememberQrScanner

/**
 * Вступление (ФТ-10): сканер QR и ввод имени. Работает в авиарежиме, сканеру `rememberQrScanner` интернет не нужен.
 *
 * Задача: docs/team/valentin-ui.md
 */
@Composable
fun JoinScreen(onJoin: (qr: QrPayload, myName: String) -> Unit) {
    // TODO(Валентин): кнопка сканирования, понятное сообщение для чужого или битого кода (QrParseResult.Invalid.reason),
    //  поле «Ваше имя» и «Вступить».
    //  docs/team/valentin-ui.md
    var scanned by remember { mutableStateOf<QrParseResult?>(null) }
    val scan = rememberQrScanner { scanned = it }
    StubScreen("Вступление") {
        StubButton("Сканировать QR", scan)
        when (val result = scanned) {
            is QrParseResult.Valid -> {
                Text("Группа «${result.payload.groupName}»")
                StubButton("Вступить как «Участник»") { onJoin(result.payload, "Участник") }
            }
            is QrParseResult.Invalid -> Text(result.reason)
            null -> Unit
        }
    }
}
