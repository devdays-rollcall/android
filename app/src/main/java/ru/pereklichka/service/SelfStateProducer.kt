package ru.pereklichka.service

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.presence.PresenceTracker
import ru.pereklichka.domain.sync.MemberStateDraft
import ru.pereklichka.domain.sync.MeshStore
import ru.pereklichka.domain.sync.PeerTransport
import ru.pereklichka.domain.sync.StoreChange
import ru.pereklichka.domain.sync.SyncConfig
import javax.inject.Inject

/**
 * Пишет своё состояние: последнюю фиксацию, заряд и кого телефон слышит. `SyncEngine` сразу рассылает новое
 * состояние соседям. Запускает его [RouteService] на время работы службы.
 *
 * Состояние пишется раз в [SyncConfig.stateIntervalMs] и ещё сразу после двух поводов. Первый повод это моё новое
 * событие: перекличка или тревога приходит к соседям вместе со свежей позицией автора, а не на 15 секунд раньше
 * неё. Второй повод это новый сосед: он получает мою позицию на момент встречи. Поводы, пришедшие чаще чем раз
 * в [MIN_GAP_MS], сливаются в одно состояние.
 */
class SelfStateProducer @Inject constructor(
    private val store: MeshStore,
    private val transport: PeerTransport,
    private val presence: PresenceTracker,
    private val ownLocation: OwnLocation,
    private val battery: BatteryLevel,
    private val config: SyncConfig,
) {
    /** Пишет первое состояние сразу и дальше по таймеру и поводам, пока корутину не отменят. */
    suspend fun run() = coroutineScope {
        val triggers = Channel<Unit>(Channel.CONFLATED)
        launch {
            store.changes.filter { it == StoreChange.OWN_EVENT }.collect { triggers.trySend(Unit) }
        }
        launch {
            var known = emptySet<MemberId>()
            transport.connectedPeers.collect { peers ->
                if ((peers - known).isNotEmpty()) triggers.trySend(Unit)
                known = peers
            }
        }
        while (true) {
            store.putOwnState(MemberStateDraft(ownLocation.lastFix.value, battery.percent(), presence.heardAgo()))
            delay(MIN_GAP_MS)
            withTimeoutOrNull(config.stateIntervalMs - MIN_GAP_MS) { triggers.receive() }
        }
    }

    private companion object {
        /** Не чаще одного состояния в секунду: перекличка и ответы на неё не должны давать по состоянию на событие. */
        const val MIN_GAP_MS = 1_000L
    }
}
