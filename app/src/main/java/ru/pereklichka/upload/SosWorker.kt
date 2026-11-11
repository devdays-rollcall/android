package ru.pereklichka.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException

@HiltWorker
class SosWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val api: EventsApi,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val token = inputData.getString(TOKEN) ?: return Result.failure()
        val member = inputData.getString(MEMBER) ?: return Result.failure()
        val url = inputData.getString(URL)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val authorization = EventsApi.bearer(token)

        return try {
            val response = api.raiseAlert(EventsApi.alertUrl(url), authorization)
            when (response.code()) {
                200 -> if (response.body()?.notified == true) Result.success() else Result.retry()
                404 -> {
                    val heartbeat = api.postEvents(EventsApi.eventsUrl(url), authorization, EventBatchDto(member, emptyList()))
                    if (heartbeat.code() == 202 || heartbeat.code() == 429 || heartbeat.code() >= 500) Result.retry()
                    else Result.failure()
                }
                429, in 500..599 -> Result.retry()
                else -> Result.failure()
            }
        } catch (exception: IOException) {
            Result.retry()
        } catch (exception: IllegalArgumentException) {
            Result.failure()
        }
    }

    companion object {
        const val TOKEN = "token"
        const val MEMBER = "member"
        const val URL = "url"
    }
}
