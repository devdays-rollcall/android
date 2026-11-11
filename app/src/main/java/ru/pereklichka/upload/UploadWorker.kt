package ru.pereklichka.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import ru.pereklichka.data.AppSettings
import ru.pereklichka.data.GroupRepository
import ru.pereklichka.data.db.LocationDao
import ru.pereklichka.data.db.toModel

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val locationDao: LocationDao,
    private val groupRepository: GroupRepository,
    private val api: EventsApi,
    private val settings: AppSettings,
    private val uploadStatus: UploadStatus,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val group = groupRepository.activeGroup() ?: return Result.success()
        val serverUrl = settings.serverUrl.value
        if (serverUrl.isBlank()) return Result.success()

        var limit = BATCH_SIZE
        while (groupRepository.activeGroup() == group) {
            val points = locationDao.unsentByAuthor(group.me.value, limit)
            if (points.isEmpty()) return Result.success()

            val invalid = points.filterNot { it.toModel().hasValidValues() }
            if (invalid.isNotEmpty()) {
                locationDao.markRejected(invalid.map { it.id })
                continue
            }

            val response = try {
                api.postEvents(
                    EventsApi.eventsUrl(serverUrl),
                    EventsApi.bearer(group.token),
                    EventBatchDto(group.me.value, points.map { it.toModel().toEventInput() }),
                )
            } catch (exception: IOException) {
                return Result.retry()
            } catch (exception: IllegalArgumentException) {
                uploadStatus.reportError("Некорректный адрес сервера")
                return Result.failure()
            }

            when (val code = response.code()) {
                202 -> {
                    if (response.body()?.accepted != points.size) return Result.retry()
                    locationDao.markUploaded(points.map { it.id })
                    uploadStatus.clearError()
                }
                400, 413, 422 -> {
                    if (points.size > 1) {
                        limit = points.size / 2
                    } else if (code == 400) {
                        uploadStatus.reportError("Сервер отклонил запрос (400)")
                        return Result.failure()
                    } else {
                        locationDao.markRejected(listOf(points.single().id))
                    }
                }
                429, in 500..599 -> return Result.retry()
                else -> {
                    uploadStatus.reportError("Сервер отклонил запрос ($code)")
                    return Result.failure()
                }
            }
        }
        return Result.success()
    }

    private companion object {
        const val BATCH_SIZE = 200
    }
}
