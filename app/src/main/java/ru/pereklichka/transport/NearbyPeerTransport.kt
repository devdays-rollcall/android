package ru.pereklichka.transport

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import ru.pereklichka.di.ApplicationScope
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.sync.PeerTransport
import ru.pereklichka.domain.sync.TransportConfig
import ru.pereklichka.domain.sync.TransportEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PeerTransport] поверх Nearby Connections: `P2P_CLUSTER`, serviceId с тегом группы, `member_id` как имя
 * устройства, `NON_DISRUPTIVE`. Кого подключать и когда, решает [ConnectionPolicy], радио проверяет [RadioCheck].
 *
 * Обратные вызовы Nearby приходят в главный поток: их нужно сразу перекладывать в [eventsChannel] и ничего
 * тяжёлого в них не делать. Подтверждения доставки обрабатываются здесь же, а не в движке.
 */
@Singleton
class NearbyPeerTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val radioCheck: RadioCheck,
    /** Для отложенных запросов соединения, повторов и окон поиска: живёт дольше одного запуска транспорта. */
    @ApplicationScope private val scope: CoroutineScope,
) : PeerTransport {

    private val eventsChannel = Channel<TransportEvent>(Channel.UNLIMITED)
    override val events: Flow<TransportEvent> = eventsChannel.receiveAsFlow()

    private val peers = MutableStateFlow<Set<MemberId>>(emptySet())
    override val connectedPeers: StateFlow<Set<MemberId>> = peers.asStateFlow()

    override fun start(config: TransportConfig) {
        // TODO(Ольга): проверить радио и включить рекламу и поиск по политике из 03.
        //  Решения о соединениях через ConnectionPolicy, соединения принимать без кода; после перезапуска процесса
        //  поднимать всё с нуля. docs/team/olga-transport.md
        throw NotImplementedError("TODO(Ольга): проверить радио и включить рекламу и поиск по политике из 03.")
    }

    override fun stop() {
        // TODO(Ольга): остановить рекламу и поиск, разорвать все соединения, очистить соответствие endpointId и member_id.
        //  docs/team/olga-transport.md
        throw NotImplementedError("TODO(Ольга): остановить рекламу и поиск, разорвать все соединения, очистить соответствие endpointId и member_id.")
    }

    override suspend fun send(peer: MemberId, bytes: ByteArray): Boolean {
        // TODO(Ольга): sendPayload(Payload.fromBytes) и ждать SUCCESS или FAILURE в onPayloadTransferUpdate с таймаутом.
        //  docs/team/olga-transport.md
        throw NotImplementedError("TODO(Ольга): sendPayload(Payload.fromBytes) и ждать SUCCESS или FAILURE в onPayloadTransferUpdate с таймаутом.")
    }

    override fun disconnect(peer: MemberId) {
        // TODO(Ольга): disconnectFromEndpoint для endpointId этого соседа и событие Disconnected. docs/team/olga-transport.md
        throw NotImplementedError("TODO(Ольга): disconnectFromEndpoint для endpointId этого соседа и событие Disconnected.")
    }
}
