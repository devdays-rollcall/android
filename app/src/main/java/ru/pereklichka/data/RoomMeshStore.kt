package ru.pereklichka.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import ru.pereklichka.data.db.AppDatabase
import ru.pereklichka.data.db.ControlEventEntity
import ru.pereklichka.data.db.LocationPointEntity
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
 * `data/db/Mappers.kt`, причём `toEntity()` у точки сам ставит `rejected`. Каждая запись должна идти внутри
 * `db.withTransaction`, а [changesFlow] срабатывать после её фиксации. Реализацию проверяет
 * `RoomMeshStoreContractTest`.
 *
 * Задача: docs/team/vasilisa-service-storage.md
 */
@Singleton
class RoomMeshStore @Inject constructor(
    private val db: AppDatabase,
    private val groupRepository: GroupRepository,
    private val clock: Clock,
) : MeshStore {

    private val changesFlow = MutableSharedFlow<StoreChange>(extraBufferCapacity = 64)

    override val changes: Flow<StoreChange> = changesFlow.asSharedFlow()

    override suspend fun summary(): SyncSummary {
        //  сводка: максимальный номер события и точки по каждому автору и номер каждого состояния.
        //  Запросы maxSeqByAuthor уже есть в DAO.
        //  docs/team/vasilisa-service-storage.md

        val controlMax = db.controlEventDao().maxSeqByAuthor().associate { MemberId(it.author) to it.maxSeq }
        val location = db.locationDao().maxSeqByAuthor().associate { MemberId(it.author) to it.maxSeq }
        val stateMax = db.memberStateDao().observeAll().first().associate { MemberId(it.memberId) to it.seq }

        return SyncSummary(
                control = controlMax,
                locations = location,
                states = stateMax
            )
    }

    override suspend fun controlEvents(author: MemberId, afterSeq: Long, limit: Int): List<Stamped<ControlEvent>> {
        //  события автора с номерами больше afterSeq по возрастанию, не больше limit.
        //  docs/team/vasilisa-service-storage.md

        val range: List<ControlEventEntity>  = db.controlEventDao().range(author = author.value, afterSeq = afterSeq, limit = limit)

        return range.map { it.toModel() }

    }

    override suspend fun locations(author: MemberId, afterSeq: Long, limit: Int): List<LocationPoint> {
        //  точки автора с номерами больше afterSeq по возрастанию, не больше limit.
        //  Точки с пометкой rejected тоже отдаются: соседям они нужны ради префикса.
        //  docs/team/vasilisa-service-storage.md
        val range = db.locationDao().range(author.value, afterSeq, limit)

        return range.map { it.toModel() }
    }

    override suspend fun statesNewerThan(known: Map<MemberId, Long>): List<Stamped<MemberState>> {
        //  состояния, номер которых больше, чем в known; участника не из known отдавать всегда.
        //  docs/team/vasilisa-service-storage.md
        val allStates = db.memberStateDao().observeAll().first().mapNotNull { entity ->
            val stamped = entity.toModel()
            stamped.takeIf { it.value.seq > (known[it.value.member] ?: 0L) }
        }
        return allStates
    }

    private suspend fun me(): MemberId = groupRepository.activeGroup()?.me ?: throw IllegalStateException("no active group")

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

    private suspend fun admit(
        author: MemberId,
        seq: Long,
        id: EventId,
        me: MemberId,
        maxSeq: Long,
        isEvent: Boolean,
    ): Admission = when {
        seq < 1 -> Admission.OUT_OF_ORDER
        seq <= maxSeq -> {
            val existingId = if (isEvent)
                db.controlEventDao().find(author.value, seq)?.id
            else
                db.locationDao().find(author.value, seq)?.id
            if (existingId == id.value) Admission.DUPLICATE else Admission.CONFLICT
        }
        author == me -> Admission.CONFLICT
        seq != maxSeq + 1L -> Admission.OUT_OF_ORDER
        db.controlEventDao().exists(id.value) || db.locationDao().exists(id.value) -> Admission.CONFLICT
        else -> Admission.ACCEPT
    }

    private suspend fun applyLocation(locations: List<LocationPoint>,
                                      me: MemberId,
                                      counter: ApplyCounter,){
        val maxByAuthor = db.locationDao().maxSeqByAuthor()
            .associate { it.author to it.maxSeq }
            .toMutableMap()
        val accepted = mutableListOf<LocationPointEntity>()

        for (point in locations.sortedWith(compareBy({ it.author.value }, { it.seq }))) {
            val maxSeq = maxByAuthor[point.author.value] ?: 0L
            when (admit(point.author, point.seq, point.id, me, maxSeq, isEvent = false)) {
                Admission.ACCEPT -> {
                    accepted += point.toEntity()   // toEntity сам ставит rejected
                    maxByAuthor[point.author.value] = point.seq   // локально обновляем максимум
                    counter.newLocations++
                }
                Admission.DUPLICATE -> counter.duplicates++
                Admission.OUT_OF_ORDER -> counter.outOfOrder++
                Admission.CONFLICT -> counter.conflicts++
            }
        }
        db.locationDao().insertAll(accepted)
    }
    private suspend fun applyState(states: List<Stamped<MemberState>>,
                                   me: MemberId,
                                   counter: ApplyCounter,){
        for (stamped in states) {
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
    private suspend fun applyEvent(control: List<Stamped<ControlEvent>>,
                                   me: MemberId,
                                   counter: ApplyCounter,){
        for (stamped in control.sortedWith(compareBy({ it.value.author.value }, { it.value.seq }))) {
            val e = stamped.value
            val maxSeq = db.controlEventDao().maxSeqByAuthor()
                .firstOrNull { it.author == e.author.value }?.maxSeq ?: 0L
            when (admit(e.author, e.seq, e.id, me, maxSeq, isEvent = true)) {
                Admission.ACCEPT -> {
                    db.controlEventDao().insert(stamped.toEntity())
                    counter.newControl++
                }
                Admission.DUPLICATE -> counter.duplicates++
                Admission.OUT_OF_ORDER -> counter.outOfOrder++
                Admission.CONFLICT -> counter.conflicts++
            }
        }
    }

    override suspend fun apply(delta: IncomingDelta): ApplyResult {
        //  применить Delta одной транзакцией по правилам из 03, раздел «Применение Delta».
        //  03 значит docs/03-sync-protocol.md. Максимумы читать внутри транзакции, порядок проверок как в admit
        //  у InMemoryMeshStore.
        //  docs/team/vasilisa-service-storage.md
        val meId = me()
        val result = db.withTransaction {
            val counter = ApplyCounter()
            applyEvent(delta.control, meId, counter)
            applyState(delta.states, meId, counter)
            applyLocation(delta.locations, meId, counter)
            counter.toResult()
        }
        if (result.newControl > 0) changesFlow.emit(StoreChange.REMOTE_EVENTS)
        if (result.newStates > 0) changesFlow.emit(StoreChange.REMOTE_STATES)
        if (result.newLocations > 0) changesFlow.emit(StoreChange.REMOTE_LOCATIONS)
        return result
    }

    override suspend fun appendOwnEvent(draft: ControlEventDraft): ControlEvent {
        //  своё событие с номером MAX(seq) + 1 в той же транзакции, затем OWN_EVENT в changesFlow.
        //  Готовое событие даёт draft.toEvent, отметку свежести Stamped.own.
        //  docs/team/vasilisa-service-storage.md


        val meId = me()

        val event = db.withTransaction {
            val maxSeq = db.controlEventDao().findMaxSeqByAuthor(meId.value)?.maxSeq ?: 0L

            val event = draft.toEvent(EventId.random(), meId, maxSeq + 1L, clock.wallMs())
            db.controlEventDao().insert( Stamped.own(event, clock).toEntity())
            event
        }

        changesFlow.emit(StoreChange.OWN_EVENT)  // ← после коммита
        return event

    }

    override suspend fun appendOwnLocation(fix: Fix, batteryPercent: Int?): LocationPoint {
        val point = db.withTransaction {
            val group = checkNotNull(groupRepository.activeGroup())
            val seq = (db.locationDao().maxSeq(group.me.value) ?: 0) + 1
            fix.toLocationPoint(EventId.random(), group.me, seq, batteryPercent).also {
                db.locationDao().insertAll(listOf(it.toEntity()))
            }
        }
        changesFlow.emit(StoreChange.OWN_LOCATION)
        return point
    }

    override suspend fun putOwnState(draft: MemberStateDraft): MemberState {
        //  своё состояние со следующим номером состояния, затем OWN_STATE в changesFlow.
        //  Готовое состояние даёт draft.toState(me, seq, clock.elapsedMs()).
        //  docs/team/vasilisa-service-storage.md

        val meId = me()

        val state = db.withTransaction {
            val maxSeq = db.memberStateDao().get(meId.value)?.seq ?: 0L
            val state = draft.toState(meId, maxSeq + 1, clock.elapsedMs())

            db.memberStateDao().upsert(Stamped.own(state, clock).toEntity())
            state
        }

        changesFlow.emit(StoreChange.OWN_STATE)
        return state

    }

    override fun snapshot(): Flow<GroupSnapshot> {
        //  группа, все события и состояния; новый снимок после каждого изменения событий или состояний.
        //  Удобно соединить groupDao().observe(), controlEventDao().observeAll() и memberStateDao().observeAll().
        //  docs/team/vasilisa-service-storage.md
        return combine(
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
    }
}
