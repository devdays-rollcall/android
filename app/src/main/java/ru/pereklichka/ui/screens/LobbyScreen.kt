package ru.pereklichka.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import ru.pereklichka.domain.model.QrPayload
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.qr.QrFormat
import ru.pereklichka.qr.QrImage

/**
 * QR и сбор (ФТ-3, ФТ-4, ФТ-5): QR для вступления, кто уже вступил, «Поделиться токеном», «Выходим» у руководителя.
 * Поля: `view.group` (для QR), `view.members`, `view.iAmLeader`, `view.route`.
 */
@Composable
fun LobbyScreen(view: GroupView, onStartRoute: () -> Unit, onOpenGroup: () -> Unit) {
    // TODO(Валентин): QR крупно и контрастно, список вступивших, «Поделиться токеном» через ACTION_SEND
    //  (токен и адрес сайта из AppSettings), «Выходим» только у руководителя. docs/team/valentin-ui.md
    val qrText = remember(view.group) {
        QrFormat.encode(QrPayload(view.group.token, view.group.name, view.group.leaderId))
    }
    val qrBitmap = remember(qrText) { QrImage.bitmap(qrText, QR_SIZE_PX).asImageBitmap() }
    StubScreen("QR и сбор: ${view.group.name}") {
        Image(qrBitmap, contentDescription = "QR-код для вступления", modifier = Modifier.fillMaxWidth())
        Text("Вступили: " + view.members.joinToString { it.name })
        if (view.iAmLeader) StubButton("Выходим", onStartRoute)
        StubButton("К списку группы", onOpenGroup)
    }
}

private const val QR_SIZE_PX = 720
