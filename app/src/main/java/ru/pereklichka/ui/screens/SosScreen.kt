package ru.pereklichka.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.upload.UploadScheduler

@Composable
fun SosScreen(view: GroupView, onSos: () -> Unit) {
    val context = LocalContext.current
    val work = remember(view.group.tag) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UploadScheduler.sosName(view.group.tag))
    }
    val workInfos by work.collectAsStateWithLifecycle(initialValue = emptyList())
    val status = when (workInfos.lastOrNull()?.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "SOS ожидает отправки."
        WorkInfo.State.RUNNING -> "Отправляем SOS…"
        WorkInfo.State.SUCCEEDED -> "Предупреждение передано сервису уведомлений."
        WorkInfo.State.FAILED -> "Не удалось отправить SOS. Проверьте настройки и попробуйте снова."
        else -> null
    }
    StubScreen("SOS") {
        StubButton("Отправить SOS", onSos)
        if (status != null) Text(status)
    }
}
