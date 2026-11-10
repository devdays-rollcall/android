package ru.pereklichka.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.pereklichka.data.db.AppDatabase
import ru.pereklichka.data.db.ControlEventEntity
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

    private val mutex = Mutex()

    private val storedIds = HashSet<EventId>()

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

        val range: List<ControlEventEntity>  =  mutex.withLock {
            db.controlEventDao().range(author = author.toString(), afterSeq = afterSeq, limit = limit)
        }

        return range.map { it.toModel() }

    }

    override suspend fun locations(author: MemberId, afterSeq: Long, limit: Int): List<LocationPoint> {
        //  точки автора с номерами больше afterSeq по возрастанию, не больше limit.
        //  Точки с пометкой rejected тоже отдаются: соседям они нужны ради префикса.
        //  docs/team/vasilisa-service-storage.md
        val range = mutex.withLock { db.locationDao().range(author.toString(), afterSeq, limit) }

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

    private enum class Admission { ACCEPT, DUPLICATE, OUT_OF_ORDER, CONFLICT }
    private suspend fun me(): MemberId = groupRepository.activeGroup()?.me ?: throw IllegalStateException("no active group")


    private suspend fun applyLocation(locations: List<LocationPoint>){
    }
    private suspend fun applyState(states: List<Stamped<MemberState>>){
    }
    private suspend fun applyEvent(control: List<Stamped<ControlEvent>>){
    }

    override suspend fun apply(delta: IncomingDelta): ApplyResult {
        // TODO(Василиса): применить Delta одной транзакцией по правилам из 03, раздел «Применение Delta».
        //  03 значит docs/03-sync-protocol.md. Максимумы читать внутри транзакции, порядок проверок как в admit
        //  у InMemoryMeshStore.
        //  docs/team/vasilisa-service-storage.md
        throw NotImplementedError("TODO(Василиса): применить Delta одной транзакцией по правилам из 03, раздел «Применение Delta».")

    }

    override suspend fun appendOwnEvent(draft: ControlEventDraft): ControlEvent {
        // TODO(Василиса): своё событие с номером MAX(seq) + 1 в той же транзакции, затем OWN_EVENT в changesFlow.
        //  Готовое событие даёт draft.toEvent, отметку свежести Stamped.own.
        //  docs/team/vasilisa-service-storage.md
        //throw NotImplementedError("TODO(Василиса): своё событие с номером MAX(seq) + 1 в той же транзакции, затем OWN_EVENT в changesFlow.")

        val meId = me()

        val event = db.withTransaction {
            val maxSeq = db.controlEventDao().findMaxSeqByAuthor(meId.value)?.maxSeq ?: 0L

            val event = draft.toEvent(EventId.random(), meId, maxSeq + 1L, clock.wallMs())
            db.controlEventDao().insert( Stamped.own(event, clock).toEntity())
            storedIds += event.id
            //TODO
            //snapshotState.value = buildSnapshot()
            event
        }

        changesFlow.emit(StoreChange.OWN_EVENT)  // ← после коммита
        return event

    }

    override suspend fun appendOwnLocation(fix: Fix, batteryPercent: Int?): LocationPoint {
        //  своя точка с номером MAX(seq) + 1 в той же транзакции, затем OWN_LOCATION в changesFlow.
        //  Готовую точку даёт fix.toLocationPoint.
        //  docs/team/vasilisa-service-storage.md

        val meId = me()

        val point = db.withTransaction {
            val maxSeq = db.locationDao().findMaxSeqByAuthor(meId.value)?.maxSeq ?: 0L
            val point = fix.toLocationPoint(
                id = EventId.random(),
                author = meId,
                seq = maxSeq + 1L,
                batteryPercent = batteryPercent,
            )
            storedIds += point.id
            db.locationDao().insertAll(listOf(point.toEntity()))
            point                                   // ← возврат из withTransactio
        }

        changesFlow.emit(StoreChange.OWN_LOCATION)  // ← после коммита
        return point
    }

    override suspend fun putOwnState(draft: MemberStateDraft): MemberState {
        // TODO(Василиса): своё состояние со следующим номером состояния, затем OWN_STATE в changesFlow.
        //  Готовое состояние даёт draft.toState(me, seq, clock.elapsedMs()).
        //  docs/team/vasilisa-service-storage.md

        val meId = me()

        val state = db.withTransaction {
            val maxSeq = db.memberStateDao().get(meId.value)?.seq ?: 0L
            val state = draft.toState(meId, maxSeq, clock.elapsedMs())
            //todo вставка обновления
            //db.locationDao().upsert(state)
            state                                   // ← возврат из withTransactio
        }

        changesFlow.emit(StoreChange.OWN_LOCATION)  // ← после коммита
        return state

    }

    override fun snapshot(): Flow<GroupSnapshot> {
        // TODO(Василиса): группа, все события и состояния; новый снимок после каждого изменения событий или состояний.
        //  Удобно соединить groupDao().observe(), controlEventDao().observeAll() и memberStateDao().observeAll().
        //  docs/team/vasilisa-service-storage.md
        throw NotImplementedError("TODO(Василиса): группа, все события и состояния; новый снимок после каждого изменения событий или состояний.")
    }
}
