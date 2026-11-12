package ru.pereklichka.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import ru.pereklichka.domain.model.GroupInfo
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ставит [UploadWorker] и [SosWorker] в очередь WorkManager. Вызывают его `RouteService`, `TrackRecorder`,
 * `GroupViewModel` и экран «Для разработчиков».
 *
 * Отправка не зависит от службы: WorkManager сам дождётся сети и повторит разовую задачу после сбоя с растущей
 * паузой от 10 секунд, а периодическая просто сработает в свой срок.
 */
@Singleton
class UploadScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    private val needsNetwork = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Отправить, когда будет сеть. Если разовая задача уже стоит в очереди, в том числе в паузе после сбоя,
     * она остаётся как есть. Вызывают `TrackRecorder` на каждую новую точку и служба после завершения маршрута.
     */
    fun uploadNow() = enqueueUpload(ExistingWorkPolicy.KEEP)

    /**
     * Отправить сразу, не дожидаясь конца паузы после сбоя: ждущая разовая задача заменяется новой. Идущую
     * отправку замена не трогает, иначе каждое переключение Wi-Fi и сотовой сети в горах обрывало бы её посреди
     * запроса. Вызывают служба при появлении сети с интернетом и кнопка «Отправить» на экране «Для разработчиков».
     */
    suspend fun restartUpload() {
        val running = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UPLOAD_NOW).first()
            .any { it.state == WorkInfo.State.RUNNING }
        enqueueUpload(if (running) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE)
    }

    fun sendSos(group: GroupInfo, serverUrl: String) {
        val request = OneTimeWorkRequestBuilder<SosWorker>()
            .setInputData(workDataOf(SosWorker.TOKEN to group.token, SosWorker.MEMBER to group.me.value, SosWorker.URL to serverUrl))
            .setConstraints(needsNetwork)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(sosName(group.tag), ExistingWorkPolicy.KEEP, request)
    }

    /** Снимает SOS группы [groupTag], который ещё ждёт сети: человек отменил свой SOS, и сервер о нём не узнает. */
    fun cancelSos(groupTag: String) {
        WorkManager.getInstance(context).cancelUniqueWork(sosName(groupTag))
    }

    /** Страховка на случай, если сигнал о сети не пришёл: раз в 15 минут, это минимум WorkManager. */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<UploadWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(needsNetwork)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UPLOAD_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Снимает периодическую отправку: после «Новой группы» отправлять нечего до новой группы. */
    fun cancelPeriodic() {
        WorkManager.getInstance(context).cancelUniqueWork(UPLOAD_PERIODIC)
    }

    private fun enqueueUpload(policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(needsNetwork)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UPLOAD_NOW, policy, request)
    }

    companion object {
        fun sosName(groupTag: String) = "sos-$groupTag"

        private const val UPLOAD_NOW = "upload-now"
        private const val UPLOAD_PERIODIC = "upload-periodic"
        private const val BACKOFF_SECONDS = 10L
        private const val PERIOD_MINUTES = 15L
    }
}
