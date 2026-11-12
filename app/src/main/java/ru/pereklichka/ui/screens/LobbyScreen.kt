package ru.pereklichka.ui.screens

import android.content.Intent
import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.pereklichka.R
import ru.pereklichka.domain.model.QrPayload
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.qr.QrFormat
import ru.pereklichka.qr.QrImage
import ru.pereklichka.ui.FakeGroupViews
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.CustomTopScreen
import ru.pereklichka.ui.components.IconCircle
import ru.pereklichka.ui.components.ListCard
import ru.pereklichka.ui.components.SymIcon
import ru.pereklichka.ui.components.WarningBanner
import ru.pereklichka.ui.components.avatarColor
import ru.pereklichka.ui.components.rememberPermissionWarning
import ru.pereklichka.ui.theme.PereklichkaTheme

private const val QR_SIZE_PX = 720

/** Сколько аватаров вступивших рисовать внахлёст; остальные сводятся в кружок «+N». */
private const val AVATARS_SHOWN = 4

/**
 * Сбор: шапка с названием группы, меткой этапа («Сбор», «В пути», «Завершён») и кнопкой «Поделиться», карточка
 * с QR-кодом для вступления, кто уже вступил, и внизу «Выходим» у руководителя на сборе. Участнику и после начала
 * маршрута внизу «К списку группы».
 *
 * QR чёрный на белом даже в тёмной теме: так его считают с полуметра на солнце. Ключ группы скрыт и показывается
 * кнопкой «Показать ключ группы». «Поделиться» отправляет через `ACTION_SEND` название группы, ключ и [serverUrl]
 * (пока у сайта карты нет своего адреса).
 *
 * Если служба не запустилась ([serviceStarted] равен false) или не хватает разрешений, над QR предупреждение
 * со входом на экран разрешений: без службы телефоны участников не найдут этот.
 *
 * У руководителя на сборе в шапке меню с «Отменить сбор» ([onCancelGathering]) после подтверждения.
 */
@Composable
fun LobbyScreen(
    view: GroupView,
    serverUrl: String,
    serviceStarted: Boolean?,
    onStartRoute: () -> Unit,
    onOpenGroup: () -> Unit,
    onPermissions: () -> Unit,
    onCancelGathering: () -> Unit = {},
) {
    val context = LocalContext.current
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    val share = {
        val text = buildString {
            appendLine("Группа «${view.group.name}» в Перекличке")
            appendLine("Ключ группы: ${view.group.token}")
            if (serverUrl.isNotBlank()) append("Карта группы: $serverUrl")
        }.trim()
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Передать ключ группы"))
    }
    val leaderInLobby = view.iAmLeader && view.route == RouteState.LOBBY

    CustomTopScreen(
        top = { LobbyTopBar(view, share, onCancelGathering = { confirmCancel = true }.takeIf { leaderInLobby }) },
        contentSpacing = 14.dp,
        bottom = {
            if (leaderInLobby) {
                BigButtonFull("Выходим", onStartRoute, icon = R.drawable.sym_hiking, large = true)
                Text(
                    "После «Выходим» начнётся трек. Вступить по коду можно и потом",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                BigButtonFull("К списку группы", onOpenGroup, large = true)
            }
        },
    ) {
        // После завершения маршрута служба выключается сама или по «Выключить», и предупреждать не о чем.
        if (view.route != RouteState.FINISHED) rememberPermissionWarning(serviceStarted)?.let { WarningBanner(it, onPermissions) }
        QrCard(view)
        JoinedCard(view.members.filterNot { it.isMe }, view.iAmLeader)
    }

    if (confirmCancel) {
        CancelGatheringDialog(view, onConfirm = { confirmCancel = false; onCancelGathering() }, onDismiss = { confirmCancel = false })
    }
}

/** Название группы, метка этапа с ролью, «Поделиться» и у руководителя на сборе меню с «Отменить сбор». */
@Composable
private fun LobbyTopBar(view: GroupView, onShare: () -> Unit, onCancelGathering: (() -> Unit)?) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                view.group.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Row(
                Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (view.route) {
                    RouteState.LOBBY -> StageChip(R.drawable.sym_groups, "Сбор")
                    RouteState.ACTIVE -> StageChip(R.drawable.sym_hiking, "В пути")
                    RouteState.FINISHED -> StageChip(R.drawable.sym_flag, if (view.routeStartedAgo == null) "Отменён" else "Завершён")
                }
                Text(
                    if (view.iAmLeader) "Вы руководитель" else "Вы участник",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onShare) {
            SymIcon(R.drawable.sym_share, contentDescription = "Поделиться ключом группы")
        }
        if (onCancelGathering != null) {
            Box {
                IconButton(onClick = { menuOpen = true }) { SymIcon(R.drawable.sym_more_vert, contentDescription = "Меню") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Отменить сбор", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onCancelGathering() },
                    )
                }
            }
        }
    }
}

/** Метка этапа маршрута в рамке, высотой 24 dp. */
@Composable
private fun StageChip(@DrawableRes icon: Int, text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.heightIn(min = 24.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SymIcon(icon, size = 16.dp)
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Карточка с QR на белом квадрате, подписями и скрытым ключом группы. */
@Composable
private fun QrCard(view: GroupView) {
    val qrText = remember(view.group) {
        QrFormat.encode(QrPayload(view.group.token, view.group.name, view.group.leaderId))
    }
    val qrBitmap = remember(qrText) { QrImage.bitmap(qrText, QR_SIZE_PX).asImageBitmap() }
    var keyShown by rememberSaveable { mutableStateOf(false) }
    ListCard {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                bitmap = qrBitmap,
                contentDescription = "QR-код для вступления в группу ${view.group.name}",
                filterQuality = FilterQuality.None,
                // Белый фон с полями самого кода: в тёмной теме вокруг QR остаётся светлая рамка.
                modifier = Modifier.size(264.dp).background(Color.White, RoundedCornerShape(12.dp)),
            )
            Text("Покажите код участникам", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
            Text(
                "Сканируют в приложении, интернет не нужен",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (keyShown) {
                Text(
                    view.group.token,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            TextButton(onClick = { keyShown = !keyShown }, modifier = Modifier.padding(top = 2.dp)) {
                SymIcon(R.drawable.sym_key, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(if (keyShown) "Скрыть ключ группы" else "Показать ключ группы", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/**
 * Кто уже в группе, кроме меня: аватары внахлёст, число и имена. У руководителя подпись «Вступили N»,
 * у участника «В группе ещё N»: в его списке есть и руководитель.
 */
@Composable
private fun JoinedCard(others: List<MemberView>, iAmLeader: Boolean) {
    ListCard {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (others.isEmpty()) {
                IconCircle(R.drawable.sym_group, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                AvatarStack(others)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        others.isEmpty() -> "Пока никто не вступил"
                        iAmLeader -> "Вступили ${others.size}"
                        else -> "В группе ещё ${others.size}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (others.isEmpty()) "Список обновится, когда телефоны участников найдут ваш" else others.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Аватары 40 dp внахлёст на 10 dp с белой обводкой; больше [AVATARS_SHOWN] сводятся в «+N». */
@Composable
private fun AvatarStack(members: List<MemberView>) {
    val shown = if (members.size > AVATARS_SHOWN) members.take(AVATARS_SHOWN - 1) else members
    val rest = members.size - shown.size
    val ring = MaterialTheme.colorScheme.surfaceContainerLowest
    Row(Modifier.padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
        shown.forEach { member ->
            MiniAvatar(member.name.firstOrNull()?.uppercase() ?: "?", avatarColor(member.id.value), Color.White, ring)
        }
        if (rest > 0) {
            MiniAvatar("+$rest", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant, ring)
        }
    }
}

@Composable
private fun MiniAvatar(text: String, color: Color, textColor: Color, ring: Color) {
    Box(
        Modifier.size(40.dp).border(3.dp, ring, CircleShape).padding(3.dp).background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = textColor, style = MaterialTheme.typography.labelLarge, fontSize = 15.sp)
    }
}

@Preview(name = "Сбор, руководитель")
@Preview(name = "Сбор, руководитель, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LobbyPreview() {
    PereklichkaTheme {
        LobbyScreen(FakeGroupViews.leader.copy(route = RouteState.LOBBY), "http://192.168.1.20:8080", true, {}, {}, {})
    }
}

@Preview(name = "Сбор, участник")
@Composable
private fun LobbyParticipantPreview() {
    PereklichkaTheme {
        LobbyScreen(FakeGroupViews.participant.copy(route = RouteState.LOBBY), "", true, {}, {}, {})
    }
}

@Preview(name = "Сбор, никто не вступил")
@Composable
private fun LobbyEmptyPreview() {
    val view = FakeGroupViews.leader.let { it.copy(route = RouteState.LOBBY, members = it.members.filter { m -> m.isMe }) }
    PereklichkaTheme { LobbyScreen(view, "", true, {}, {}, {}) }
}
