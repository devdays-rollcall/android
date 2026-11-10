package ru.pereklichka.domain.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.presence.PresenceTracker
import ru.pereklichka.domain.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.ContinuationInterceptor

/**
 * Движок синхронизации: сверяет записи этого телефона с каждым соседом и досылает то, чего у соседа нет.
 *
 * На каждое соединение ведёт сеанс по правилам `docs/03-sync-protocol.md`: шлёт сводку ([Hello]) при подключении
 * и каждые 10 с, на сводку соседа отвечает одной [Delta], свои новые события и состояние рассылает сразу.
 * Байты получает и отправляет через [PeerTransport], записи читает и применяет через [MeshStore], о каждом
 * входящем сообщении сообщает [PresenceTracker]. Запускает и останавливает движок `RouteService`, он же
 * запускает транспорт. В `MeshSimulationTest` тот же движок работает поверх
 * [ru.pereklichka.domain.testing.InMemoryMeshStore] и [ru.pereklichka.domain.testing.InMemoryTransport].
 *
 * Все корутины движка выполняются по одной, на однопоточном представлении диспетчера вызывающего, поэтому
 * состояние сеансов (`peerKnown` и прочее) можно держать в обычных коллекциях. Но на каждом suspend-вызове
 * (`store.apply`, `send`) может выполниться другой обработчик: проверку состояния и его изменение через такой
 * вызов не разносить. Блокировать поток в обработчике нельзя.
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
     * запускает после движка, иначе движок пропустит первые подключения.
     *
     * Исключение в обработчике считается ошибкой в коде: оно завершает свою корутину и уходит в обработчик
     * ошибок области службы, а без него приложение на Android падает. Ожидаемые ошибки (неразборчивое
     * сообщение, неудачная отправка) обработчики разбирают сами.
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

        // TODO(Ольга): состояние сеанса с каждым соседом: peerKnown, очередь отправки, момент последнего сообщения.
        //  Сводка соседа заменяет peerKnown целиком, сливать их нельзя.
        //  docs/team/olga-transport.md

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
            // TODO(Ольга): завести peerKnown пустым и отправить соседу Hello.
            //  Первое сообщение от соседа тоже должно быть Hello своей группы.
            //  docs/team/olga-transport.md
            throw NotImplementedError("TODO(Ольга): завести peerKnown пустым и отправить соседу Hello.")
        }

        private suspend fun onDisconnected(peer: MemberId) {
            // TODO(Ольга): забыть сеанс соседа и остановить его очередь отправки.
            //  После переподключения сеанс начинается заново с пустого peerKnown.
            //  docs/team/olga-transport.md
            throw NotImplementedError("TODO(Ольга): забыть сеанс соседа и остановить его очередь отправки.")
        }

        private suspend fun onReceived(peer: MemberId, bytes: ByteArray) {
            // TODO(Ольга): разобрать сообщение, отметить presence.onHeard(peer) и обработать Hello или Delta.
            //  На Hello ответить одной Delta, Delta применить через store.apply(delta.toIncoming(clock)).
            //  docs/team/olga-transport.md, docs/03-sync-protocol.md «Правила сеанса» и «Ответ на сводку»
            throw NotImplementedError(
                "TODO(Ольга): разобрать сообщение, отметить presence.onHeard(peer) и обработать Hello или Delta.",
            )
        }

        private suspend fun onStoreChange(change: StoreChange) {
            // TODO(Ольга): своё новое событие или состояние сразу отправить соседям, у которых его нет по peerKnown.
            //  Чужие состояния и все точки уходят только в ответ на сводку соседа.
            //  docs/team/olga-transport.md, docs/03-sync-protocol.md «Что уходит сразу»
            throw NotImplementedError(
                "TODO(Ольга): своё новое событие или состояние сразу отправить соседям, у которых его нет по peerKnown.",
            )
        }

        private suspend fun onHelloTimer() {
            // TODO(Ольга): отправить Hello каждому соседу, а молчащего дольше config.silentDisconnectMs отключить.
            //  Hello собирается из store.summary() через Hello.of.
            //  docs/team/olga-transport.md
            throw NotImplementedError(
                "TODO(Ольга): отправить Hello каждому соседу, а молчащего дольше config.silentDisconnectMs отключить.",
            )
        }
    }

    private fun parentDispatcher(parent: CoroutineScope): CoroutineDispatcher =
        parent.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Default
}
