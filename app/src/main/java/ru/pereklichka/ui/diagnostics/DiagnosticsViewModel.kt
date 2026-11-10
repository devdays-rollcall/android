package ru.pereklichka.ui.diagnostics

import android.content.Context
import androidx.core.app.NotificationManagerCompat
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
import ru.pereklichka.domain.sync.PeerTransport
import ru.pereklichka.domain.sync.SyncDiagnostics
import ru.pereklichka.domain.sync.SyncEngine
import ru.pereklichka.service.PowerTools
import ru.pereklichka.transport.RadioCheck
import ru.pereklichka.upload.UploadScheduler
import javax.inject.Inject

/** Всё, что показывает экран диагностики. */
data class DiagnosticsState(
    val group: GroupInfo? = null,
    val system: SystemChecks = SystemChecks(),
    val counts: DatabaseCounts = DatabaseCounts(),
    val connectedPeers: Set<MemberId> = emptySet(),
    val sync: SyncDiagnostics = SyncDiagnostics(),
)

/** Состояния телефона, о смене которых система не сообщает: экран перечитывает их раз в 2 секунды. */
data class SystemChecks(
    val bluetoothEnabled: Boolean = false,
    val playServicesAvailable: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false,
    val notificationsEnabled: Boolean = false,
)

data class DatabaseCounts(
    val events: Int = 0,
    val points: Int = 0,
    val unsentPoints: Int = 0,
    val rejectedPoints: Int = 0,
    val states: Int = 0,
)

/**
 * Данные экрана диагностики. Ольга добавит сюда соседей с временем последнего сообщения и список блокировки.
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
) : ViewModel() {

    private val systemChecks = flow {
        while (true) {
            emit(readSystemChecks())
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
        groupRepository.activeGroup,
        systemChecks,
        counts,
        transport.connectedPeers,
        engine.diagnostics,
    ) { group, system, dbCounts, peers, sync -> DiagnosticsState(group, system, dbCounts, peers, sync) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DiagnosticsState())

    val serverUrl: StateFlow<String> = settings.serverUrl
    val demoMode: StateFlow<Boolean> = settings.demoMode

    fun setServerUrl(url: String) = settings.setServerUrl(url)

    fun setDemoMode(enabled: Boolean) = settings.setDemoMode(enabled)

    fun uploadNow() = uploadScheduler.uploadNow()

    private fun readSystemChecks() = SystemChecks(
        bluetoothEnabled = radioCheck.isBluetoothEnabled(),
        playServicesAvailable = radioCheck.isPlayServicesAvailable(),
        ignoringBatteryOptimizations = powerTools.isIgnoringBatteryOptimizations(),
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    )

    private companion object {
        const val SYSTEM_REFRESH_MS = 2_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
