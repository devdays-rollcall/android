package ru.pereklichka.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import ru.pereklichka.data.db.AppDatabase
import ru.pereklichka.data.db.toEntity
import ru.pereklichka.data.db.toModel
import ru.pereklichka.domain.model.ControlEvent
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.Fix
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MeshStore] на Room, которым пользуется приложение на телефоне. Через него проходят все записи группы, свои
 * и пришедшие от соседей, а правила приёма те же, что в эталонном `InMemoryMeshStore`.
 *
 * Свой `member_id` берётся из [GroupRepository.activeGroup]. Строки базы переводит в модели и обратно
 * `data/db/Mappers.kt`, причём `toEntity()` у точки сам ставит `rejected`. Каждая запись идёт внутри
 * `db.withTransaction`, а [changesFlow] срабатывает после её фиксации. Реализацию проверяет
 * `RoomMeshStoreContractTest`.
 */
@Singleton
class RoomMeshStore @Inject constructor(
    private val db: AppDatabase,
    private val groupRepository: GroupRepository,
    private val clock: Clock,
) : MeshStore {

    private val changesFlow = MutableSharedFlow<StoreChange>(extraBufferCapacity = 64)

    override val changes: Flow<StoreChange> = changesFlow.asSharedFlow()

    override suspend fun summary(): SyncSummary = SyncSummary(
        control = db.controlEventDao().maxSeqByAuthor().associate { MemberId(it.author) to it.maxSeq },
        locations = db.locationDao().maxSeqByAuthor().associate { MemberId(it.author) to it.maxSeq },
        states = db.memberStateDao().getAll().associate { MemberId(it.memberId) to it.seq },
    )

    override suspend fun controlEvents(author: MemberId, afterSeq: Long, limit: Int): List<Stamped<ControlEvent>> =
        db.controlEventDao().range(author.value, afterSeq, limit).map { it.toModel() }

    /** Точки с пометкой rejected тоже отдаются: соседям они нужны ради префикса. */
    override suspend fun locations(author: MemberId, afterSeq: Long, limit: Int): List<LocationPoint> =
        db.locationDao().range(author.value, afterSeq, limit).map { it.toModel() }

    override suspend fun statesNewerThan(known: Map<MemberId, Long>): List<Stamped<MemberState>> =
        db.memberStateDao().getAll().map { it.toModel() }.filter { it.value.seq > (known[it.value.member] ?: 0L) }

    override suspend fun apply(delta: IncomingDelta): ApplyResult {
        val me = me()
        return db.withTransaction {
            val counter = ApplyCounter()
            applyControl(delta.control, me, counter)
            applyStates(delta.states, me, counter)
            applyLocations(delta.locations, me, counter)
            counter.toResult()
        }
    }

    override suspend fun appendOwnEvent(draft: ControlEventDraft): ControlEvent {
        val me = me()
        val event = db.withTransaction {
            val seq = (db.controlEventDao().maxSeq(me.value) ?: 0L) + 1
            draft.toEvent(EventId.random(), me, seq, clock.wallMs()).also {
                db.controlEventDao().insert(Stamped.own(it, clock).toEntity())
            }
        }
        changesFlow.emit(StoreChange.OWN_EVENT)
        return event
    }

    override suspend fun appendOwnLocation(fix: Fix, batteryPercent: Int?): LocationPoint {
        val me = me()
        return db.withTransaction {
            val seq = (db.locationDao().maxSeq(me.value) ?: 0L) + 1
            fix.toLocationPoint(EventId.random(), me, seq, batteryPercent).also {
                db.locationDao().insert(it.toEntity())
            }
        }
    }

    override suspend fun putOwnState(draft: MemberStateDraft): MemberState {
        val me = me()
        val state = db.withTransaction {
            val seq = (db.memberStateDao().get(me.value)?.seq ?: 0L) + 1
            draft.toState(me, seq, clock.elapsedMs()).also {
                db.memberStateDao().upsert(Stamped.own(it, clock).toEntity())
            }
        }
        changesFlow.emit(StoreChange.OWN_STATE)
        return state
    }

    override fun snapshot(): Flow<GroupSnapshot> = combine(
        db.groupDao().observe(),
        db.controlEventDao().observeAll(),
        db.memberStateDao().observeAll(),
    ) { groupEntity, eventEntities, stateEntities ->
        groupEntity?.let { group ->
            GroupSnapshot(
                group = group.toModel(),
                events = eventEntities.map { it.toModel() },
                states = stateEntities.map { it.toModel() }.associateBy { it.value.member },
            )
        }
    }.filterNotNull()

    private suspend fun me(): MemberId = checkNotNull(groupRepository.activeGroup()) { "Нет активной группы" }.me

    // Принятая запись вставляется сразу, внутри транзакции apply: так find и exists в admit видят и записи,
    // принятые раньше в этой же Delta. Максимумы по авторам читаются один раз и дальше ведутся в памяти.
    private suspend fun applyControl(incoming: List<Stamped<ControlEvent>>, me: MemberId, counter: ApplyCounter) {
        val maxByAuthor = db.controlEventDao().maxSeqByAuthor().associateTo(HashMap()) { it.author to it.maxSeq }
        for (stamped in incoming.sortedWith(compareBy({ it.value.author.value }, { it.value.seq }))) {
            val event = stamped.value
            val maxSeq = maxByAuthor[event.author.value] ?: 0L
            val stored = suspend { db.controlEventDao().find(event.author.value, event.seq)?.id }
            when (admit(event.author, event.seq, event.id, me, maxSeq, stored)) {
                Admission.ACCEPT -> {
                    db.controlEventDao().insert(stamped.toEntity())
                    maxByAuthor[event.author.value] = event.seq
                    counter.newControl++
                }
                Admission.DUPLICATE -> counter.duplicates++
                Admission.OUT_OF_ORDER -> counter.outOfOrder++
                Admission.CONFLICT -> counter.conflicts++
            }
        }
    }

    private suspend fun applyLocations(incoming: List<LocationPoint>, me: MemberId, counter: ApplyCounter) {
        val maxByAuthor = db.locationDao().maxSeqByAuthor().associateTo(HashMap()) { it.author to it.maxSeq }
        for (point in incoming.sortedWith(compareBy({ it.author.value }, { it.seq }))) {
            val maxSeq = maxByAuthor[point.author.value] ?: 0L
            val stored = suspend { db.locationDao().find(point.author.value, point.seq)?.id }
            when (admit(point.author, point.seq, point.id, me, maxSeq, stored)) {
                Admission.ACCEPT -> {
                    db.locationDao().insert(point.toEntity())
                    maxByAuthor[point.author.value] = point.seq
                    counter.newLocations++
                }
                Admission.DUPLICATE -> counter.duplicates++
                Admission.OUT_OF_ORDER -> counter.outOfOrder++
                Admission.CONFLICT -> counter.conflicts++
            }
        }
    }

    private suspend fun applyStates(incoming: List<Stamped<MemberState>>, me: MemberId, counter: ApplyCounter) {
        for (stamped in incoming) {
            val member = stamped.value.member
            val current = db.memberStateDao().get(member.value)?.seq ?: 0L
            when {
                stamped.value.seq <= current -> counter.duplicates++
                member == me -> counter.conflicts++
                else -> {
                    db.memberStateDao().upsert(stamped.toEntity())
                    counter.newStates++
                }
            }
        }
    }

    /**
     * Правило «ровно следующий номер» с проверками на конфликт, ветки идут в том же порядке, что в `InMemoryMeshStore`.
     * [storedId] читает id записи, которая уже стоит у автора под этим номером.
     */
    private suspend fun admit(
        author: MemberId,
        seq: Long,
        id: EventId,
        me: MemberId,
        maxSeq: Long,
        storedId: suspend () -> String?,
    ): Admission = when {
        seq < 1 -> Admission.OUT_OF_ORDER
        seq <= maxSeq -> if (storedId() == id.value) Admission.DUPLICATE else Admission.CONFLICT
        author == me -> Admission.CONFLICT
        seq != maxSeq + 1 -> Admission.OUT_OF_ORDER
        db.controlEventDao().exists(id.value) || db.locationDao().exists(id.value) -> Admission.CONFLICT
        else -> Admission.ACCEPT
    }

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
