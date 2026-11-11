package ru.pereklichka.upload

import android.content.Context
import androidx.core.content.edit
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Response
import java.io.IOException
import ru.pereklichka.data.AppSettings
import ru.pereklichka.data.GroupRepository
import ru.pereklichka.data.db.LocationDao
import ru.pereklichka.data.db.toModel
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.domain.time.Clock
import ru.pereklichka.status.GroupViewProvider

/**
 * Отправляет на сервер неотправленные точки всей группы пачками по [BATCH_SIZE] от имени этого телефона.
 *
 * Сбой сети, ответ 429 и 5xx, неожиданный код 2xx или 3xx и ответ, который не разобрать (портал Wi-Fi без
 * интернета отвечает 200 своей страницей), дают `Result.retry()`. На 413 пачка уменьшается вдвое до конца запуска.
 * На 400 и 422 пачка делится пополам, пока не найдётся виновная точка; её помечают `rejected`, и дальше снова
 * идут полные пачки. Если же 400 пришёл на одну точку, плох сам запрос. Его, как и 401 с прочими 4xx, повторять
 * бесполезно: запуск завершается ошибкой, а текст ошибки уходит в [UploadStatus] для диагностики.
 *
 * Если отправлять нечего, а маршрут идёт, запуск шлёт пустую пачку как heartbeat, но не чаще раза в
 * [HEARTBEAT_INTERVAL_MS] после последнего ответа 202. Время этого ответа хранится в SharedPreferences и
 * переживает перезапуск процесса. Один запуск длится не дольше [RUN_LIMIT_MS]: WorkManager останавливает задачу
 * через 10 минут, поэтому оставшиеся точки уходят в следующем запуске через `Result.retry()`.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val locationDao: LocationDao,
    private val groupRepository: GroupRepository,
    private val api: EventsApi,
    private val settings: AppSettings,
    private val uploadStatus: UploadStatus,
    private val viewProvider: GroupViewProvider,
    private val clock: Clock,
) : CoroutineWorker(context, params) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override suspend fun doWork(): Result {
        val group = groupRepository.activeGroup() ?: return Result.success()
        val serverUrl = settings.serverUrl.value
        if (serverUrl.isBlank()) return Result.success()
        val url = EventsApi.eventsUrl(serverUrl)
        if (url.toHttpUrlOrNull() == null) {
            uploadStatus.reportError("Некорректный адрес сервера")
            return Result.failure()
        }
        val startedAtMs = clock.elapsedMs()

        // batchSize уменьшает только 413, searchSize задан, пока после 400 или 422 ищется виновная точка.
        var batchSize = BATCH_SIZE
        var searchSize: Int? = null
        while (groupRepository.activeGroup() == group) {
            // Точки всей группы, свои и полученные от соседей: сервер записывает автора каждой точки отдельно от
            // отправителя и отсеивает повторы по id, поэтому одни и те же точки могут прийти от нескольких телефонов.
            val points = locationDao.unsent(minOf(batchSize, searchSize ?: batchSize))
            if (points.isEmpty()) return sendHeartbeatIfDue(url, group)
            if (clock.elapsedMs() - startedAtMs >= RUN_LIMIT_MS) return Result.retry()

            val invalid = points.filterNot { it.toModel().hasValidValues() }
            if (invalid.isNotEmpty()) {
                locationDao.markRejected(invalid.map { it.id })
                continue
            }

            val response = post(url, group, EventBatchDto(group.me.value, points.map { it.toModel().toEventInput() }))
                ?: return Result.retry()
            when (val code = response.code()) {
                202 -> {
                    if (response.body()?.accepted != points.size) return Result.retry()
                    locationDao.markUploaded(points.map { it.id })
                    onAccepted()
                }
                413 -> if (points.size > 1) batchSize = points.size / 2 else locationDao.markRejected(listOf(points.single().id))
                400, 422 -> when {
                    points.size > 1 -> searchSize = points.size / 2
                    code == 400 -> return rejected(code)
                    else -> {
                        locationDao.markRejected(listOf(points.single().id))
                        searchSize = null
                    }
                }
                else -> return otherResponse(code)
            }
        }
        return Result.success()
    }

    private suspend fun sendHeartbeatIfDue(url: String, group: GroupInfo): Result {
        val sinceAcceptedMs = clock.wallMs() - prefs.getLong(KEY_LAST_ACCEPTED_WALL_MS, 0L)
        // Отрицательный промежуток значит, что часы перевели назад, и тогда heartbeat уходит.
        if (sinceAcceptedMs in 0 until HEARTBEAT_INTERVAL_MS) return Result.success()
        if (viewProvider.awaitRoute() != RouteState.ACTIVE) return Result.success()

        val response = post(url, group, EventBatchDto(group.me.value, emptyList())) ?: return Result.retry()
        if (response.code() != 202) return otherResponse(response.code())
        onAccepted()
        return Result.success()
    }

    /** Ответ сервера или null, если запрос не дошёл или ответ не удалось разобрать и запуск надо повторить. */
    private suspend fun post(url: String, group: GroupInfo, batch: EventBatchDto): Response<AcceptedDto>? =
        try {
            api.postEvents(url, EventsApi.bearer(group.token), batch)
        } catch (exception: IOException) {
            null
        } catch (exception: SerializationException) {
            null
        }

    private fun onAccepted() {
        prefs.edit { putLong(KEY_LAST_ACCEPTED_WALL_MS, clock.wallMs()) }
        uploadStatus.clearError()
    }

    /** Коды, на которые нет особого правила: 429, 5xx, 2xx и 3xx кроме 202 повторяются, остальное ошибка. */
    private fun otherResponse(code: Int): Result = when (code) {
        429, in 500..599, in 200..399 -> Result.retry()
        else -> rejected(code)
    }

    private fun rejected(code: Int): Result {
        uploadStatus.reportError("Сервер отклонил запрос ($code)")
        return Result.failure()
    }

    private companion object {
        const val BATCH_SIZE = 200
        const val RUN_LIMIT_MS = 2 * 60_000L
        const val HEARTBEAT_INTERVAL_MS = 60_000L
        const val PREFS = "upload"
        const val KEY_LAST_ACCEPTED_WALL_MS = "last_accepted_wall_ms"
    }
}
