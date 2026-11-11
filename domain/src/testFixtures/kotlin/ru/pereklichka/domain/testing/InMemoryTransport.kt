package ru.pereklichka.domain.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.sync.PeerTransport
import ru.pereklichka.domain.sync.TransportConfig
import ru.pereklichka.domain.sync.TransportEvent
import kotlin.random.Random

/**
 * Эфир для нескольких виртуальных телефонов в одном тесте: кто с кем соединён и как ведут себя сообщения.
 * На нём `MeshSimulationTest` гоняет настоящий [ru.pereklichka.domain.sync.SyncEngine] без телефонов.
 *
 * Тест сам решает, какие связи есть ([connect], [disconnect]), а сообщения идут с задержкой и пропускной
 * способностью канала и по команде теста теряются, дублируются или переставляются. Задержку и скорость
 * по умолчанию прикинули, на телефонах их не мерили. Случайность задаётся [seed], поэтому прогон повторяем.
 *
 * Время берётся из [now], а доставка планируется в [scope], обычно это `backgroundScope` и виртуальное время
 * `runTest`. Класс не потокобезопасен: тест и все телефоны работают в одном потоке тестового диспетчера.
 */
class InMemoryNetwork(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    seed: Int = 1,
) {
    private val random = Random(seed)

    /** Доля сообщений, которые пропадают в пути. Отправитель об этом не узнаёт, как и с Nearby. */
    var lossRate: Double = 0.0

    /** Доля сообщений, которые приходят дважды. */
    var duplicateRate: Double = 0.0

    /** Доля сообщений, которые задерживаются на 0,2–3 с и обгоняются следующими. */
    var reorderRate: Double = 0.0

    /** Задержка доставки одного сообщения, мс, случайная в этих пределах. */
    var latencyMs: LongRange = 30L..150L

    /** Пропускная способность канала в одну сторону, байт в секунду. */
    var bandwidthBytesPerSecond: Int = 40_000

    private val transports = HashMap<MemberId, InMemoryTransport>()

    /** Активные связи; номер связи меняется при каждом подключении, чтобы старые сообщения не дошли по новой. */
    private val links = HashMap<Set<MemberId>, Int>()
    private var lastLinkNumber = 0

    /** Когда освободится канал в каждом направлении: сообщения одного направления передаются по очереди. */
    private val channelBusyUntil = HashMap<Pair<MemberId, MemberId>, Long>()

    /** Виртуальный телефон с идентификатором [member]. */
    fun transport(member: MemberId): InMemoryTransport {
        check(member !in transports) { "Телефон $member уже есть в сети" }
        return InMemoryTransport(this, member).also { transports[member] = it }
    }

    /** Соединяет два телефона. Если один из них не запущен или связь уже есть, ничего не делает. */
    fun connect(a: MemberId, b: MemberId) {
        val pair = setOf(a, b)
        if (pair in links || transports[a]?.isStarted != true || transports[b]?.isStarted != true) return
        links[pair] = ++lastLinkNumber
        transports.getValue(a).onConnected(b)
        transports.getValue(b).onConnected(a)
    }

    /** Рвёт связь. Сообщения, которые были в пути, не дойдут. */
    fun disconnect(a: MemberId, b: MemberId) {
        if (links.remove(setOf(a, b)) == null) return
        transports.getValue(a).onDisconnected(b)
        transports.getValue(b).onDisconnected(a)
    }

    fun isConnected(a: MemberId, b: MemberId): Boolean = setOf(a, b) in links

    internal fun disconnectAll(member: MemberId) {
        links.keys.filter { member in it }.forEach { pair ->
            val (a, b) = pair.toList()
            disconnect(a, b)
        }
    }

    internal fun send(from: MemberId, to: MemberId, bytes: ByteArray): Boolean {
        val linkNumber = links[setOf(from, to)] ?: return false
        val direction = from to to
        val transferStart = maxOf(now(), channelBusyUntil[direction] ?: 0)
        val transferEnd = transferStart + bytes.size * 1000L / bandwidthBytesPerSecond
        channelBusyUntil[direction] = transferEnd

        var arrival = transferEnd + random.nextLong(latencyMs.first, latencyMs.last + 1)
        if (random.nextDouble() < reorderRate) arrival += random.nextLong(200, 3_000)
        if (random.nextDouble() < lossRate) return true
        deliverAt(arrival, from, to, bytes, linkNumber)
        if (random.nextDouble() < duplicateRate) deliverAt(arrival + random.nextLong(10, 500), from, to, bytes, linkNumber)
        return true
    }

    private fun deliverAt(time: Long, from: MemberId, to: MemberId, bytes: ByteArray, linkNumber: Int) {
        scope.launch {
            delay(time - now())
            if (links[setOf(from, to)] == linkNumber) transports.getValue(to).onReceived(from, bytes)
        }
    }
}

/**
 * Виртуальный телефон в [InMemoryNetwork]: [PeerTransport] без радио. Создаётся через [InMemoryNetwork.transport].
 *
 * Политику соединений Nearby (лимит соединений, список блокировки, четвёртый слот) он не моделирует:
 * связи задаёт тест. Как и Nearby после смерти процесса, [stop] рвёт все связи и забывает недоставленные события.
 */
class InMemoryTransport internal constructor(
    private val network: InMemoryNetwork,
    private val me: MemberId,
) : PeerTransport {

    private val eventsChannel = Channel<TransportEvent>(Channel.UNLIMITED)
    override val events: Flow<TransportEvent> = eventsChannel.receiveAsFlow()

    private val peers = MutableStateFlow<Set<MemberId>>(emptySet())
    override val connectedPeers: StateFlow<Set<MemberId>> = peers.asStateFlow()

    internal var isStarted = false
        private set

    override fun start(config: TransportConfig) {
        require(config.me == me) { "Транспорт телефона $me запущен с чужим id ${config.me}" }
        isStarted = true
    }

    override fun stop() {
        network.disconnectAll(me)
        isStarted = false
        while (eventsChannel.tryReceive().isSuccess) {
            // Отбрасываем события, которые движок не успел прочитать до остановки.
        }
    }

    override suspend fun send(peer: MemberId, bytes: ByteArray): Boolean = network.send(me, peer, bytes)

    override fun disconnect(peer: MemberId) = network.disconnect(me, peer)

    internal fun onConnected(peer: MemberId) {
        peers.update { it + peer }
        eventsChannel.trySend(TransportEvent.Connected(peer))
    }

    internal fun onDisconnected(peer: MemberId) {
        peers.update { it - peer }
        eventsChannel.trySend(TransportEvent.Disconnected(peer))
    }

    internal fun onReceived(peer: MemberId, bytes: ByteArray) {
        eventsChannel.trySend(TransportEvent.Received(peer, bytes))
    }
}
