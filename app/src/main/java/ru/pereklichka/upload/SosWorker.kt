package ru.pereklichka.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.SerializationException
import ru.pereklichka.data.GroupRepository
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.status.GroupViewProvider
import java.io.IOException

/**
 * Отправляет на сервер (`POST /api/v1/alert`) SOS участника [MEMBER], а сервер передаёт его наблюдателям через ntfy.
 * Задачу ставит [UploadScheduler.sendSos] на каждом телефоне, где виден этот SOS: на сервер он попадёт с любого
 * телефона, который первым поймает интернет. Снимает задачу [UploadScheduler.cancelSos], когда SOS сняли.
 * Если активная группа сменилась или SOS к моменту отправки уже сняли, задача завершается без отправки. Если вид
 * группы не посчитался, SOS уходит: лучше отправить его лишний раз, чем не отправить.
 *
 * Сервер, который ещё не видел группу, отвечает 404. Тогда задача шлёт от имени этого телефона ([SENDER]) пустую
 * пачку событий, чтобы группа появилась, и повторяется. Сбой сети, 429, 5xx и ответ, который не удалось разобрать
 * (портал Wi-Fi без интернета), тоже дают повтор.
 */
@HiltWorker
class SosWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val api: EventsApi,
    private val groupRepository: GroupRepository,
    private val viewProvider: GroupViewProvider,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val token = inputData.getString(TOKEN) ?: return Result.failure()
        val member = inputData.getString(MEMBER) ?: return Result.failure()
        val sender = inputData.getString(SENDER) ?: return Result.failure()
        val alarm = inputData.getString(ALARM) ?: return Result.failure()
        val url = inputData.getString(URL)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        if (groupRepository.activeGroup()?.token != token) return Result.success()
        if (viewProvider.awaitAlarmActive(EventId(alarm)) == false) return Result.success()
        val authorization = EventsApi.bearer(token)

        return try {
            val response = api.raiseAlert(EventsApi.alertUrl(url), authorization, "sos", member)
            when (response.code()) {
                200 -> if (response.body()?.notified == true) Result.success() else Result.retry()
                404 -> {
                    val heartbeat = api.postEvents(EventsApi.eventsUrl(url), authorization, EventBatchDto(sender, emptyList()))
                    if (heartbeat.code() == 202 || heartbeat.code() == 429 || heartbeat.code() >= 500) Result.retry()
                    else Result.failure()
                }
                429, in 500..599 -> Result.retry()
                else -> Result.failure()
            }
        } catch (exception: IOException) {
            Result.retry()
        } catch (exception: SerializationException) {
            Result.retry()
        } catch (exception: IllegalArgumentException) {
            Result.failure()
        }
    }

    companion object {
        const val TOKEN = "token"
        /** Участник, чей это SOS. */
        const val MEMBER = "member"

        /** `member_id` этого телефона в группе: от его имени уходит пустая пачка для неизвестной серверу группы. */
        const val SENDER = "sender"

        /** id события SOS. */
        const val ALARM = "alarm"
        const val URL = "url"
    }
}
