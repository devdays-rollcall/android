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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ставит [UploadWorker] в очередь WorkManager. Отправка не зависит от службы: WorkManager сам дождётся сети
 * и повторит задачу после сбоя с растущей паузой от 10 секунд.
 */
@Singleton
class UploadScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    private val needsNetwork = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Отправить сейчас. Вызывают служба при появлении сети с интернетом и после завершения маршрута,
     * а также кнопка на экране диагностики. Задача, которая ждёт повтора, заменяется новой: раз появилась сеть,
     * ждать паузу незачем. Прерванную пачку сервер примет повторно без дублей.
     */
    fun uploadNow() {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(needsNetwork)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UPLOAD_NOW, ExistingWorkPolicy.REPLACE, request)
    }

    /** Страховка на случай, если сигнал о сети не пришёл: раз в 15 минут, это минимум WorkManager. */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<UploadWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(needsNetwork)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UPLOAD_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val UPLOAD_NOW = "upload-now"
        const val UPLOAD_PERIODIC = "upload-periodic"
        const val BACKOFF_SECONDS = 10L
        const val PERIOD_MINUTES = 15L
    }
}
