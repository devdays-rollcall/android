package ru.pereklichka.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import ru.pereklichka.data.AppSettings
import ru.pereklichka.data.GroupRepository
import ru.pereklichka.data.db.LocationDao
import ru.pereklichka.status.GroupViewProvider

/**
 * Отправляет на сервер точки всей группы, свои и полученные по p2p. Ставит его [UploadScheduler].
 * Одни и те же точки отправят несколько телефонов, сервер отсеет повторы по `id`.
 *
 * Состояние маршрута для heartbeat даёт [viewProvider] (`view.filterNotNull().first().route`), ошибку для
 * диагностики принимает [uploadStatus].
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val locationDao: LocationDao,
    private val groupRepository: GroupRepository,
    private val api: EventsApi,
    private val settings: AppSettings,
    private val viewProvider: GroupViewProvider,
    private val uploadStatus: UploadStatus,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // TODO(Даниил): пачки до 200 из locationDao.unsent(), проверка hasValidValues, разбор кодов ответа, heartbeat.
        //  202 помечает ровно эту пачку, 5xx и нет сети дают retry, 413 уменьшает пачку, 400 и 422 ищут кривую точку,
        //  401 даёт failure и ошибку в uploadStatus. docs/team/daniil-upload.md, тесты в UploadWorkerTest
        throw NotImplementedError("TODO(Даниил): пачки до 200 из locationDao.unsent(), проверка hasValidValues, разбор кодов ответа, heartbeat.")
    }
}
