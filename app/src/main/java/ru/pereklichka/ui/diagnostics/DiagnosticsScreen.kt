package ru.pereklichka.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Диагностика для прогонов на телефонах: кто я, в какой группе, работает ли радио и фон, сколько записей в базе,
 * что делает движок. Здесь же режим демо, адрес сервера и «Отправить на сервер сейчас». Открывается со старта
 * и с экрана группы.
 *
 * Соседей с временем последнего сообщения и список блокировки добавит Ольга, строку «Не беспокоить» Валентин.
 * Задача: docs/team/olga-transport.md
 */
@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val demoMode by viewModel.demoMode.collectAsStateWithLifecycle()
    val uploadError by viewModel.uploadError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editedUrl by remember(serverUrl) { mutableStateOf(serverUrl) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section("Группа")
            Line("member_id", state.group?.me?.value ?: "нет группы")
            Line("Группа", state.group?.let { "${it.name}, тег ${it.tag}" } ?: "нет")
            Line("Руководитель", state.group?.let { if (it.leaderId == it.me) "я" else it.leaderId.value } ?: "нет")

            Section("Телефон")
            Line("Bluetooth", yesNo(state.system.bluetoothEnabled))
            Line("Google Play Services", yesNo(state.system.playServicesAvailable))
            Line("Исключение из оптимизации батареи", yesNo(state.system.ignoringBatteryOptimizations))
            if (!state.system.ignoringBatteryOptimizations) {
                Button(onClick = { context.startActivity(viewModel.powerTools.ignoreBatteryOptimizationsIntent()) }) {
                    Text("Запросить исключение")
                }
            }
            Button(onClick = { viewModel.powerTools.openAutostartSettings(context) }) { Text("Автозапуск (Xiaomi, Honor)") }
            Line("Уведомления", yesNo(state.system.notificationsEnabled))

            Section("Обмен")
            Line("Движок", if (state.sync.running) "работает" else "остановлен")
            Line("Соседи", state.connectedPeers.joinToString().ifEmpty { "нет" })
            Line("Сообщений отправлено и получено", "${state.sync.sentMessages} / ${state.sync.receivedMessages}")
            Line("Неразборчивых", state.sync.corruptMessages.toString())
            Line("Повторы, не по порядку, конфликты", state.sync.applied.run { "$duplicates / $outOfOrder / $conflicts" })

            Section("База")
            Line("События", state.counts.events.toString())
            Line("Точки: всего, к отправке, отклонено", state.counts.run { "$points / $unsentPoints / $rejectedPoints" })
            Line("Состояния участников", state.counts.states.toString())

            Section("Настройки")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Режим демо: «Не слышно» через 60 с", Modifier.weight(1f))
                Switch(checked = demoMode, onCheckedChange = viewModel::setDemoMode)
            }
            OutlinedTextField(
                value = editedUrl,
                onValueChange = { editedUrl = it },
                label = { Text("Адрес сервера, например http://192.168.1.20:8080") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { viewModel.setServerUrl(editedUrl) }) { Text("Сохранить адрес") }
            Button(onClick = viewModel::uploadNow) { Text("Отправить на сервер сейчас") }
            Line("Ошибка отправки", uploadError ?: "нет")
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Text(value)
    }
}

private fun yesNo(value: Boolean) = if (value) "да" else "нет"
