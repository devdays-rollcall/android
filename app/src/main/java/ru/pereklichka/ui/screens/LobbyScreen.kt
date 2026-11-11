package ru.pereklichka.ui.screens

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import ru.pereklichka.domain.model.QrPayload
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.qr.QrFormat
import ru.pereklichka.qr.QrImage
import ru.pereklichka.ui.FakeGroupViews
import ru.pereklichka.ui.components.AppScreen
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.ButtonKind
import ru.pereklichka.ui.components.CardLabel
import ru.pereklichka.ui.components.HintText
import ru.pereklichka.ui.components.InfoCard
import ru.pereklichka.ui.components.WarningBanner
import ru.pereklichka.ui.components.plural
import ru.pereklichka.ui.components.rememberPermissionWarning
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.spacing

private const val QR_SIZE_PX = 720

// QR и токен под ним всегда тёмные на белом, в любой теме: иначе код хуже считывается на солнце.
private val QrInk = Color(0xFF1B1F1D)

/**
 * QR и сбор: QR во всю ширину на белом, токен под ним, кто уже вступил,
 * «Передать токен тому, кто остаётся» и «Выходим» у руководителя.
 * Поля: `view.group` (для QR), `view.members`, `view.iAmLeader`, `view.route`.
 *
 * QR чёрный на белом даже в тёмной теме: так его считают с полуметра на солнце. «Передать токен» отправляет
 * через `ACTION_SEND` название группы, токен и [serverUrl] (пока у сайта карты нет своего адреса).
 *
 * Если служба не запустилась ([serviceStarted] равен false) или не хватает разрешений, над QR предупреждение
 * со входом на экран разрешений: без службы телефоны участников не найдут этот.
 *
 * Задача: docs/team/valentin-ui.md
 */
@Composable
fun LobbyScreen(
    view: GroupView,
    serverUrl: String,
    serviceStarted: Boolean?,
    onStartRoute: () -> Unit,
    onOpenGroup: () -> Unit,
    onPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val qrText = remember(view.group) {
        QrFormat.encode(QrPayload(view.group.token, view.group.name, view.group.leaderId))
    }
    val qrBitmap = remember(qrText) { QrImage.bitmap(qrText, QR_SIZE_PX).asImageBitmap() }
    val others = view.members.filterNot { it.isMe }
    val subtitle = buildString {
        append(if (view.iAmLeader) "Вы руководитель" else "Вы участник")
        append(" · ")
        append(
            if (others.isEmpty()) "пока никто не вступил"
            else "вступили ${others.size} ${plural(others.size, "человек", "человека", "человек")}",
        )
    }
    val share = {
        val text = buildString {
            appendLine("Группа «${view.group.name}» в Перекличке")
            appendLine("Токен: ${view.group.token}")
            if (serverUrl.isNotBlank()) append("Карта группы: $serverUrl")
        }.trim()
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Передать токен"))
    }

    AppScreen(
        title = view.group.name,
        subtitle = subtitle,
        bottom = {
            BigButtonFull("Передать токен тому, кто остаётся", share, ButtonKind.SECONDARY)
            if (view.iAmLeader && view.route == RouteState.LOBBY) {
                BigButtonFull("Выходим", onStartRoute)
            } else {
                BigButtonFull("К списку группы", onOpenGroup)
            }
        },
    ) {
        rememberPermissionWarning(serviceStarted)?.let { WarningBanner(it, onPermissions) }
        InfoCard(container = Color.White) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.m),
            ) {
                Image(
                    bitmap = qrBitmap,
                    contentDescription = "QR-код для вступления в группу ${view.group.name}",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
                Text(
                    view.group.token,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    letterSpacing = 2.sp,
                    color = QrInk,
                    textAlign = TextAlign.Center,
                )
            }
        }
        HintText(
            "Участники сканируют код в приложении, интернет не нужен",
            Modifier.fillMaxWidth(),
        )
        InfoCard {
            CardLabel("Вступили")
            if (others.isEmpty()) {
                Text("Пока никого. Список обновится, когда телефоны участников найдут ваш.", style = MaterialTheme.typography.bodyMedium)
            } else {
                others.forEach { member ->
                    Text(member.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Preview(name = "QR и сбор")
@Preview(name = "QR и сбор, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LobbyPreview() {
    PereklichkaTheme {
        LobbyScreen(FakeGroupViews.leader.copy(route = RouteState.LOBBY), "http://192.168.1.20:8080", true, {}, {}, {})
    }
}
