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
 * [PeerTransport] на Nearby Connections: находит телефоны своей группы через advertising и discovery, держит
 * с ними соединения и передаёт байты движку. Запускает и останавливает его `RouteService`.
 *
 * Стратегия `P2P_CLUSTER`, serviceId с тегом группы, имя устройства равно `member_id`. `NON_DISRUPTIVE`
 * не даёт Nearby переключать Wi-Fi, и телефон не теряет интернет для отправки на сервер. Кого подключать,
 * решает [ConnectionPolicy], радио проверяет [RadioCheck].
 *
 * Обратные вызовы Nearby приходят в главный поток, поэтому их нужно сразу перекладывать в [eventsChannel].
 * Исключение одно: статус отправки из `onPayloadTransferUpdate` надо отдавать ждущему `send` прямо из
 * обратного вызова, потому что через [eventsChannel] движок прочитает его только после возврата из `send`.
 * SUCCESS там значит, что Nearby передал байты, а приём соседнее приложение не подтверждает.
 *
 * Задача: docs/team/olga-transport.md
 */
@Singleton
class NearbyPeerTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val radioCheck: RadioCheck,
    /** Область для отложенных запросов соединения, повторов и окон discovery, живёт дольше одного запуска транспорта. */
    @ApplicationScope private val scope: CoroutineScope,
) : PeerTransport {

    private val eventsChannel = Channel<TransportEvent>(Channel.UNLIMITED)
    override val events: Flow<TransportEvent> = eventsChannel.receiveAsFlow()

    private val peers = MutableStateFlow<Set<MemberId>>(emptySet())
    override val connectedPeers: StateFlow<Set<MemberId>> = peers.asStateFlow()

    override fun start(config: TransportConfig) {
        // TODO(Ольга): проверить радио и включить advertising и discovery по политике из 03.
        //  03 значит docs/03-sync-protocol.md, раздел «Транспорт поверх Nearby Connections». Решения о соединениях
        //  через ConnectionPolicy, входящие принимать без сверки кода аутентификации Nearby, после перезапуска
        //  процесса поднимать всё с нуля.
        //  docs/team/olga-transport.md
        throw NotImplementedError("TODO(Ольга): проверить радио и включить advertising и discovery по политике из 03.")
    }

    override fun stop() {
        // TODO(Ольга): остановить advertising и discovery, разорвать все соединения, очистить соответствие endpointId и member_id.
        //  docs/team/olga-transport.md
        throw NotImplementedError("TODO(Ольга): остановить advertising и discovery, разорвать все соединения, очистить соответствие endpointId и member_id.")
    }

    override suspend fun send(peer: MemberId, bytes: ByteArray): Boolean {
        // TODO(Ольга): sendPayload(Payload.fromBytes) и ждать SUCCESS или FAILURE в onPayloadTransferUpdate с таймаутом.
        //  docs/team/olga-transport.md
        throw NotImplementedError("TODO(Ольга): sendPayload(Payload.fromBytes) и ждать SUCCESS или FAILURE в onPayloadTransferUpdate с таймаутом.")
    }

    override fun disconnect(peer: MemberId) {
        // TODO(Ольга): disconnectFromEndpoint для endpointId этого соседа и событие Disconnected.
        //  На своей стороне Nearby onDisconnected после этого не вызывает.
        //  docs/team/olga-transport.md
        throw NotImplementedError("TODO(Ольга): disconnectFromEndpoint для endpointId этого соседа и событие Disconnected.")
    }
}
