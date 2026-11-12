package ru.pereklichka.upload

import android.content.Context
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
import ru.pereklichka.data.db.OutboxDao
import ru.pereklichka.data.db.toModel
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.LocationPoint
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.domain.time.Clock
import ru.pereklichka.status.GroupViewProvider

/**
 * Отправляет на сервер неотправленные точки всей группы пачками по [BATCH_SIZE] от имени этого телефона. Сначала
 * уходят точки активной группы, потом очередь стёртых групп (`outbox_point`): у каждой стёртой группы свои токен
 * и отправитель, сохранённые при стирании. Точку из очереди, которую сервер принял или которую нельзя отправить,
 * worker удаляет, а группу без точек убирает из очереди.
 *
 * Сбой сети, ответ 429 и 5xx, неожиданный код 2xx или 3xx и ответ, который не разобрать (портал Wi-Fi без
 * интернета отвечает 200 своей страницей), дают `Result.retry()`. На 413 пачка уменьшается вдвое до конца запуска.
 * На 400 и 422 пачка делится пополам, пока не найдётся виновная точка; её помечают `rejected`, и дальше снова
 * идут полные пачки. Если же 400 пришёл на одну точку, плох сам запрос. Его, как и 401 с прочими 4xx, повторять
 * бесполезно: запуск завершается ошибкой, а текст ошибки уходит в [UploadStatus] для экранов настроек.
 *
 * Если отправлять нечего, а маршрут идёт, запуск шлёт пустую пачку как heartbeat, но не чаще раза в
 * [HEARTBEAT_INTERVAL_MS] после последнего ответа 202. Время этого ответа хранит [UploadStatus], оно
 * переживает перезапуск процесса. Один запуск длится не дольше [RUN_LIMIT_MS]: WorkManager останавливает задачу
 * через 10 минут, поэтому оставшиеся точки уходят в следующем запуске через `Result.retry()`.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val locationDao: LocationDao,
    private val outboxDao: OutboxDao,
    private val groupRepository: GroupRepository,
    private val api: EventsApi,
    private val settings: AppSettings,
    private val uploadStatus: UploadStatus,
    private val viewProvider: GroupViewProvider,
    private val clock: Clock,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val serverUrl = settings.serverUrl.value
        if (serverUrl.isBlank()) return Result.success()
        val url = EventsApi.eventsUrl(serverUrl)
        if (url.toHttpUrlOrNull() == null) {
            uploadStatus.reportError("Некорректный адрес сервера")
            return Result.failure()
        }
        val startedAtMs = clock.elapsedMs()

        val group = groupRepository.activeGroup()
        if (group != null) {
            // Точки всей группы, свои и полученные от соседей: сервер записывает автора каждой точки отдельно от
            // отправителя и отсеивает повторы по id, поэтому одни и те же точки могут прийти от нескольких телефонов.
            val active = object : PointQueue {
                override suspend fun next(limit: Int) = locationDao.unsent(limit).map { it.toModel() }
                override suspend fun accept(ids: List<String>) = locationDao.markUploaded(ids)
                override suspend fun reject(ids: List<String>) = locationDao.markRejected(ids)
                override suspend fun isCurrent() = groupRepository.activeGroup() == group
            }
            send(url, group.token, group.me.value, active, startedAtMs)?.let { return it }
            if (groupRepository.activeGroup() == group) sendHeartbeatIfDue(url, group)?.let { return it }
        }
        for (old in outboxDao.groups()) {
            val queued = object : PointQueue {
                override suspend fun next(limit: Int) = outboxDao.points(old.tag, limit).map { it.toModel() }
                override suspend fun accept(ids: List<String>) = outboxDao.deletePoints(ids)
                override suspend fun reject(ids: List<String>) = outboxDao.deletePoints(ids)
                override suspend fun isCurrent() = true
            }
            send(url, old.token, old.senderId, queued, startedAtMs)?.let { return it }
            outboxDao.deleteGroupIfEmpty(old.tag)
        }
        return Result.success()
    }

    /** Неотправленные точки одной группы и что с ними делать после ответа сервера. */
    private interface PointQueue {
        /** Следующие не больше [limit] точек по порядку (автор, номер); пусто, когда отправлять нечего. */
        suspend fun next(limit: Int): List<LocationPoint>
        suspend fun accept(ids: List<String>)
        suspend fun reject(ids: List<String>)

        /** false, если группу сменили посреди запуска и её точки отправлять больше не нужно. */
        suspend fun isCurrent(): Boolean
    }

    /**
     * Отправляет точки [queue] с токеном [token] от имени [senderId]. null, когда точки кончились или группа
     * перестала быть текущей; иначе результат, с которым запуск надо закончить.
     */
    private suspend fun send(url: String, token: String, senderId: String, queue: PointQueue, startedAtMs: Long): Result? {
        // batchSize уменьшает только 413, searchSize задан, пока после 400 или 422 ищется виновная точка.
        var batchSize = BATCH_SIZE
        var searchSize: Int? = null
        while (queue.isCurrent()) {
            val points = queue.next(minOf(batchSize, searchSize ?: batchSize))
            if (points.isEmpty()) return null
            if (clock.elapsedMs() - startedAtMs >= RUN_LIMIT_MS) return Result.retry()

            val invalid = points.filterNot { it.isUploadable() }
            if (invalid.isNotEmpty()) {
                queue.reject(invalid.map { it.id.value })
                continue
            }

            val ids = points.map { it.id.value }
            val response = post(url, token, EventBatchDto(senderId, points.map { it.toEventInput() })) ?: return Result.retry()
            when (val code = response.code()) {
                202 -> {
                    if (response.body()?.accepted != points.size) return Result.retry()
                    queue.accept(ids)
                    onAccepted()
                }
                413 -> if (points.size > 1) batchSize = points.size / 2 else queue.reject(ids)
                400, 422 -> when {
                    points.size > 1 -> searchSize = points.size / 2
                    code == 400 -> return rejected(code)
                    else -> {
                        queue.reject(ids)
                        searchSize = null
                    }
                }
                else -> return otherResponse(code)
            }
        }
        return null
    }

    /** null, если heartbeat ушёл или не нужен; иначе результат, с которым запуск надо закончить. */
    private suspend fun sendHeartbeatIfDue(url: String, group: GroupInfo): Result? {
        val sinceAcceptedMs = clock.wallMs() - (uploadStatus.lastAcceptedWallMs.value ?: 0L)
        // Отрицательный промежуток значит, что часы перевели назад, и тогда heartbeat уходит.
        if (sinceAcceptedMs in 0 until HEARTBEAT_INTERVAL_MS) return null
        if (viewProvider.awaitRoute() != RouteState.ACTIVE) return null

        val response = post(url, group.token, EventBatchDto(group.me.value, emptyList())) ?: return Result.retry()
        if (response.code() != 202) return otherResponse(response.code())
        onAccepted()
        return null
    }

    /** Ответ сервера или null, если запрос не дошёл или ответ не удалось разобрать и запуск надо повторить. */
    private suspend fun post(url: String, token: String, batch: EventBatchDto): Response<AcceptedDto>? =
        try {
            api.postEvents(url, EventsApi.bearer(token), batch)
        } catch (exception: IOException) {
            null
        } catch (exception: SerializationException) {
            null
        }

    private fun onAccepted() {
        uploadStatus.reportAccepted(clock.wallMs())
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
    }
}
