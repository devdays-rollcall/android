package ru.pereklichka.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.ui.FakeGroupViews
import ru.pereklichka.ui.components.AppScreen
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.ButtonKind
import ru.pereklichka.ui.components.CardLabel
import ru.pereklichka.ui.components.HintText
import ru.pereklichka.ui.components.InfoCard
import ru.pereklichka.ui.components.formatAge
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.spacing
import ru.pereklichka.upload.UploadScheduler
import java.util.Locale
import kotlin.math.roundToInt

private const val HOLD_MS = 2_000
private val SosButtonSize = 220.dp
private val ProgressWidth = 10.dp

/**
 * SOS. Отправка удержанием кнопки 2 секунды: так её не нажать случайно в кармане. Пока кнопку держат, по краю
 * идёт кольцо прогресса; отпустили раньше, ничего не отправилось. В TalkBack то же действие доступно долгим нажатием.
 *
 * После отправки: кнопка звонка в 112 (`ACTION_DIAL`, разрешение на звонки не нужно, вызов человек нажмёт сам)
 * и текст с координатами из моей позиции в `view.members`, который можно скопировать или зачитать диспетчеру.
 * Рядом прямо сказано, куда что дойдёт: звонок только при сотовой сети любого оператора, а сигнал SOS только
 * до телефонов группы, связанных цепочкой, и при интернете на сервер. Состояние отправки на сервер [serverStatus]
 * берётся из задачи WorkManager.
 *
 * Задача: docs/team/valentin-ui.md
 */
@Composable
fun SosScreen(
    view: GroupView,
    onSos: () -> Unit,
    onBack: () -> Unit,
    serverStatus: String? = rememberSosServerStatus(view),
) {
    val me = view.members.firstOrNull { it.isMe }
    // В фейковом режиме действие ничего не пишет, поэтому запоминаем
    var sentHere by rememberSaveable { mutableStateOf(false) }
    val sent = sentHere || me?.hasSos == true

    AppScreen(
        title = if (sent) "SOS отправлен" else "SOS",
        subtitle = if (sent) "Группа получила сигнал тревоги" else null,
        onBack = onBack,
        bottom = { if (sent) Call112Button() },
    ) {
        if (sent) {
            CoordinatesCard(me)
            serverStatus?.let { ServerStatusCard(it) }
        } else {
            HoldToSend(onSend = { sentHere = true; onSos() })
        }
        LimitsCard()
    }
}

/** Текст о том, где сейчас задача отправки SOS на сервер; null, пока SOS не отправляли. */
@Composable
private fun rememberSosServerStatus(view: GroupView): String? {
    val context = LocalContext.current
    val work = remember(view.group.tag) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UploadScheduler.sosName(view.group.tag))
    }
    val workInfos by work.collectAsStateWithLifecycle(initialValue = emptyList())
    return when (workInfos.lastOrNull()?.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Ждёт интернета, отправится сама."
        WorkInfo.State.RUNNING -> "Отправляем…"
        WorkInfo.State.SUCCEEDED -> "Сервер принял SOS и разослал уведомление наблюдателям."
        WorkInfo.State.FAILED -> "Не удалось отправить. Проверьте адрес сервера в настройках и отправьте SOS снова."
        else -> null
    }
}

@Composable
private fun ServerStatusCard(status: String) {
    InfoCard {
        CardLabel("Сервер")
        Text(status, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HoldToSend(onSend: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.l),
    ) {
        Text(
            "Держите кнопку 2 секунды",
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
            color = scheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier
                .size(SosButtonSize)
                .semantics {
                    contentDescription = "Отправить SOS. Удерживайте"
                    onLongClick(label = "Отправить SOS") { onSend(); true }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        val hold = scope.launch {
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                            onSend()
                        }
                        tryAwaitRelease()
                        if (progress.value < 1f) {
                            hold.cancel()
                            scope.launch { progress.animateTo(0f, tween(200)) }
                        }
                    })
                }
                .drawWithContent {
                    drawContent()
                    val stroke = ProgressWidth.toPx()
                    drawArc(
                        color = scheme.onError,
                        startAngle = -90f,
                        sweepAngle = 360f * progress.value,
                        useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke),
                    )
                },
            shape = CircleShape,
            color = scheme.error,
            contentColor = scheme.onError,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("SOS", fontSize = 48.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Call112Button() {
    val context = LocalContext.current
    BigButtonFull(
        "Позвонить 112",
        { context.startActivity(Intent(Intent.ACTION_DIAL, "tel:112".toUri())) },
        ButtonKind.DANGER,
    )
}

/** Координаты для диспетчера: строкой. */
@Composable
private fun CoordinatesCard(me: MemberView?) {
    val context = LocalContext.current
    val position = me?.position
    InfoCard {
        CardLabel("Ваши координаты")
        if (position == null) {
            Text("GPS ещё не определил позицию", style = MaterialTheme.typography.titleMedium)
            HintText("Выйдите на открытое место. Координаты появятся здесь сами.")
            return@InfoCard
        }
        val text = String.format(Locale.US, "%.5f, %.5f", position.latitude, position.longitude)
        SelectionContainer {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        HintText(spokenCoordinates(position.latitude, position.longitude))
        HintText(
            listOfNotNull(
                me.accuracyM?.let { "точность ±${it.roundToInt()} м" },
                me.positionAge?.let { "определено ${formatAge(it)}" },
            ).joinToString(" · "),
        )
        BigButtonFull(
            "Скопировать координаты",
            {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Координаты", text))
                Toast.makeText(context, "Координаты скопированы", Toast.LENGTH_SHORT).show()
            },
            ButtonKind.SECONDARY,
        )
    }
}

private fun spokenCoordinates(lat: Double, lon: Double): String {
    val ru = Locale.forLanguageTag("ru")
    val latSide = if (lat >= 0) "северная" else "южная"
    val lonSide = if (lon >= 0) "восточная" else "западная"
    return String.format(ru, "%s широта %.5f, %s долгота %.5f", latSide, kotlin.math.abs(lat), lonSide, kotlin.math.abs(lon))
        .replaceFirstChar { it.uppercase() }
}

@Composable
private fun LimitsCard() {
    InfoCard {
        CardLabel("Куда дойдёт сигнал")
        Text(
            "Звонок в 112 пройдёт, только если есть сотовая сеть любого оператора.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Сигнал SOS дойдёт до телефонов группы, связанных с вами цепочкой. Если есть интернет, он уйдёт ещё и " +
                "на сервер, а тот пришлёт уведомление наблюдателям. Спасателям он сам не уходит.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Preview(name = "SOS: удержание")
@Preview(name = "SOS: удержание, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SosHoldPreview() {
    PereklichkaTheme { SosScreen(FakeGroupViews.participant, {}, {}, serverStatus = null) }
}

@Preview(name = "SOS: отправлен")
@Composable
private fun SosSentPreview() {
    val view = FakeGroupViews.participant
    PereklichkaTheme {
        SosScreen(view.copy(members = view.members.map { if (it.isMe) it.copy(hasSos = true) else it }), {}, {}, serverStatus = "Ждёт интернета, отправится сама.")
    }
}
