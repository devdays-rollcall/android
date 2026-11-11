package ru.pereklichka.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import ru.pereklichka.domain.model.GroupInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ставит [UploadWorker] в очередь WorkManager. Вызывают его `RouteService` и диагностика.
 *
 * Отправка не зависит от службы: WorkManager сам дождётся сети и повторит разовую задачу после сбоя с растущей
 * паузой от 10 секунд, а периодическая просто сработает в свой срок.
 */
@Singleton
class UploadScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    private val needsNetwork = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Отправить сейчас. Вызывают служба при появлении сети с интернетом и после завершения маршрута,
     * а также кнопка на экране диагностики.
     */
    fun uploadNow() {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(needsNetwork)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UPLOAD_NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun sendSos(group: GroupInfo, serverUrl: String) {
        val request = OneTimeWorkRequestBuilder<SosWorker>()
            .setInputData(workDataOf(SosWorker.TOKEN to group.token, SosWorker.MEMBER to group.me.value, SosWorker.URL to serverUrl))
            .setConstraints(needsNetwork)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(sosName(group.tag), ExistingWorkPolicy.KEEP, request)
    }

    /** Страховка на случай, если сигнал о сети не пришёл: раз в 15 минут, это минимум WorkManager. */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<UploadWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(needsNetwork)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UPLOAD_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        fun sosName(groupTag: String) = "sos-$groupTag"

        const val UPLOAD_NOW = "upload-now"
        const val UPLOAD_PERIODIC = "upload-periodic"
        const val BACKOFF_SECONDS = 10L
        const val PERIOD_MINUTES = 15L
    }
}
