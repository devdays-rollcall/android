package ru.pereklichka.domain.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.pereklichka.domain.model.ControlEvent
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.Fix
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.GroupSnapshot
import ru.pereklichka.domain.model.LocationPoint
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.MemberState
import ru.pereklichka.domain.sync.ApplyResult
import ru.pereklichka.domain.sync.ControlEventDraft
import ru.pereklichka.domain.sync.IncomingDelta
import ru.pereklichka.domain.sync.MemberStateDraft
import ru.pereklichka.domain.sync.MeshStore
import ru.pereklichka.domain.sync.StoreChange
import ru.pereklichka.domain.sync.SyncSummary
import ru.pereklichka.domain.sync.toLocationPoint
import ru.pereklichka.domain.time.Clock
import ru.pereklichka.domain.time.Stamped

/**
 * Хранилище в памяти для тестов и эталон поведения для `RoomMeshStore`: правила приёма из
 * `03-sync-protocol.md` здесь записаны полностью, и обе реализации проходят `MeshStoreContractTest`.
 *
 * Мьютекс делает каждую операцию атомарной, как транзакция Room: две [apply] не перемешиваются,
 * а максимумы по авторам читаются под тем же замком, что и запись.
 */
class InMemoryMeshStore(private val group: GroupInfo, private val clock: Clock) : MeshStore {

    private val mutex = Mutex()

    // Индекс в списке равен номеру записи минус один: непрерывный префикс заложен в саму структуру.
    private val control = HashMap<MemberId, MutableList<Stamped<ControlEvent>>>()
    private val points = HashMap<MemberId, MutableList<LocationPoint>>()
    private val states = HashMap<MemberId, Stamped<MemberState>>()

    /** UUID всех событий и точек: запись с данным id хранится не больше одного раза. */
    private val storedIds = HashSet<EventId>()
    private val rejectedPointIds = HashSet<EventId>()

    private val changesFlow = MutableSharedFlow<StoreChange>(extraBufferCapacity = 64)
    override val changes: Flow<StoreChange> = changesFlow.asSharedFlow()

    private val snapshotState = MutableStateFlow(buildSnapshot())
    override fun snapshot(): Flow<GroupSnapshot> = snapshotState.asStateFlow()

    private val me: MemberId get() = group.me

    override suspend fun summary(): SyncSummary = mutex.withLock {
        SyncSummary(
            control = control.mapValues { (_, events) -> events.size.toLong() },
            locations = points.mapValues { (_, authorPoints) -> authorPoints.size.toLong() },
            states = states.mapValues { (_, state) -> state.value.seq },
        )
    }

    override suspend fun controlEvents(author: MemberId, afterSeq: Long, limit: Int): List<Stamped<ControlEvent>> =
        mutex.withLock { control[author].orEmpty().range(afterSeq, limit) }

    override suspend fun locations(author: MemberId, afterSeq: Long, limit: Int): List<LocationPoint> =
        mutex.withLock { points[author].orEmpty().range(afterSeq, limit) }

    override suspend fun statesNewerThan(known: Map<MemberId, Long>): List<Stamped<MemberState>> =
        mutex.withLock { states.values.filter { it.value.seq > (known[it.value.member] ?: 0) } }

    override suspend fun apply(delta: IncomingDelta): ApplyResult {
        val result = mutex.withLock {
            val counter = ApplyCounter()
            applyControl(delta.control, counter)
            applyStates(delta.states, counter)
            applyLocations(delta.locations, counter)
            if (counter.newControl > 0 || counter.newStates > 0) snapshotState.value = buildSnapshot()
            counter.toResult()
        }
        if (result.newControl > 0) changesFlow.emit(StoreChange.REMOTE_EVENTS)
        if (result.newStates > 0) changesFlow.emit(StoreChange.REMOTE_STATES)
        if (result.newLocations > 0) changesFlow.emit(StoreChange.REMOTE_LOCATIONS)
        return result
    }

    override suspend fun appendOwnEvent(draft: ControlEventDraft): ControlEvent {
        val event = mutex.withLock {
            val myEvents = control.getOrPut(me) { mutableListOf() }
            val event = draft.toEvent(EventId.random(), me, myEvents.size + 1L, clock.wallMs())
            myEvents += Stamped.own(event, clock)
            storedIds += event.id
            snapshotState.value = buildSnapshot()
            event
        }
        changesFlow.emit(StoreChange.OWN_EVENT)
        return event
    }

    override suspend fun appendOwnLocation(fix: Fix, batteryPercent: Int?): LocationPoint {
        val point = mutex.withLock {
            val myPoints = points.getOrPut(me) { mutableListOf() }
            val point = fix.toLocationPoint(EventId.random(), me, myPoints.size + 1L, batteryPercent)
            myPoints += point
            storedIds += point.id
            if (!point.hasValidValues()) rejectedPointIds += point.id
            point
        }
        changesFlow.emit(StoreChange.OWN_LOCATION)
        return point
    }

    override suspend fun putOwnState(draft: MemberStateDraft): MemberState {
        val state = mutex.withLock {
            val seq = (states[me]?.value?.seq ?: 0) + 1
            val state = draft.toState(me, seq, clock.elapsedMs())
            states[me] = Stamped.own(state, clock)
            snapshotState.value = buildSnapshot()
            state
        }
        changesFlow.emit(StoreChange.OWN_STATE)
        return state
    }

    /** Помечена ли точка как непригодная для сервера. В `RoomMeshStore` этому соответствует флаг `rejected`. */
    suspend fun isRejected(pointId: EventId): Boolean = mutex.withLock { pointId in rejectedPointIds }

    private fun applyControl(incoming: List<Stamped<ControlEvent>>, counter: ApplyCounter) {
        // Сортировка нужна, чтобы записи одного автора, пришедшие в Delta вперемешку, встали в префикс по порядку.
        for (stamped in incoming.sortedWith(compareBy({ it.value.author.value }, { it.value.seq }))) {
            val event = stamped.value
            val stream = control[event.author].orEmpty()
            when (admit(stream, { it.value.id }, event.author, event.seq, event.id)) {
                Admission.ACCEPT -> {
                    // Событие незнакомого типа тоже принимается: оно уже стало EventType.UNKNOWN, правила его пропустят.
                    control.getOrPut(event.author) { mutableListOf() } += stamped
                    storedIds += event.id
                    counter.newControl++
                }
                Admission.DUPLICATE -> counter.duplicates++
                Admission.OUT_OF_ORDER -> counter.outOfOrder++
                Admission.CONFLICT -> counter.conflicts++
            }
        }
    }

    private fun applyLocations(incoming: List<LocationPoint>, counter: ApplyCounter) {
        for (point in incoming.sortedWith(compareBy({ it.author.value }, { it.seq }))) {
            val stream = points[point.author].orEmpty()
            when (admit(stream, { it.id }, point.author, point.seq, point.id)) {
                Admission.ACCEPT -> {
                    points.getOrPut(point.author) { mutableListOf() } += point
                    storedIds += point.id
                    // Плохое содержимое не повод разрывать префикс: точка хранится, но на сервер не уйдёт.
                    if (!point.hasValidValues()) rejectedPointIds += point.id
                    counter.newLocations++
                }
                Admission.DUPLICATE -> counter.duplicates++
                Admission.OUT_OF_ORDER -> counter.outOfOrder++
                Admission.CONFLICT -> counter.conflicts++
            }
        }
    }

    private fun applyStates(incoming: List<Stamped<MemberState>>, counter: ApplyCounter) {
        for (stamped in incoming) {
            val member = stamped.value.member
            val isNewer = stamped.value.seq > (states[member]?.value?.seq ?: 0)
            when {
                !isNewer -> counter.duplicates++
                // Своё состояние новее моего бывает, только если кто-то пишет от моего имени.
                member == me -> counter.conflicts++
                else -> {
                    states[member] = stamped
                    counter.newStates++
                }
            }
        }
    }

    /**
     * Можно ли поставить запись автора [author] с номером [seq] в его поток [stream].
     * Это правило «ровно следующий номер» вместе с проверками на конфликт.
     */
    private inline fun <T> admit(
        stream: List<T>,
        idOf: (T) -> EventId,
        author: MemberId,
        seq: Long,
        id: EventId,
    ): Admission = when {
        seq < 1 -> Admission.OUT_OF_ORDER
        seq <= stream.size -> if (idOf(stream[(seq - 1).toInt()]) == id) Admission.DUPLICATE else Admission.CONFLICT
        // Своих записей с номером больше моего максимума не бывает: значит, кто-то пишет от моего имени.
        author == me -> Admission.CONFLICT
        seq != stream.size + 1L -> Admission.OUT_OF_ORDER
        id in storedIds -> Admission.CONFLICT
        else -> Admission.ACCEPT
    }

    private fun buildSnapshot() = GroupSnapshot(
        group = group,
        events = control.values.flatten(),
        states = states.toMap(),
    )

    private fun <T> List<T>.range(afterSeq: Long, limit: Int): List<T> =
        drop(afterSeq.coerceIn(0, size.toLong()).toInt()).take(limit)

    private enum class Admission { ACCEPT, DUPLICATE, OUT_OF_ORDER, CONFLICT }

    private class ApplyCounter {
        var newControl = 0
        var newStates = 0
        var newLocations = 0
        var duplicates = 0
        var outOfOrder = 0
        var conflicts = 0

        fun toResult() = ApplyResult(newControl, newStates, newLocations, duplicates, outOfOrder, conflicts)
    }
}
