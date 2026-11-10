package ru.pereklichka.service

import kotlinx.coroutines.delay
import ru.pereklichka.domain.presence.PresenceTracker
import ru.pereklichka.domain.sync.MemberStateDraft
import ru.pereklichka.domain.sync.MeshStore
import ru.pereklichka.domain.sync.SyncConfig
import javax.inject.Inject

/**
 * Пишет своё состояние раз в [SyncConfig.stateIntervalMs]: последнюю фиксацию, заряд и кого телефон слышит.
 * `SyncEngine` сразу рассылает новое состояние соседям. Запускает его [RouteService] на время работы службы.
 */
class SelfStateProducer @Inject constructor(
    private val store: MeshStore,
    private val presence: PresenceTracker,
    private val ownLocation: OwnLocation,
    private val battery: BatteryLevel,
    private val config: SyncConfig,
) {
    /** Работает, пока не отменят корутину. Первое состояние пишет сразу. */
    suspend fun run() {
        while (true) {
            store.putOwnState(MemberStateDraft(ownLocation.lastFix.value, battery.percent(), presence.heardAgo()))
            delay(config.stateIntervalMs)
        }
    }
}
