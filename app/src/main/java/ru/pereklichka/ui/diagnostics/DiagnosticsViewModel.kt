package ru.pereklichka.ui.diagnostics

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import ru.pereklichka.data.AppSettings
import ru.pereklichka.data.GroupRepository
import ru.pereklichka.data.db.AppDatabase
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.presence.PresenceTracker
import ru.pereklichka.domain.sync.PeerTransport
import ru.pereklichka.domain.sync.SyncDiagnostics
import ru.pereklichka.domain.sync.SyncEngine
import ru.pereklichka.notify.PhoneChecks
import ru.pereklichka.service.PowerTools
import ru.pereklichka.status.GroupViewProvider
import ru.pereklichka.transport.RadioCheck
import ru.pereklichka.upload.UploadScheduler
import ru.pereklichka.upload.UploadStatus
import javax.inject.Inject

/** Всё, что показывает экран диагностики. */
data class DiagnosticsState(
    val group: GroupInfo? = null,
    val system: SystemChecks = SystemChecks(),
    val counts: DatabaseCounts = DatabaseCounts(),
    val connectedPeers: Set<MemberId> = emptySet(),
    val sync: SyncDiagnostics = SyncDiagnostics(),
    /** Кого телефон слышал напрямую и сколько миллисекунд назад, по `PresenceTracker`. */
    val heardAgo: Map<MemberId, Long> = emptyMap(),
    /** Состав группы для списка блокировки. */
    val members: List<MemberId> = emptyList(),
    /** Заблокированные участники: им не подключаемся и их сообщения отбрасываем. */
    val blocked: Set<MemberId> = emptySet(),
)

/** Системные флаги для раздела «Телефон». ViewModel перечитывает их раз в 2 секунды, это проще, чем подписываться на рассылку о каждом. */
data class SystemChecks(
    val bluetoothEnabled: Boolean = false,
    val playServicesAvailable: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false,
    val notificationsEnabled: Boolean = false,
    /** «Не беспокоить» в режиме полной тишины: тревога не прозвучит, даже как будильник. */
    val totalSilence: Boolean = false,
)

/** Сколько записей в базе: события, точки (всего, к отправке, отклонённые) и состояния. */
data class DatabaseCounts(
    val events: Int = 0,
    val points: Int = 0,
    val unsentPoints: Int = 0,
    val rejectedPoints: Int = 0,
    val states: Int = 0,
)

/**
 * Данные для [DiagnosticsScreen]: собирает группу, системные проверки, счётчики базы, соседей и счётчики
 * `SyncEngine` в одно [DiagnosticsState] и передаёт настройки из экрана в `AppSettings`.
 *
 * Задача: docs/team/olga-transport.md
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    groupRepository: GroupRepository,
    db: AppDatabase,
    transport: PeerTransport,
    engine: SyncEngine,
    private val radioCheck: RadioCheck,
    val powerTools: PowerTools,
    private val settings: AppSettings,
    private val uploadScheduler: UploadScheduler,
    uploadStatus: UploadStatus,
    private val presence: PresenceTracker,
    viewProvider: GroupViewProvider,
) : ViewModel() {

    private val systemChecks = flow {
        while (true) {
            emit(readSystemChecks())
            delay(SYSTEM_REFRESH_MS)
        }
    }

    // Сколько назад слышали каждого: считается по монотонным часам, обновляем с тем же тиком, что и системные проверки.
    private val heardAgo = flow {
        while (true) {
            emit(presence.heardAgo())
            delay(SYSTEM_REFRESH_MS)
        }
    }

    private val counts = combine(
        db.controlEventDao().observeCount(),
        db.locationDao().observeCount(),
        db.locationDao().observeUnsentCount(),
        db.locationDao().observeRejectedCount(),
        db.memberStateDao().observeCount(),
    ) { events, points, unsent, rejected, states -> DatabaseCounts(events, points, unsent, rejected, states) }

    val state: StateFlow<DiagnosticsState> = combine(
        combine(groupRepository.activeGroup, systemChecks, counts, ::Triple),
        combine(transport.connectedPeers, engine.diagnostics, heardAgo, ::Triple),
        combine(settings.blockedMembers, viewProvider.view, ::Pair),
    ) { (group, system, dbCounts), (peers, sync, heard), (blocked, view) ->
        val roster = (view?.members?.map { it.id }.orEmpty() + heard.keys + blocked)
            .distinct().sortedBy { it.value }
        DiagnosticsState(group, system, dbCounts, peers, sync, heard, roster, blocked)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DiagnosticsState())

    val serverUrl: StateFlow<String> = settings.serverUrl
    val demoMode: StateFlow<Boolean> = settings.demoMode
    val uploadError: StateFlow<String?> = uploadStatus.lastError

    fun setServerUrl(url: String) = settings.setServerUrl(url)

    fun setDemoMode(enabled: Boolean) = settings.setDemoMode(enabled)

    fun uploadNow() = uploadScheduler.uploadNow()

    /** Переключатель блокировки участника: заблокированного транспорт отключает и отбрасывает его сообщения. */
    fun setBlocked(member: MemberId, blocked: Boolean) = settings.setBlocked(member, blocked)

    private fun readSystemChecks() = SystemChecks(
        bluetoothEnabled = radioCheck.isBluetoothEnabled(),
        playServicesAvailable = radioCheck.isPlayServicesAvailable(),
        ignoringBatteryOptimizations = powerTools.isIgnoringBatteryOptimizations(),
        notificationsEnabled = PhoneChecks.notificationsEnabled(context),
        totalSilence = PhoneChecks.totalSilence(context),
    )

    private companion object {
        const val SYSTEM_REFRESH_MS = 2_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
