package ru.pereklichka.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.pereklichka.R
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.ui.components.AppScreen
import ru.pereklichka.ui.components.CardShape
import ru.pereklichka.ui.components.HintText
import ru.pereklichka.ui.components.ListCard
import ru.pereklichka.ui.components.ListRow
import ru.pereklichka.ui.components.RU
import ru.pereklichka.ui.components.SectionHeader
import ru.pereklichka.ui.components.SymIcon
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.statusColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Экран «Для разработчиков» для прогонов на телефонах: адрес сервера и отправка, соседи с блокировкой, счётчики
 * обмена и базы, данные группы. Открывается из настроек после семи нажатий на «О приложении».
 */
@Composable
fun DevScreen(onBack: () -> Unit, viewModel: DevViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DevContent(
        state,
        onSaveUrl = viewModel::setServerUrl,
        onUpload = viewModel::uploadNow,
        onBlock = viewModel::setBlocked,
        onBack = onBack,
    )
}

@Composable
private fun DevContent(
    state: DevState,
    onSaveUrl: (String) -> Unit,
    onUpload: () -> Unit,
    onBlock: (MemberId, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var editedUrl by rememberSaveable(state.serverUrl) { mutableStateOf(state.serverUrl) }
    AppScreen(title = "Для разработчиков", subtitle = "Видно только после ${SettingsViewModel.DEV_TAPS} нажатий", onBack = onBack) {
        Section("Сервер") {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value = editedUrl,
                        onValueChange = { editedUrl = it },
                        label = { Text("Адрес сервера") },
                        placeholder = { Text("http://192.168.1.20:8080") },
                        leadingIcon = { SymIcon(R.drawable.sym_dns, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onSaveUrl(editedUrl) }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onSaveUrl(editedUrl) },
                            enabled = editedUrl.trim().trimEnd('/') != state.serverUrl,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text("Сохранить") }
                        OutlinedButton(
                            onClick = onUpload,
                            enabled = state.serverUrl.isNotBlank(),
                            shape = CircleShape,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) {
                            SymIcon(R.drawable.sym_upload, size = 18.dp)
                            Text("Отправить", Modifier.padding(start = 8.dp))
                        }
                    }
                    UploadLine(state)
                }
            }
        }

        Section("Соседи и блокировка") {
            ListCard {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    Text("Участник", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = muted)
                    Text("Блокировать", style = MaterialTheme.typography.bodyMedium, color = muted)
                }
                if (state.members.isEmpty()) {
                    RowDivider()
                    ListRow(title = "Пока никого", subtitle = "Соседи появятся, когда телефон услышит группу")
                }
                state.members.forEach { member ->
                    val blocked = member in state.blocked
                    RowDivider()
                    ListRow(
                        title = state.memberNames[member] ?: member.value,
                        subtitle = neighbourSubtitle(state.heardAgo[member], member in state.groupHeard),
                        modifier = Modifier.toggleable(blocked, role = Role.Switch) { onBlock(member, it) },
                        trailing = { Switch(checked = blocked, onCheckedChange = null) },
                    )
                }
            }
            HintText(
                "Заблокированного телефон не слышит напрямую. Так на одном столе проверяют пересылку через посредника.",
                Modifier.padding(horizontal = 4.dp),
            )
        }

        Section("Счётчики") {
            KeyValueCard(
                listOf(
                    "Сообщения: отправлено / получено" to "${state.sync.sentMessages} / ${state.sync.receivedMessages}",
                    "Неразборчивых" to state.sync.corruptMessages.toString(),
                    "Повторы / не по порядку / конфликты" to state.sync.applied.run { "$duplicates / $outOfOrder / $conflicts" },
                    "События · состояния участников" to "${state.counts.events} · ${state.counts.states}",
                    "Точки: всего / ждут / отклонены" to state.counts.run {
                        "${formatCount(points)} / ${formatCount(unsentPoints)} / ${formatCount(rejectedPoints)}"
                    },
                ),
            )
        }

        Section("Группа") {
            val group = state.group
            SelectionContainer {
                KeyValueCard(
                    listOf(
                        "member_id" to (group?.me?.value ?: "нет группы"),
                        "Тег группы" to (group?.tag ?: "нет"),
                        "Название" to (group?.name ?: "нет"),
                        "Руководитель" to leaderText(group, state.memberNames),
                        "Обмен" to if (state.sync.running) "работает" else "остановлен",
                        "Соединения Nearby" to state.connectedPeers
                            .joinToString { state.memberNames[it] ?: it.value.take(8) }
                            .ifEmpty { "нет" },
                    ),
                    monospaceKeys = setOf("member_id", "Тег группы"),
                )
            }
        }
    }
}

private fun leaderText(group: GroupInfo?, names: Map<MemberId, String>): String = when {
    group == null -> "нет"
    group.leaderId == group.me -> "я"
    else -> names[group.leaderId] ?: group.leaderId.value
}

/** Строка под кнопками: когда сервер последний раз принял точки или какая была ошибка. */
@Composable
private fun UploadLine(state: DevState) {
    val status = MaterialTheme.statusColors
    val (icon, color, text) = when {
        state.serverUrl.isBlank() ->
            Triple(R.drawable.sym_info, MaterialTheme.colorScheme.onSurfaceVariant, "Адрес не задан, точки и SOS не отправляются")
        state.uploadError != null -> Triple(R.drawable.sym_error_fill, status.lost.content, state.uploadError)
        state.lastAcceptedWallMs != null -> Triple(
            R.drawable.sym_check_circle_fill,
            status.ok.content,
            "Последняя отправка ${formatTime(state.lastAcceptedWallMs)}, ошибок нет",
        )
        else -> Triple(R.drawable.sym_schedule, MaterialTheme.colorScheme.onSurfaceVariant, "Сервер ещё не принимал точки")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SymIcon(icon, tint = color, size = 18.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** «12:51» сегодня, «9 нояб., 12:51» в другие дни. */
private fun formatTime(wallMs: Long): String {
    val time = Instant.ofEpochMilli(wallMs).atZone(ZoneId.systemDefault())
    val pattern = if (time.toLocalDate() == LocalDate.now()) "HH:mm" else "d MMM, HH:mm"
    return time.format(DateTimeFormatter.ofPattern(pattern, RU))
}

/** Белая карточка из строк с названием слева и значением справа, между строками разделители. */
@Composable
private fun KeyValueCard(rows: List<Pair<String, String>>, monospaceKeys: Set<String> = emptySet()) {
    Card {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            rows.forEachIndexed { index, (key, value) ->
                if (index > 0) RowDivider()
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        key,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val mono = key in monospaceKeys
                    Text(
                        value,
                        // Значение прижато вправо, а длинный id обрезается, чтобы подпись слева не сжималась в столбик.
                        modifier = Modifier.widthIn(max = 180.dp),
                        style = if (mono) {
                            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        } else {
                            MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        },
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title)
        content()
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

@Preview(heightDp = 1300)
@Composable
private fun DevPreview() {
    val me = MemberId("19134447-d846-46fd-9a4e-3c1f0d2b7a11")
    val daniil = MemberId("daniil")
    val olga = MemberId("olga")
    val valentin = MemberId("valentin")
    PereklichkaTheme {
        DevContent(
            state = DevState(
                group = GroupInfo("085422ebcc10b0f4", "token", "Поход", leaderId = me, me = me, createdAtWallMs = 0),
                counts = DatabaseCounts(events = 37, points = 1_252, unsentPoints = 12, rejectedPoints = 0, states = 5),
                heardAgo = mapOf(daniil to 3_000L, olga to 5_000L),
                members = listOf(daniil, olga, valentin),
                groupHeard = setOf(daniil, olga, valentin),
                blocked = setOf(valentin),
                memberNames = mapOf(daniil to "Даниил", olga to "Ольга", valentin to "Валентин"),
                serverUrl = "http://192.168.1.20:8080",
                lastAcceptedWallMs = System.currentTimeMillis(),
            ),
            onSaveUrl = {},
            onUpload = {},
            onBlock = { _, _ -> },
            onBack = {},
        )
    }
}
