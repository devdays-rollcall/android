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
import ru.pereklichka.data.db.ControlEventDao
import ru.pereklichka.data.db.LocationDao
import ru.pereklichka.data.db.key
import ru.pereklichka.data.db.toModel
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.LocationPoint
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.domain.time.Clock
import ru.pereklichka.status.GroupViewProvider

/**
 * Отправляет на сервер неотправленные точки всей группы пачками по [BATCH_SIZE] от имени этого телефона. Сначала
 * уходят точки активной группы, потом прошлых маршрутов, старые первыми: у каждой группы свой токен, а отправитель
 * это мой `member_id` в ней. Принятую точку worker помечает `uploaded`, и в базе она остаётся. В каждое событие
 * кладётся имя автора точки из его JOINED, чтобы карта на сайте подписала участника.
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
 *
 * Отказ сервера по одному прошлому маршруту (например, 401 на старый токен) не держит остальные: их трек уходит
 * дальше, а запуск всё равно кончается ошибкой. Повтор после сбоя сети заканчивает запуск сразу. Heartbeat активной
 * группы уходит, только если после отправки её точек она всё ещё активна: человек мог начать новую группу.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val locationDao: LocationDao,
    private val controlEventDao: ControlEventDao,
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

        val active = groupRepository.activeGroup()
        if (active != null) {
            send(url, active, startedAtMs)?.let { return it }
            if (groupRepository.activeGroup() == active) sendHeartbeatIfDue(url, active)?.let { return it }
        }
        var failure: Result? = null
        for (past in groupRepository.pastGroupsWithUnsentTrack()) {
            when (val result = send(url, past, startedAtMs)) {
                null -> Unit
                Result.failure() -> failure = result
                else -> return result
            }
        }
        return failure ?: Result.success()
    }

    /**
     * Отправляет неотправленные точки группы [group] с её токеном от моего имени в ней. Точки всей группы, свои
     * и полученные от соседей: сервер записывает автора каждой точки отдельно от отправителя и отсеивает повторы
     * по id, поэтому одни и те же точки могут прийти от нескольких телефонов. null, когда точки кончились;
     * иначе результат, с которым запуск надо закончить.
     *
     * Имена авторов читаются один раз на группу, и JOINED, пришедший посреди запуска, попадёт в следующий. Размер
     * пачки уменьшает только 413, а уменьшенный размер поиска действует, пока после 400 или 422 ищется виновная точка.
     */
    private suspend fun send(url: String, group: GroupInfo, startedAtMs: Long): Result? {
        val names = controlEventDao.joinedNames(group.key).associate { it.author to it.name }
        var batchSize = BATCH_SIZE
        var searchSize: Int? = null
        while (true) {
            val points = locationDao.unsent(group.key, minOf(batchSize, searchSize ?: batchSize)).map { it.toModel() }
            if (points.isEmpty()) return null
            if (clock.elapsedMs() - startedAtMs >= RUN_LIMIT_MS) return Result.retry()

            val invalid = points.filterNot { it.isUploadable() }
            if (invalid.isNotEmpty()) {
                locationDao.markRejected(group.key, invalid.map { it.id.value })
                continue
            }

            val ids = points.map { it.id.value }
            val batch = EventBatchDto(group.me.value, points.map { it.toEventInput(names[it.author.value]) })
            val response = post(url, group.token, batch) ?: return Result.retry()
            when (val code = response.code()) {
                202 -> {
                    if (response.body()?.accepted != points.size) return Result.retry()
                    locationDao.markUploaded(group.key, ids)
                    onAccepted()
                }
                413 -> if (points.size > 1) batchSize = points.size / 2 else locationDao.markRejected(group.key, ids)
                400, 422 -> when {
                    points.size > 1 -> searchSize = points.size / 2
                    code == 400 -> return rejected(code)
                    else -> {
                        locationDao.markRejected(group.key, ids)
                        searchSize = null
                    }
                }
                else -> return otherResponse(code)
            }
        }
    }

    /**
     * null, если heartbeat ушёл или не нужен; иначе результат, с которым запуск надо закончить. Отрицательный
     * промежуток с последнего ответа 202 значит, что часы перевели назад, и тогда heartbeat уходит.
     */
    private suspend fun sendHeartbeatIfDue(url: String, group: GroupInfo): Result? {
        val sinceAcceptedMs = clock.wallMs() - (uploadStatus.lastAcceptedWallMs.value ?: 0L)
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
