package ru.pereklichka.domain.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.pereklichka.domain.model.ControlEvent
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.EventType
import ru.pereklichka.domain.model.Fix
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.LatLon
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.presence.PresenceTracker
import ru.pereklichka.domain.testing.FakeClock
import ru.pereklichka.domain.testing.InMemoryMeshStore
import ru.pereklichka.domain.testing.InMemoryNetwork
import ru.pereklichka.domain.testing.InMemoryTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Точечные сценарии сеанса [SyncEngine], которые в `MeshSimulationTest` не выходят детерминированно:
 * перестановка первых сообщений, невалидный сосед, битые сообщения, молчащее соединение, флаг `more`.
 *
 * Сообщения соседа скармливаются движку напрямую через `InMemoryTransport.onReceived`, поэтому
 * порядок и содержимое каждого теста заданы вручную. Разрыв виден по `network.isConnected`.
 */
class SyncEngineSessionTest {

    private val me = MemberId("me")
    private val peer = MemberId("peer")
    private val group = GroupInfo("tag", "token", "Тест", leaderId = me, me = me, createdAtWallMs = 0)

    private class Rig(
        val engine: SyncEngine,
        val store: InMemoryMeshStore,
        val network: InMemoryNetwork,
        val transport: InMemoryTransport,
        val peerTransport: InMemoryTransport,
        val codec: MeshCodec,
    )

    private fun TestScope.rig(config: SyncConfig = SyncConfig()): Rig {
        val codec = MeshCodec(config)
        val clock = FakeClock { testScheduler.currentTime }
        val network = InMemoryNetwork(backgroundScope, { testScheduler.currentTime })
        val transport = network.transport(me)
        // У соседа движка нет: его сообщения шлём руками, его transport нужен только чтобы связь была.
        val peerTransport = network.transport(peer)
        peerTransport.start(config(peer))
        transport.start(config(me))
        val store = InMemoryMeshStore(group, clock)
        val engine = SyncEngine(store, transport, PresenceTracker(clock), codec, clock, config)
        engine.start(backgroundScope, group)
        return Rig(engine, store, network, transport, peerTransport, codec)
    }

    /** Сообщения, которые движок отправил соседу и сеть ему доставила. */
    private fun TestScope.observePeer(rig: Rig): MutableList<MeshMessage> {
        val received = mutableListOf<MeshMessage>()
        backgroundScope.launch {
            rig.peerTransport.events.collect { event ->
                if (event is TransportEvent.Received) received += rig.codec.decode(event.bytes)
            }
        }
        return received
    }

    private fun config(me: MemberId) =
        TransportConfig("ru.pereklichka.g.tag", me, MutableStateFlow(emptySet())) { false }

    private fun Rig.receive(message: MeshMessage) {
        transport.onReceived(peer, codec.encode(message)!!)
    }

    private fun Rig.peerHello(): ByteArray =
        codec.encode(Hello.of(SyncSummary(emptyMap(), emptyMap(), emptyMap()), group.tag, peer))!!

    @Test
    fun `Delta до первого Hello буферизуется и применяется после валидного Hello`() = runTest {
        val rig = rig()
        network(rig).connect(me, peer)
        runCurrent()

        val event = ControlEvent(EventId.random(), author = peer, seq = 1, createdAtWallMs = 0, type = EventType.ALARM)
        // Перестановка в сети: ответ на наше Hello обогнал само Hello соседа.
        rig.receive(Delta(control = listOf(WireControlEvent(event, ageMs = 0))))
        runCurrent()

        assertTrue(network(rig).isConnected(me, peer), "Delta до Hello не должна рвать соединение")
        assertFalse(rig.store.snapshot().first().events.any { it.value.id == event.id }, "Delta без Hello применяться не должна")

        rig.transport.onReceived(peer, rig.peerHello())
        runCurrent()

        assertTrue(rig.store.snapshot().first().events.any { it.value.id == event.id }, "Буферная Delta должна примениться после Hello")
    }

    @Test
    fun `сводка соседа видна в reportedBySeq до разрыва соединения`() = runTest {
        val rig = rig()
        network(rig).connect(me, peer)
        runCurrent()

        rig.receive(Hello.of(SyncSummary(mapOf(me to 3L), emptyMap(), emptyMap()), group.tag, peer))
        runCurrent()
        assertEquals(mapOf(peer to mapOf(me to 3L)), rig.engine.reportedBySeq.value)

        network(rig).disconnect(me, peer)
        runCurrent()
        assertEquals(emptyMap(), rig.engine.reportedBySeq.value)
    }

    @Test
    fun `Hello с чужим тегом группы разрывает соединение`() = runTest {
        val rig = rig()
        network(rig).connect(me, peer)
        runCurrent()

        rig.receive(Hello.of(SyncSummary(emptyMap(), emptyMap(), emptyMap()), "чужой-тег", peer))
        runCurrent()

        assertFalse(network(rig).isConnected(me, peer))
    }

    @Test
    fun `Hello с чужим тегом после рукопожатия разрывает соединение`() = runTest {
        val rig = rig()
        network(rig).connect(me, peer)
        runCurrent()

        rig.transport.onReceived(peer, rig.peerHello())
        runCurrent()

        rig.receive(Hello.of(SyncSummary(emptyMap(), emptyMap(), emptyMap()), "чужой-тег", peer))
        runCurrent()

        assertFalse(network(rig).isConnected(me, peer))
    }

    @Test
    fun `Hello, чей from не совпадает с соседом, разрывает соединение`() = runTest {
        val rig = rig()
        network(rig).connect(me, peer)
        runCurrent()

        rig.receive(Hello.of(SyncSummary(emptyMap(), emptyMap(), emptyMap()), group.tag, MemberId("кто-то-другой")))
        runCurrent()

        assertFalse(network(rig).isConnected(me, peer))
    }

    @Test
    fun `три битых сообщения подряд рвут соединение`() = runTest {
        val rig = rig()
        network(rig).connect(me, peer)
        runCurrent()

        rig.transport.onReceived(peer, rig.peerHello())
        repeat(3) {
            rig.transport.onReceived(peer, byteArrayOf(0x7F, 0x7F, 0x7F))
        }
        runCurrent()

        assertFalse(network(rig).isConnected(me, peer))
    }

    @Test
    fun `сосед, молчащий дольше silentDisconnectMs, отключается`() = runTest {
        val rig = rig()
        network(rig).connect(me, peer)
        runCurrent()

        // Сосед молчит с самого подключения: ни Hello, ни Delta от него не было.
        // Таймер тикает раз в helloIntervalMs, поэтому проверка случится на следующем тике после порога.
        advanceTimeBy(SyncConfig().silentDisconnectMs + SyncConfig().helloIntervalMs + 1)
        runCurrent()

        assertFalse(network(rig).isConnected(me, peer))
    }

    @Test
    fun `Delta с more получает в ответ Hello вне таймера`() = runTest {
        val rig = rig()
        network(rig).connect(me, peer)
        runCurrent()

        val received = mutableListOf<MeshMessage>()
        backgroundScope.launch {
            rig.peerTransport.events.collect { event ->
                if (event is TransportEvent.Received) received += rig.codec.decode(event.bytes)
            }
        }

        rig.transport.onReceived(peer, rig.peerHello())
        runCurrent()
        val sentAfterHello = rig.engine.diagnostics.value.sentMessages

        rig.receive(Delta(more = true))
        runCurrent()
        // Доставка до соседа идёт с задержкой эфира 30–150 мс, дадим ей долететь.
        advanceTimeBy(200)

        val sent = rig.engine.diagnostics.value.sentMessages
        assertTrue(sent > sentAfterHello, "Delta с more должна вызвать Hello соседу")
        // Одно Hello уходит при подключении, второе — вне таймера, как ответ на more.
        val hellos = received.count { it is Hello }
        assertTrue(hellos >= 2, "Delta с more должна вызвать Hello соседу, а ушло hellos: $hellos")
    }

    private fun network(rig: Rig) = rig.network

    @Test
    fun `Hello уходит соседу каждые helloIntervalMs`() = runTest {
        val rig = rig()
        val received = observePeer(rig)
        network(rig).connect(me, peer)
        // Одно Hello на подключение плюс по таймеру.
        advanceTimeBy(SyncConfig().helloIntervalMs * 2 + 1_000)
        val hellos = received.count { it is Hello }
        assertTrue(hellos >= 3, "За два интервала должно уйти не меньше трёх Hello, а ушло $hellos")
    }

    @Test
    fun `переподключение начинает сеанс заново с Hello`() = runTest {
        val rig = rig()
        val received = observePeer(rig)
        network(rig).connect(me, peer)
        rig.transport.onReceived(peer, rig.peerHello())
        runCurrent()
        network(rig).disconnect(me, peer)
        runCurrent()

        network(rig).connect(me, peer)
        runCurrent()
        advanceTimeBy(200)

        assertTrue(network(rig).isConnected(me, peer))
        // Сеанс начался с чистого peerKnown, поэтому первым делом снова уходит Hello.
        assertTrue(received.last() is Hello, "После переподключения ждали Hello, а было: ${received.lastOrNull()}")
    }

    @Test
    fun `Delta, не влезшая в maxMessageBytes, делится на части и доходит целиком`() = runTest {
        // Лимит подбираем измерением: влезает пара событий, но не все четыре.
        val probe = MeshCodec(SyncConfig())
        val sample = ControlEvent(EventId.random(), author = me, seq = 1, createdAtWallMs = 0, type = EventType.ALARM)
        val oneEventBytes = probe.encode(Delta(control = listOf(WireControlEvent(sample, ageMs = 0))))!!.size
        val rig = rig(SyncConfig(maxMessageBytes = oneEventBytes * 2 + 16))

        val received = observePeer(rig)
        network(rig).connect(me, peer)
        runCurrent()

        val events = (1..4).map {
            rig.store.appendOwnEvent(ControlEventDraft(EventType.ALARM, target = peer))
        }
        runCurrent()

        // Сосед прислал пустую сводку: событий у него нет, движок обязан отдать все.
        rig.transport.onReceived(peer, rig.peerHello())
        runCurrent()
        advanceTimeBy(500)

        val sentSeqs = received.filterIsInstance<Delta>().flatMap { it.control }.map { it.event.seq }.toSet()
        assertEquals(events.map { it.seq }.toSet(), sentSeqs, "Все события должны дойти, пусть и частями")
        assertTrue(received.filterIsInstance<Delta>().size > 1, "Delta должна была разделиться")
    }

    @Test
    fun `Delta из одного события, одного состояния и одной точки делится по записям и доходит целиком`() = runTest {
        // Те же записи в отдельном хранилище нужны, чтобы измерить их размер до запуска движка с лимитом.
        val fix = Fix(LatLon(43.35, 42.44), 5.0, null, null, null, timeUtcMs = 0, elapsedRealtimeMs = 0)
        val clock = FakeClock { testScheduler.currentTime }
        val probe = InMemoryMeshStore(group, clock)
        probe.appendOwnEvent(ControlEventDraft(EventType.ALARM, target = peer))
        probe.putOwnState(MemberStateDraft(fix, batteryPercent = 80, heardAgoMs = emptyMap()))
        probe.appendOwnLocation(fix, batteryPercent = 80)
        val event = probe.controlEvents(me, afterSeq = 0, limit = 1).single().toWire(clock)
        val state = probe.statesNewerThan(emptyMap()).single().toWire(clock)
        val point = probe.locations(me, afterSeq = 0, limit = 1).single()

        // Лимит пропускает любую запись по отдельности, но не все три вместе.
        val codec = MeshCodec(SyncConfig())
        val limit = listOf(Delta(control = listOf(event)), Delta(states = listOf(state)), Delta(locations = listOf(point)))
            .maxOf { codec.encode(it)!!.size } + 8
        val all = Delta(control = listOf(event), states = listOf(state), locations = listOf(point))
        assertTrue(codec.encode(all)!!.size > limit, "Все три записи влезли в лимит, сценарий ничего не проверит")

        // Записи создаются до подключения, поэтому сразу никуда не уходят и все три попадают в ответ на сводку.
        val rig = rig(SyncConfig(maxMessageBytes = limit))
        rig.store.appendOwnEvent(ControlEventDraft(EventType.ALARM, target = peer))
        rig.store.putOwnState(MemberStateDraft(fix, batteryPercent = 80, heardAgoMs = emptyMap()))
        rig.store.appendOwnLocation(fix, batteryPercent = 80)
        val received = observePeer(rig)
        network(rig).connect(me, peer)
        runCurrent()

        rig.transport.onReceived(peer, rig.peerHello())
        runCurrent()
        advanceTimeBy(500)

        val deltas = received.filterIsInstance<Delta>()
        assertEquals(1, deltas.sumOf { it.control.size }, "Событие должно дойти один раз")
        assertEquals(1, deltas.sumOf { it.states.size }, "Состояние должно дойти один раз")
        assertEquals(1, deltas.sumOf { it.locations.size }, "Точка должна дойти один раз")
        assertTrue(deltas.none { it.control.isEmpty() && it.states.isEmpty() && it.locations.isEmpty() }, "Пустых частей быть не должно")
        assertEquals(0, rig.engine.diagnostics.value.failedSends)
    }
}
