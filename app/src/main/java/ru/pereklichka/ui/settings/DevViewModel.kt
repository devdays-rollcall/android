package ru.pereklichka.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.pereklichka.data.AppSettings
import ru.pereklichka.data.GroupRepository
import ru.pereklichka.data.db.AppDatabase
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.presence.PresenceTracker
import ru.pereklichka.domain.sync.PeerTransport
import ru.pereklichka.domain.sync.SyncDiagnostics
import ru.pereklichka.domain.sync.SyncEngine
import ru.pereklichka.status.GroupViewProvider
import ru.pereklichka.upload.UploadScheduler
import ru.pereklichka.upload.UploadStatus
import javax.inject.Inject

/** Всё, что показывает [DevScreen]. */
data class DevState(
    val group: GroupInfo? = null,
    val counts: DatabaseCounts = DatabaseCounts(),
    /** Соседи, с которыми сейчас открыто соединение Nearby. */
    val connectedPeers: Set<MemberId> = emptySet(),
    val sync: SyncDiagnostics = SyncDiagnostics(),
    /** Кого телефон слышал напрямую за последние 10 минут и сколько миллисекунд назад, по `PresenceTracker`. */
    val heardAgo: Map<MemberId, Long> = emptyMap(),
    /** Участники для списка блокировки, кроме меня, по имени. */
    val members: List<MemberId> = emptyList(),
    /** О ком в группе есть хоть какой-то контакт, пусть и через других. */
    val groupHeard: Set<MemberId> = emptySet(),
    /** Заблокированные участники: им не подключаемся и их сообщения отбрасываем. */
    val blocked: Set<MemberId> = emptySet(),
    /** Имена участников из состояния группы; у тех, чей JOINED ещё не дошёл, имени нет. */
    val memberNames: Map<MemberId, String> = emptyMap(),
    val serverUrl: String = "",
    /** Настенное время последнего ответа сервера 202; null, если сервер ещё не принимал. */
    val lastAcceptedWallMs: Long? = null,
    val uploadError: String? = null,
)

/**
 * Сколько записей в базе: события, точки (всего, ждут отправки, отклонены), состояния участников и точки стёртых
 * групп, которые ещё ждут отправки в очереди.
 */
data class DatabaseCounts(
    val events: Int = 0,
    val points: Int = 0,
    val unsentPoints: Int = 0,
    val rejectedPoints: Int = 0,
    val states: Int = 0,
    val queuedPoints: Int = 0,
)

/**
 * Данные для [DevScreen]: группа, счётчики базы и `SyncEngine`, соседи и итог отправки. Передаёт адрес сервера
 * и блокировку в `AppSettings`, «Отправить» в [UploadScheduler.restartUpload].
 */
@HiltViewModel
class DevViewModel @Inject constructor(
    groupRepository: GroupRepository,
    db: AppDatabase,
    transport: PeerTransport,
    engine: SyncEngine,
    private val settings: AppSettings,
    private val uploadScheduler: UploadScheduler,
    uploadStatus: UploadStatus,
    private val presence: PresenceTracker,
    viewProvider: GroupViewProvider,
) : ViewModel() {

    // Сколько назад слышали каждого: считается по монотонным часам, поэтому перечитывается по таймеру.
    private val heardAgo = flow {
        while (true) {
            emit(presence.heardAgo())
            delay(REFRESH_MS)
        }
    }

    private val counts = combine(
        db.controlEventDao().observeCount(),
        db.locationDao().observeCount(),
        db.locationDao().observeUnsentCount(),
        db.locationDao().observeRejectedCount(),
        db.memberStateDao().observeCount(),
        db.outboxDao().observeCount(),
    ) { values -> DatabaseCounts(values[0], values[1], values[2], values[3], values[4], values[5]) }

    private val upload = combine(settings.serverUrl, uploadStatus.lastAcceptedWallMs, uploadStatus.lastError, ::Triple)

    val state: StateFlow<DevState> = combine(
        combine(groupRepository.activeGroup, counts, upload, ::Triple),
        combine(transport.connectedPeers, engine.diagnostics, heardAgo, ::Triple),
        combine(settings.blockedMembers, viewProvider.view, ::Pair),
    ) { (group, dbCounts, uploadInfo), (peers, sync, heard), (blocked, view) ->
        val (serverUrl, acceptedAt, error) = uploadInfo
        val names = view?.members?.associate { it.id to it.name }.orEmpty()
        // Себя в список блокировки не выводим: GroupView.members включает свой телефон.
        val roster = (view?.members?.map { it.id }.orEmpty() + heard.keys + blocked)
            .distinct()
            .filter { it != group?.me }
            .sortedBy { names[it] ?: it.value }
        DevState(
            group = group,
            counts = dbCounts,
            connectedPeers = peers,
            sync = sync,
            heardAgo = heard,
            members = roster,
            groupHeard = view?.members?.filter { it.lastContact != null }?.map { it.id }.orEmpty().toSet(),
            blocked = blocked,
            memberNames = names,
            serverUrl = serverUrl,
            lastAcceptedWallMs = acceptedAt,
            uploadError = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DevState())

    fun setServerUrl(url: String) = settings.setServerUrl(url)

    fun uploadNow() {
        viewModelScope.launch { uploadScheduler.restartUpload() }
    }

    /** Переключатель блокировки участника: заблокированного транспорт отключает и отбрасывает его сообщения. */
    fun setBlocked(member: MemberId, blocked: Boolean) = settings.setBlocked(member, blocked)

    private companion object {
        const val REFRESH_MS = 2_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
