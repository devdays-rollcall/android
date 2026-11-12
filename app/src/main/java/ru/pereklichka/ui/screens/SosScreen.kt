package ru.pereklichka.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
import ru.pereklichka.R
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.rules.AlarmView
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.time.Age
import ru.pereklichka.ui.FakeGroupViews
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.ButtonKind
import ru.pereklichka.ui.components.ListCard
import ru.pereklichka.ui.components.SymIcon
import ru.pereklichka.ui.components.formatAge
import ru.pereklichka.ui.components.plural
import ru.pereklichka.ui.components.receivedBy
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.StatusColor
import ru.pereklichka.ui.theme.statusColors
import ru.pereklichka.upload.UploadScheduler
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val HOLD_MS = 2_000
private val RingSize = 230.dp
private val SosButtonSize = 196.dp
private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * SOS. Отправка удержанием кнопки 2 секунды: так её не нажать случайно в кармане. Пока кнопку держат, кольцо вокруг
 * неё заполняется, а под надписью идёт обратный отсчёт; отпустили раньше, ничего не отправилось. В момент отправки
 * телефон вибрирует. В TalkBack то же действие доступно долгим нажатием. Под кнопкой сказано, кому уйдёт сигнал:
 * телефонам группы (и сколько их рядом сейчас), наблюдателям через сервер, а спасателям нет.
 *
 * После отправки красная шапка и шаги доставки: SOS записан на телефоне; кто из соседей на связи его получил
 * (по [reportedBySeq] и номеру события моего SOS, см. `receivedBy`); что с отправкой на сервер ([serverState] из задачи
 * WorkManager, при неудаче [onResendToServer] ставит её снова). Ниже координаты из моей позиции в `view.members`:
 * их можно скопировать или открыть текстом для диктовки. Внизу звонок в 112 (`ACTION_DIAL`, разрешение на звонки
 * не нужно, вызов человек нажмёт сам) и отмена SOS: после подтверждения [onCancelSos] снимает его у всех.
 *
 * Отправлен ли SOS, экран берёт из вида (`hasSos` у меня), поэтому после снятия SOS снова видна кнопка удержания.
 * Только что нажатая кнопка считается отправкой, пока вид не догнал её.
 *
 * Задача: docs/team/valentin-ui.md
 */
@Composable
fun SosScreen(
    view: GroupView,
    reportedBySeq: Map<MemberId, Map<MemberId, Long>>,
    onSos: () -> Unit,
    onCancelSos: () -> Unit,
    onResendToServer: () -> Unit,
    onBack: () -> Unit,
    serverState: WorkInfo.State? = rememberSosServerState(view),
) {
    val me = view.members.firstOrNull { it.isMe }
    val hasSos = me?.hasSos == true
    // Между удержанием и новым видом проходит до пары секунд, и без этой отметки на это время вернулась бы кнопка.
    // В фейковом режиме вид SOS не покажет, и отметка держится, пока экран открыт или SOS не отменят.
    var justSent by remember { mutableStateOf(false) }
    LaunchedEffect(hasSos) { if (hasSos) justSent = false }
    val sent = hasSos || justSent

    if (sent) {
        SosSent(
            view = view,
            me = me,
            sos = view.alarms.firstOrNull { it.isSos && it.target == view.me },
            reportedBySeq = reportedBySeq,
            serverState = serverState,
            onCancelSos = { justSent = false; onCancelSos() },
            onResendToServer = onResendToServer,
            onBack = onBack,
        )
    } else {
        SosHold(view, onSend = { justSent = true; onSos() }, onBack = onBack)
    }
}

/** Состояние задачи отправки SOS на сервер; null, пока SOS на сервер не отправляли. */
@Composable
private fun rememberSosServerState(view: GroupView): WorkInfo.State? {
    val context = LocalContext.current
    val work = remember(view.group.tag) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UploadScheduler.sosName(view.group.tag))
    }
    val workInfos by work.collectAsStateWithLifecycle(initialValue = emptyList())
    return workInfos.lastOrNull()?.state
}

/** До отправки: шапка с крестиком, вопрос, кнопка с кольцом, кому уйдёт сигнал и звонок в 112. */
@Composable
private fun SosHold(view: GroupView, onSend: () -> Unit, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = scheme.background, contentColor = scheme.onBackground) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HeaderIconButton(R.drawable.sym_close, "Закрыть", scheme.onBackground, onBack)
                Text("SOS", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Нужна помощь?", fontSize = 20.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    Text(
                        "Держите кнопку, пока кольцо не заполнится",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                HoldToSend(onSend)
                RecipientsCard(view)
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
                Call112Button(ButtonKind.SECONDARY)
            }
        }
    }
}

/**
 * Красный круг 196 dp внутри кольца прогресса. Кольцо заполняется за [HOLD_MS], под «SOS» отсчёт секунд до отправки.
 * Отпустили раньше, кольцо откатывается к нулю.
 */
@Composable
private fun HoldToSend(onSend: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val send = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onSend()
    }
    val value = progress.value
    val countdown = when {
        value <= 0f -> "держите 2 с"
        value >= 1f -> "отправляем"
        else -> "ещё ${ceil((1f - value) * HOLD_MS / 1000f).toInt()} с"
    }
    Box(
        modifier = Modifier
            .size(RingSize)
            .semantics(mergeDescendants = true) {
                contentDescription = "Отправить SOS. Удерживайте"
                onLongClick(label = "Отправить SOS") { send(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val hold = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                        send()
                    }
                    tryAwaitRelease()
                    if (progress.value < 1f) {
                        hold.cancel()
                        scope.launch { progress.animateTo(0f, tween(200)) }
                    }
                })
            }
            .drawBehind {
                drawCircle(scheme.errorContainer)
                drawArc(
                    color = scheme.error,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.value,
                    useCenter = true,
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(SosButtonSize).shadow(12.dp, CircleShape, spotColor = scheme.error, ambientColor = scheme.error),
            shape = CircleShape,
            color = scheme.error,
            contentColor = scheme.onError,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("SOS", fontSize = 52.sp, lineHeight = 60.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text(countdown, fontSize = 15.sp, color = scheme.onError.copy(alpha = 0.9f))
            }
        }
    }
}

/** Кому уйдёт сигнал: группа (и сколько телефонов рядом сейчас), наблюдатели через сервер, а спасатели нет. */
@Composable
private fun RecipientsCard(view: GroupView) {
    // Что сигнал дойдёт, телефон заранее не знает. Известно только, сколько телефонов группы он слышит напрямую.
    val near = view.members.count { !it.isMe && it.heardDirectlyNow }
    val nearText = if (near == 0) {
        "Рядом сейчас телефонов группы нет"
    } else {
        "Рядом сейчас $near ${plural(near, "телефон", "телефона", "телефонов")}"
    }
    ListCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Recipient(R.drawable.sym_groups, "Группа", "Все, кто связан с вами цепочкой. $nearText")
            Recipient(R.drawable.sym_cloud_upload, "Наблюдатели", "Через сервер, как только у вас появится интернет")
            Recipient(R.drawable.sym_call, "Спасатели сами не узнают", "Звоните 112, если есть сотовая сеть любого оператора")
        }
    }
}

@Composable
private fun Recipient(@DrawableRes icon: Int, title: String, text: String) {
    Row(Modifier.semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SymIcon(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(title, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** После отправки: красная шапка, шаги доставки, координаты, звонок в 112 и отмена SOS с подтверждением. */
@Composable
private fun SosSent(
    view: GroupView,
    me: MemberView?,
    sos: AlarmView?,
    reportedBySeq: Map<MemberId, Map<MemberId, Long>>,
    serverState: WorkInfo.State?,
    onCancelSos: () -> Unit,
    onResendToServer: () -> Unit,
    onBack: () -> Unit,
) {
    WhiteStatusBarIcons()
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val header = MaterialTheme.statusColors.alarm
    Surface(Modifier.fillMaxSize(), color = scheme.background, contentColor = scheme.onBackground) {
        Column(Modifier.navigationBarsPadding()) {
            Column(Modifier.fillMaxWidth().background(header.container).statusBarsPadding().padding(bottom = 22.dp)) {
                Row(Modifier.heightIn(min = 64.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    HeaderIconButton(R.drawable.sym_close, "Закрыть", header.content, onBack)
                }
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(
                        "SOS ОТПРАВЛЕН",
                        color = header.content.copy(alpha = 0.9f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        "Группа увидит ваш сигнал",
                        color = header.content,
                        fontSize = 30.sp,
                        lineHeight = 36.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        "Оставайтесь на месте, если можете",
                        color = header.content.copy(alpha = 0.9f),
                        fontSize = 15.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StepsCard(view, sos, reportedBySeq, serverState, onResendToServer)
                CoordinatesCard(me)
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Call112Button(ButtonKind.PRIMARY, large = true)
                BigButtonFull("Помощь не нужна, отменить SOS", { confirmCancel = true }, ButtonKind.SECONDARY)
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Отменить SOS?") },
            text = { Text("Группа увидит, что помощь не нужна. Если SOS ещё ждёт интернета, на сервер он уже не уйдёт.") },
            confirmButton = {
                TextButton(onClick = { confirmCancel = false; onCancelSos() }) {
                    Text("Отменить SOS", color = scheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Не отменять") } },
        )
    }
}

/**
 * Шаги доставки: SOS записан на телефоне, кто из соседей на связи его получил, что с отправкой на сервер.
 * Шага сервера нет, пока задачи отправки нет в WorkManager.
 */
@Composable
private fun StepsCard(
    view: GroupView,
    sos: AlarmView?,
    reportedBySeq: Map<MemberId, Map<MemberId, Long>>,
    serverState: WorkInfo.State?,
    onResendToServer: () -> Unit,
) {
    val colors = MaterialTheme.statusColors
    val got = sos?.let { alarm -> receivedBy(reportedBySeq, view.me, alarm.seq).filter { it != view.me } }.orEmpty()
    val names = got.mapNotNull { id -> view.members.firstOrNull { it.id == id }?.name }
    val someoneNear = view.members.any { !it.isMe && it.heardDirectlyNow }
    ListCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Step(R.drawable.sym_check_fill, colors.ok, "Записан на телефоне", sos?.age?.let(::clockTime) ?: "только что")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (got.isNotEmpty()) {
                val n = got.size
                Step(
                    R.drawable.sym_check_fill,
                    colors.ok,
                    "${plural(n, "Получил", "Получили", "Получили")} $n ${plural(n, "телефон", "телефона", "телефонов")} рядом",
                    names.joinToString(", "),
                )
            } else {
                Step(
                    R.drawable.sym_hourglass_top_fill,
                    colors.away,
                    "Ждём подтверждения от телефонов рядом",
                    if (someoneNear) {
                        "Телефоны рядом подтверждают раз в 10 секунд"
                    } else {
                        "Рядом нет телефонов группы. Сигнал уйдёт, как только они появятся"
                    },
                )
            }
            if (serverState != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ServerStep(serverState, onResendToServer)
            }
        }
    }
}

@Composable
private fun ServerStep(state: WorkInfo.State, onResend: () -> Unit) {
    val colors = MaterialTheme.statusColors
    when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
            Step(R.drawable.sym_hourglass_top_fill, colors.away, "Сервер ждёт интернета", "Уйдёт с этого телефона, как только появится интернет")
        WorkInfo.State.RUNNING ->
            Step(R.drawable.sym_hourglass_top_fill, colors.away, "Отправляем на сервер", "Интернет есть, ждём ответа сервера")
        WorkInfo.State.SUCCEEDED ->
            Step(R.drawable.sym_check_fill, colors.ok, "Сервер принял SOS", "Он разослал уведомление наблюдателям")
        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED ->
            Step(R.drawable.sym_error_fill, colors.lost, "Сервер не принял SOS", "Проверьте адрес сервера в настройках") {
                SmallTonalButton("Отправить ещё раз", R.drawable.sym_refresh, onResend, Modifier.padding(top = 8.dp))
            }
    }
}

/** Шаг доставки: значок в цветном круге 36 dp, заголовок и подпись, под ними [extra]. */
@Composable
private fun Step(
    @DrawableRes icon: Int,
    color: StatusColor,
    title: String,
    hint: String,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(36.dp).background(color.container, CircleShape), contentAlignment = Alignment.Center) {
            SymIcon(icon, tint = color.content, size = 22.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            extra()
        }
    }
}

/** Время по часам телефона, когда случилось событие возрастом [age]: «12:53», «около 12:53». */
private fun clockTime(age: Age): String {
    val time = Instant.ofEpochMilli(System.currentTimeMillis() - age.ms).atZone(ZoneId.systemDefault()).format(TimeFormat)
    return if (age.approximate) "около $time" else time
}

/** Тональная кнопка высотой 48 dp со значком: «Копировать», «Прочитать», «Отправить ещё раз». */
@Composable
private fun SmallTonalButton(text: String, @DrawableRes icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer),
    ) {
        SymIcon(icon, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun Call112Button(kind: ButtonKind, large: Boolean = false) {
    val context = LocalContext.current
    BigButtonFull(
        "Позвонить 112",
        { context.startActivity(Intent(Intent.ACTION_DIAL, "tel:112".toUri())) },
        kind,
        icon = R.drawable.sym_call,
        large = large,
    )
}

/** Координаты для диспетчера строкой; «Прочитать» открывает их словами, как их диктовать. */
@Composable
private fun CoordinatesCard(me: MemberView?) {
    val context = LocalContext.current
    val position = me?.position
    var spoken by rememberSaveable { mutableStateOf(false) }
    val hint = MaterialTheme.colorScheme.onSurfaceVariant
    ListCard {
        Column(Modifier.padding(16.dp)) {
            Text("Ваши координаты", style = MaterialTheme.typography.bodyMedium, color = hint)
            if (position == null) {
                Text("GPS ещё не определил позицию", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                Text("Выйдите на открытое место. Координаты появятся здесь сами.", style = MaterialTheme.typography.bodyMedium, color = hint)
                return@Column
            }
            val text = String.format(Locale.US, "%.5f, %.5f", position.latitude, position.longitude)
            SelectionContainer {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            }
            val details = listOfNotNull(
                me.accuracyM?.let { "точность ±${it.roundToInt()} м" },
                me.positionAge?.let { formatAge(it) },
            ).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodyMedium, color = hint)
            if (spoken) {
                Text(
                    spokenCoordinates(position.latitude, position.longitude),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallTonalButton(
                    "Копировать",
                    R.drawable.sym_content_copy,
                    {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("Координаты", text))
                        Toast.makeText(context, "Координаты скопированы", Toast.LENGTH_SHORT).show()
                    },
                    Modifier.weight(1f),
                )
                SmallTonalButton(
                    if (spoken) "Скрыть" else "Прочитать",
                    R.drawable.sym_record_voice_over,
                    { spoken = !spoken },
                    Modifier.weight(1f),
                )
            }
        }
    }
}

private fun spokenCoordinates(lat: Double, lon: Double): String {
    val ru = Locale.forLanguageTag("ru")
    val latSide = if (lat >= 0) "северная" else "южная"
    val lonSide = if (lon >= 0) "восточная" else "западная"
    return String.format(ru, "%s широта %.5f, %s долгота %.5f", latSide, kotlin.math.abs(lat), lonSide, kotlin.math.abs(lon))
        .replaceFirstChar { it.uppercase() }
}

@Preview(name = "SOS: удержание")
@Preview(name = "SOS: удержание, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SosHoldPreview() {
    PereklichkaTheme { SosScreen(FakeGroupViews.participant, emptyMap(), {}, {}, {}, {}, serverState = null) }
}

/** Мой SOS в фейковом виде Даниила: он отправлен, его номер события 7. */
private fun withMySos(view: GroupView): GroupView {
    val me = view.members.first { it.isMe }
    val sos = AlarmView(EventId("5b4a3928-1706-4f5e-8d4c-3b2a19087f6e"), seq = 7, me.id, me.name, me.id, me.name, isSos = true, Age(40_000, approximate = false))
    return view.copy(
        members = view.members.map { if (it.isMe) it.copy(hasSos = true, hasActiveAlarm = true) else it },
        alarms = listOf(sos) + view.alarms,
    )
}

@Preview(name = "SOS: отправлен, двое получили, сервер ждёт интернета")
@Preview(name = "SOS: отправлен, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SosSentPreview() {
    val view = withMySos(FakeGroupViews.participant)
    val me = view.me
    val neighbours = view.members.filter { it.name == "Ольга" || it.name == "Никита" }.map { it.id }
    PereklichkaTheme {
        SosScreen(view, neighbours.associateWith { mapOf(me to 7L) }, {}, {}, {}, {}, serverState = WorkInfo.State.ENQUEUED)
    }
}

@Preview(name = "SOS: отправлен, никто не подтвердил, сервер не принял")
@Composable
private fun SosSentFailedPreview() {
    PereklichkaTheme {
        SosScreen(withMySos(FakeGroupViews.participant), emptyMap(), {}, {}, {}, {}, serverState = WorkInfo.State.FAILED)
    }
}
