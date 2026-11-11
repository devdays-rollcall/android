package ru.pereklichka.domain.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.LocationPoint
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.MemberState
import ru.pereklichka.domain.presence.PresenceTracker
import ru.pereklichka.domain.time.Clock
import ru.pereklichka.domain.time.Stamped
import ru.pereklichka.domain.time.ageNow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.ContinuationInterceptor

/**
 * Движок синхронизации: сверяет записи этого телефона с каждым соседом и досылает то, чего у соседа нет.
 *
 * На каждое соединение движок ведёт сеанс по правилам `docs/03-sync-protocol.md`. При подключении и каждые 10 с
 * он шлёт сводку ([Hello]), на сводку соседа отвечает одной [Delta], а свои новые события и состояние рассылает
 * сразу. Байты идут через [PeerTransport], записи через [MeshStore], и о каждом входящем сообщении узнаёт
 * [PresenceTracker]. Запускает и останавливает движок `RouteService`. В `MeshSimulationTest` тот же движок
 * работает поверх [ru.pereklichka.domain.testing.InMemoryMeshStore] и [ru.pereklichka.domain.testing.InMemoryTransport].
 *
 * Корутины движка идут на `limitedParallelism(1)` от диспетчера вызывающего. Параллельно они не выполняются,
 * хотя могут переходить между потоками, так что состояние сеансов (`peerKnown` и прочее) можно держать в обычных
 * коллекциях. Но на каждом suspend-вызове (`store.apply`, `send`) может выполниться другой обработчик. Если
 * проверить состояние, вызвать `send` и потом менять состояние по старой проверке, к этому моменту оно уже могло
 * измениться. Блокировать поток в обработчике нельзя.
 *
 * Задача: docs/team/olga-transport.md
 */
@Singleton
class SyncEngine @Inject constructor(
    private val store: MeshStore,
    private val transport: PeerTransport,
    private val presence: PresenceTracker,
    private val codec: MeshCodec,
    private val clock: Clock,
    private val config: SyncConfig,
) {
    private val diagnosticsState = MutableStateFlow(SyncDiagnostics())

    /** Счётчики для экрана диагностики. */
    val diagnostics: StateFlow<SyncDiagnostics> = diagnosticsState.asStateFlow()

    private var session: Session? = null

    /**
     * Запускает обмен для [group] в дочерней области [scope]. Служба передаёт область на `Dispatchers.Default`,
     * потому что движок кодирует и сжимает сообщения, а на главном потоке этого делать нельзя. Транспорт служба
     * запускает после движка, но подписка движка всё равно может начаться чуть позже. События транспорта
     * в обеих реализациях дождутся её в буфере `Channel.UNLIMITED`. У `store.changes` буфера нет, поэтому
     * самое первое своё состояние движок может пропустить; соседи получат его со следующей сводкой.
     *
     * Исключение в обработчике считается ошибкой в коде. Обработчика исключений у области службы нет, поэтому
     * такое исключение роняет приложение. Ожидаемые ошибки (неразборчивое сообщение, неудачная отправка)
     * обработчики разбирают сами.
     *
     * @throws IllegalStateException если движок уже запущен и [stop] не вызывали.
     */
    fun start(scope: CoroutineScope, group: GroupInfo) {
        check(session?.scope?.isActive != true) { "SyncEngine уже запущен" }
        session = Session(group, scope).also { it.launch() }
        diagnosticsState.update { it.copy(running = true) }
    }

    /** Останавливает обмен и забывает всё о соседях. Следующий [start] начнёт с чистого листа. */
    fun stop() {
        session?.scope?.cancel()
        session = null
        diagnosticsState.update { it.copy(running = false) }
    }

    /** Один запуск движка. Всё, что относится к соседям, живёт здесь и пропадает вместе с ним при [stop]. */
    private inner class Session(val group: GroupInfo, parent: CoroutineScope) {

        val scope = CoroutineScope(
            parent.coroutineContext +
                SupervisorJob(parent.coroutineContext[Job]) +
                parentDispatcher(parent).limitedParallelism(1),
        )

        /** Сеансы с живыми соседями. Создаются и удаляются только в обработчиках на однопоточном диспетчере. */
        private val peers = LinkedHashMap<MemberId, PeerSession>()

        /**
         * Сеанс обмена с одним соседом: что у него уже есть, очередь отправки и здоровье связи.
         *
         * Отправкой занимается своя корутина, которая читает два канала через [select]: срочный
         * (Hello, новые события, своё состояние) и обычный (ответы на сводки с порциями точек).
         * `select` смещённый — при готовности обоих каналов срочные уходят первыми, поэтому тревога
         * не ждёт в очереди за мегабайтом трека.
         */
        private inner class PeerSession(val peer: MemberId) {

            /**
             * Что, по нашим сведениям, есть у соседа. Сводка соседа заменяет её целиком, сливать нельзя:
             * с заменой потерянная Delta исправляется следующей сводкой, а со слиянием оставляла бы дыру
             * навсегда (см. `docs/03-sync-protocol.md`, «Правила сеанса»).
             */
            var peerKnown = SyncSummary(control = emptyMap(), locations = emptyMap(), states = emptyMap())

            /** Момент последнего разобранного сообщения от соседа; по нему работает молчаливый разрыв. */
            var lastMessageAtMs = clock.elapsedMs()

            /** Неразборчивые сообщения подряд; по `config.maxCorruptInRow` соединение рвётся. */
            var corruptInRow = 0

            /** true, когда от соседа получено и проверено первое сообщение — Hello своей группы. */
            var handshakeDone = false

            /**
             * Delta, пришедшие до первого Hello, и их форма для `apply`: в сети сообщения могут
             * переставляться, поэтому ответ на наше Hello может обогнать сам Hello. Возраст записей
             * в [IncomingDelta] считается при приёме, а не при применении. Размер ограничен
             * [MAX_PENDING_BEFORE_HELLO].
             */
            val pendingBeforeHello = mutableListOf<Pair<Delta, IncomingDelta>>()

            /** Срочные: Hello, свои новые события и состояние, ретранслируемые события. */
            val urgent = Channel<MeshMessage>(Channel.UNLIMITED)

            /** Ответы на сводки соседа: самая объёмная часть обмена, в том числе порции точек. */
            val normal = Channel<MeshMessage>(Channel.UNLIMITED)

            private var senderJob: Job? = null

            fun startSender() {
                senderJob = scope.launch {
                    while (isActive) {
                        val message = select<MeshMessage> {
                            urgent.onReceive { it }
                            normal.onReceive { it }
                        }
                        sendMessage(this@PeerSession, message)
                    }
                }
            }

            fun stopSender() {
                senderJob?.cancel()
            }

            fun enqueueUrgent(message: MeshMessage) {
                urgent.trySend(message)
            }

            fun enqueueNormal(message: MeshMessage) {
                normal.trySend(message)
            }

            /** Ответы на сводку считаются из свежего `peerKnown`; собранные по старому в очереди не нужны. */
            fun drainNormal() {
                while (normal.tryReceive().isSuccess) Unit
            }

            /**
             * Отмечает, что записи из [delta] у соседа есть. Вызывается после каждой отправки и после
             * Delta от него (присланное у него есть). Оценка оптимистичная: если отправка потерялась,
             * её исправит следующая сводка соседа.
             */
            fun markKnown(delta: Delta) {
                val control = peerKnown.control.toMutableMap()
                delta.control.forEach { control[it.event.author] = maxOf(control[it.event.author] ?: 0L, it.event.seq) }
                val states = peerKnown.states.toMutableMap()
                delta.states.forEach { states[it.state.member] = maxOf(states[it.state.member] ?: 0L, it.state.seq) }
                val locations = peerKnown.locations.toMutableMap()
                delta.locations.forEach { locations[it.author] = maxOf(locations[it.author] ?: 0L, it.seq) }
                peerKnown = SyncSummary(control = control, locations = locations, states = states)
            }
        }

        fun launch() {
            scope.launch {
                transport.events.collect { event ->
                    when (event) {
                        is TransportEvent.Connected -> onConnected(event.peer)
                        is TransportEvent.Disconnected -> onDisconnected(event.peer)
                        is TransportEvent.Received -> onReceived(event.peer, event.bytes)
                    }
                }
            }
            scope.launch {
                store.changes.collect { onStoreChange(it) }
            }
            scope.launch {
                while (isActive) {
                    delay(config.helloIntervalMs)
                    onHelloTimer()
                }
            }
        }

        private suspend fun onConnected(peer: MemberId) {
            peers.remove(peer)?.stopSender()
            val session = PeerSession(peer)
            peers[peer] = session
            session.startSender()
            // Первое сообщение по новому соединению — Hello: до него соседу слать нечего.
            session.enqueueUrgent(hello())
        }

        private suspend fun onDisconnected(peer: MemberId) {
            peers.remove(peer)?.stopSender()
        }

        private suspend fun onReceived(peer: MemberId, bytes: ByteArray) {
            val session = peers[peer] ?: return
            val message = try {
                codec.decode(bytes)
            } catch (e: MeshFormatException) {
                onCorrupt(session)
                return
            }
            session.corruptInRow = 0
            diagnosticsState.update { it.copy(receivedMessages = it.receivedMessages + 1) }
            presence.onHeard(peer)
            session.lastMessageAtMs = clock.elapsedMs()

            if (!session.handshakeDone) return onFirstMessages(session, message)

            when (message) {
                is Hello -> if (message.isFrom(peer)) onHello(session, message) else transport.disconnect(peer)
                is Delta -> onDelta(session, message)
            }
        }

        /**
         * Разбор сообщений до рукопожатия: первое валидное Hello открывает сеанс и запускает
         * отложенные Delta, чужое или поддельное Hello рвёт соединение, а Delta до Hello
         * буферизуются (в сети они могут обогнать Hello) с лимитом [MAX_PENDING_BEFORE_HELLO].
         */
        private suspend fun onFirstMessages(session: PeerSession, message: MeshMessage) {
            when {
                // Первое Hello определяет, свой это сосед или чужой.
                message is Hello && message.isFrom(session.peer) -> {
                    session.handshakeDone = true
                    onHello(session, message)
                    val queued = session.pendingBeforeHello.toList()
                    session.pendingBeforeHello.clear()
                    queued.forEach { (delta, incoming) -> onDelta(session, delta, incoming) }
                }
                message is Hello -> transport.disconnect(session.peer)
                message is Delta -> {
                    session.pendingBeforeHello.add(message to message.toIncoming(clock))
                    if (session.pendingBeforeHello.size > MAX_PENDING_BEFORE_HELLO) transport.disconnect(session.peer)
                }
            }
        }

        /** Hello от ожидаемого соседа своей группы; проверяется и до, и после рукопожатия. */
        private fun Hello.isFrom(peer: MemberId) = groupTag == group.tag && from == peer

        private fun onCorrupt(session: PeerSession) {
            diagnosticsState.update { it.copy(corruptMessages = it.corruptMessages + 1) }
            if (++session.corruptInRow >= config.maxCorruptInRow) transport.disconnect(session.peer)
        }

        private suspend fun onHello(session: PeerSession, message: Hello) {
            session.peerKnown = message.summary()
            session.drainNormal()
            session.enqueueNormal(composeDeltaReply(session))
        }

        private suspend fun onDelta(
            session: PeerSession,
            delta: Delta,
            incoming: IncomingDelta = delta.toIncoming(clock),
        ) {
            val result = store.apply(incoming)
            diagnosticsState.update { it.copy(applied = it.applied + result) }
            session.markKnown(delta)
            if (delta.more) session.enqueueUrgent(hello())
            if (result.newControl > 0) relayMissingControl(except = session.peer)
        }

        private suspend fun onStoreChange(change: StoreChange) {
            when (change) {
                StoreChange.OWN_EVENT -> sendOwnEvents()
                StoreChange.OWN_STATE -> sendOwnState()
                else -> Unit
            }
        }

        /** Своё новое событие сразу уходит всем соседям, у которых его ещё нет. */
        private suspend fun sendOwnEvents() {
            for (session in peers.values.toList()) {
                val after = session.peerKnown.control[group.me] ?: 0L
                val events = store.controlEvents(group.me, after, config.maxEventsPerDelta + 1)
                if (events.isEmpty()) continue
                val wire = events.take(config.maxEventsPerDelta).map { it.toWire(clock) }
                session.enqueueUrgent(Delta(control = wire, more = events.size > config.maxEventsPerDelta))
            }
        }

        /** Последнее состояние участника. Только состояния, без событий; snapshot() тут не подходит: без группы он молчит. */
        private suspend fun stateOf(member: MemberId): Stamped<MemberState>? =
            store.statesNewerThan(emptyMap()).firstOrNull { it.value.member == member }

        /** Своё новое состояние сразу уходит всем соседям, у которых его ещё нет. */
        private suspend fun sendOwnState() {
            val own = stateOf(group.me) ?: return
            val wire = own.toWire(clock)
            for (session in peers.values.toList()) {
                if ((session.peerKnown.states[group.me] ?: 0L) >= own.value.seq) continue
                session.enqueueUrgent(Delta(states = listOf(wire)))
            }
        }

        /** Новые события, принятые от соседа, досылаются остальным соседям, у которых их нет. */
        private suspend fun relayMissingControl(except: MemberId) {
            val my = store.summary()
            for (session in peers.values.toList()) {
                if (session.peer == except) continue
                val (wire, more) = collectMissingControl(my, session.peerKnown)
                if (wire.isEmpty()) continue
                session.enqueueUrgent(Delta(control = wire, more = more))
            }
        }

        private suspend fun onHelloTimer() {
            val now = clock.elapsedMs()
            for (session in peers.values.toList()) {
                if (now - session.lastMessageAtMs > config.silentDisconnectMs) {
                    transport.disconnect(session.peer)
                } else {
                    session.enqueueUrgent(hello())
                }
            }
        }

        private suspend fun hello(): Hello = Hello.of(store.summary(), group.tag, group.me)

        /**
         * Ответ на сводку соседа: события, которых у него нет (по возрастанию номера внутри автора,
         * не больше `config.maxEventsPerDelta`), все состояния новее соседских и одна порция точек.
         * Если после этого что-то для соседа остаётся, ставится `more` — он пришлёт свежую сводку
         * и получит следующую порцию.
         */
        private suspend fun composeDeltaReply(session: PeerSession): Delta {
            val my = store.summary()
            val known = session.peerKnown

            val (control, moreControl) = collectMissingControl(my, known)
            val states = store.statesNewerThan(known.states).map { it.toWire(clock) }
            val (locations, moreLocations) = collectLocations(my, known, session)

            return Delta(
                control = control,
                states = states,
                locations = locations,
                more = moreControl || moreLocations,
            )
        }

        /** События, которых у соседа нет по [known], и `more` = true, если не всё влезло в лимит. */
        private suspend fun collectMissingControl(
            my: SyncSummary,
            known: SyncSummary,
        ): Pair<List<WireControlEvent>, Boolean> {
            val result = mutableListOf<WireControlEvent>()
            var more = false
            for (author in my.control.keys.sortedBy { it.value }) {
                val peerHas = known.control[author] ?: 0L
                if (my.control.getValue(author) <= peerHas) continue
                val budget = config.maxEventsPerDelta - result.size
                if (budget <= 0) {
                    more = true
                    continue
                }
                val fetched = store.controlEvents(author, peerHas, budget + 1)
                if (fetched.size > budget) more = true
                fetched.take(budget).mapTo(result) { it.toWire(clock) }
            }
            return result to more
        }

        /**
         * Одна порция точек для соседа: сначала свои (отправляются всегда), затем остальные авторы
         * по кругу, начиная с места, которое зависит от пары «я и сосед» — тогда у нескольких соседей
         * новичка круг начинается с разных авторов.
         *
         * Точки автора X соседу не отправляются, если сосед сейчас сам слышит X: он получит их от самого
         * X. Слышимость видна по последнему состоянию соседа (`heardAgoMs`) и его возрасту. Точки,
         * пропущенные по этому правилу, в `more` не входят — иначе Hello и Delta пошли бы по кругу.
         */
        private suspend fun collectLocations(
            my: SyncSummary,
            known: SyncSummary,
            session: PeerSession,
        ): Pair<List<LocationPoint>, Boolean> {
            val peerState: Stamped<MemberState>? = stateOf(session.peer)
            fun peerHearsDirectly(author: MemberId): Boolean {
                val heardAgo = peerState?.value?.heardAgoMs?.get(author) ?: return false
                return peerState.ageNow(clock).ms + heardAgo <= PresenceTracker.HEARD_NOW_MS
            }

            val others = my.locations.keys.filter { it != group.me }.sortedBy { it.value }
            val startAt = if (others.isEmpty()) 0 else pairHash(session.peer).mod(others.size)
            val order = listOf(group.me) + others.drop(startAt) + others.take(startAt)

            val locations = mutableListOf<LocationPoint>()
            var more = false
            for (author in order) {
                if (author != group.me && peerHearsDirectly(author)) continue
                val peerHas = known.locations[author] ?: 0L
                if ((my.locations[author] ?: 0L) <= peerHas) continue
                val budget = config.maxPointsPerDelta - locations.size
                if (budget <= 0) {
                    more = true
                    continue
                }
                val fetched = store.locations(author, peerHas, budget + 1)
                if (fetched.size > budget) more = true
                locations += fetched.take(budget)
            }
            return locations to more
        }

        /** Стартовая позиция круга авторов точек: одинаковая для одной пары «я и сосед», разная между соседями. */
        private fun pairHash(peer: MemberId): Int = (group.me.value + "§" + peer.value).hashCode()

        /**
         * Кодирует сообщение и передаёт транспорту. Delta, не влезшая в `config.maxMessageBytes`,
         * делится пополам, и флаг `more` остаётся только в последней половине. По итогу отправленные
         * записи отмечаются в `peerKnown` — даже при `send` = false, потерю исправит сводка соседа.
         */
        private suspend fun sendMessage(session: PeerSession, message: MeshMessage) {
            val pending = ArrayDeque<MeshMessage>()
            pending.add(message)
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                val bytes = codec.encode(current)
                if (bytes == null) {
                    if (current is Delta && current.recordCount() > 1) {
                        val (firstHalf, secondHalf) = current.halved()
                        pending.addFirst(secondHalf)
                        pending.addFirst(firstHalf)
                    } else {
                        countSend(ok = false)
                    }
                    continue
                }
                val sent = transport.send(session.peer, bytes)
                countSend(sent)
                if (current is Delta) session.markKnown(current)
            }
        }

        private fun countSend(ok: Boolean) {
            diagnosticsState.update {
                it.copy(
                    sentMessages = it.sentMessages + if (ok) 1 else 0,
                    failedSends = it.failedSends + if (ok) 0 else 1,
                )
            }
        }
    }

    private companion object {

        /** Сколько Delta от одного соседа держим в буфере до первого Hello; дальше сосед считается сломанным. */
        const val MAX_PENDING_BEFORE_HELLO = 16

        fun Delta.recordCount(): Int = control.size + states.size + locations.size

        /** Делит записи Delta пополам: в первой половине `more` сбрасывается, во второй сохраняется. */
        fun Delta.halved(): Pair<Delta, Delta> {
            fun <T> List<T>.split(): Pair<List<T>, List<T>> = subList(0, size / 2) to subList(size / 2, size)
            val (c1, c2) = control.split()
            val (s1, s2) = states.split()
            val (l1, l2) = locations.split()
            return Delta(c1, s1, l1, more = false) to Delta(c2, s2, l2, more = more)
        }
    }

    private fun parentDispatcher(parent: CoroutineScope): CoroutineDispatcher =
        parent.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Default
}
