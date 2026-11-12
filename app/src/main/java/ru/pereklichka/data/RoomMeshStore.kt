package ru.pereklichka.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import ru.pereklichka.data.db.AppDatabase
import ru.pereklichka.data.db.key
import ru.pereklichka.data.db.toEntity
import ru.pereklichka.data.db.toModel
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MeshStore] на Room, которым пользуется приложение на телефоне. Через него проходят все записи группы, свои
 * и пришедшие от соседей, а правила приёма те же, что в эталонном `InMemoryMeshStore`.
 *
 * Хранилище работает с активной группой из [GroupRepository.activeGroup]: читает и пишет только строки с её ключом
 * (`GroupInfo.key`) и от неё же берёт свой `member_id`. Прошлые маршруты лежат в тех же таблицах, но в обмен
 * не попадают. Строки базы переводит в модели и обратно `data/db/Mappers.kt`, причём `toEntity()` у точки сам
 * ставит `rejected`. Каждая запись идёт внутри `db.withTransaction`, а [changesFlow] срабатывает после её фиксации
 * (во вложенной транзакции раньше фиксации внешней, см. `MeshStore.changes`). Реализацию проверяет
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

    override suspend fun summary(): SyncSummary {
        val key = activeGroup().key
        return SyncSummary(
            control = db.controlEventDao().maxSeqByAuthor(key).associate { MemberId(it.author) to it.maxSeq },
            locations = db.locationDao().maxSeqByAuthor(key).associate { MemberId(it.author) to it.maxSeq },
            states = db.memberStateDao().getAll(key).associate { MemberId(it.memberId) to it.seq },
        )
    }

    override suspend fun controlEvents(author: MemberId, afterSeq: Long, limit: Int): List<Stamped<ControlEvent>> =
        db.controlEventDao().range(activeGroup().key, author.value, afterSeq, limit).map { it.toModel() }

    /** Точки с пометкой rejected тоже отдаются: соседям они нужны ради префикса. */
    override suspend fun locations(author: MemberId, afterSeq: Long, limit: Int): List<LocationPoint> =
        db.locationDao().range(activeGroup().key, author.value, afterSeq, limit).map { it.toModel() }

    override suspend fun statesNewerThan(known: Map<MemberId, Long>): List<Stamped<MemberState>> =
        db.memberStateDao().getAll(activeGroup().key).map { it.toModel() }
            .filter { it.value.seq > (known[it.value.member] ?: 0L) }

    override suspend fun apply(delta: IncomingDelta): ApplyResult {
        val group = activeGroup()
        return db.withTransaction {
            val counter = ApplyCounter()
            applyControl(delta.control, group, counter)
            applyStates(delta.states, group, counter)
            applyLocations(delta.locations, group, counter)
            counter.toResult()
        }
    }

    override suspend fun appendOwnEvent(draft: ControlEventDraft): ControlEvent {
        val group = activeGroup()
        val event = db.withTransaction {
            val seq = (db.controlEventDao().maxSeq(group.key, group.me.value) ?: 0L) + 1
            draft.toEvent(EventId.random(), group.me, seq, clock.wallMs()).also {
                db.controlEventDao().insert(Stamped.own(it, clock).toEntity(group.key))
            }
        }
        changesFlow.emit(StoreChange.OWN_EVENT)
        return event
    }

    override suspend fun appendOwnLocation(fix: Fix, batteryPercent: Int?): LocationPoint {
        val group = activeGroup()
        return db.withTransaction {
            val seq = (db.locationDao().maxSeq(group.key, group.me.value) ?: 0L) + 1
            fix.toLocationPoint(EventId.random(), group.me, seq, batteryPercent).also {
                db.locationDao().insert(it.toEntity(group.key))
            }
        }
    }

    override suspend fun putOwnState(draft: MemberStateDraft): MemberState {
        val group = activeGroup()
        val state = db.withTransaction {
            val seq = (db.memberStateDao().get(group.key, group.me.value)?.seq ?: 0L) + 1
            draft.toState(group.me, seq, clock.elapsedMs()).also {
                db.memberStateDao().upsert(Stamped.own(it, clock).toEntity(group.key))
            }
        }
        changesFlow.emit(StoreChange.OWN_STATE)
        return state
    }

    override fun snapshot(): Flow<GroupSnapshot> = db.groupDao().observeActive().flatMapLatest { entity ->
        if (entity == null) {
            emptyFlow()
        } else {
            val group = entity.toModel()
            combine(
                db.controlEventDao().observeAll(group.key),
                db.memberStateDao().observeAll(group.key),
            ) { eventEntities, stateEntities ->
                GroupSnapshot(
                    group = group,
                    events = eventEntities.map { it.toModel() },
                    states = stateEntities.map { it.toModel() }.associateBy { it.value.member },
                )
            }
        }
    }

    private suspend fun activeGroup(): GroupInfo = checkNotNull(groupRepository.activeGroup()) { "Нет активной группы" }

    /**
     * Принимает события из Delta. Принятая запись вставляется сразу, внутри транзакции [apply]: так `find` и `exists`
     * в [admit] видят и записи, принятые раньше в этой же Delta. Максимумы по авторам читаются один раз и дальше
     * ведутся в памяти. [applyLocations] устроен так же.
     */
    private suspend fun applyControl(incoming: List<Stamped<ControlEvent>>, group: GroupInfo, counter: ApplyCounter) {
        val dao = db.controlEventDao()
        val maxByAuthor = dao.maxSeqByAuthor(group.key).associateTo(HashMap()) { it.author to it.maxSeq }
        for (stamped in incoming.sortedWith(compareBy({ it.value.author.value }, { it.value.seq }))) {
            val event = stamped.value
            val maxSeq = maxByAuthor[event.author.value] ?: 0L
            val stored = suspend { dao.find(group.key, event.author.value, event.seq)?.id }
            when (admit(group, event.author, event.seq, event.id, maxSeq, stored)) {
                Admission.ACCEPT -> {
                    dao.insert(stamped.toEntity(group.key))
                    maxByAuthor[event.author.value] = event.seq
                    counter.newControl++
                }
                Admission.DUPLICATE -> counter.duplicates++
                Admission.OUT_OF_ORDER -> counter.outOfOrder++
                Admission.CONFLICT -> counter.conflicts++
            }
        }
    }

    private suspend fun applyLocations(incoming: List<LocationPoint>, group: GroupInfo, counter: ApplyCounter) {
        val dao = db.locationDao()
        val maxByAuthor = dao.maxSeqByAuthor(group.key).associateTo(HashMap()) { it.author to it.maxSeq }
        for (point in incoming.sortedWith(compareBy({ it.author.value }, { it.seq }))) {
            val maxSeq = maxByAuthor[point.author.value] ?: 0L
            val stored = suspend { dao.find(group.key, point.author.value, point.seq)?.id }
            when (admit(group, point.author, point.seq, point.id, maxSeq, stored)) {
                Admission.ACCEPT -> {
                    dao.insert(point.toEntity(group.key))
                    maxByAuthor[point.author.value] = point.seq
                    counter.newLocations++
                }
                Admission.DUPLICATE -> counter.duplicates++
                Admission.OUT_OF_ORDER -> counter.outOfOrder++
                Admission.CONFLICT -> counter.conflicts++
            }
        }
    }

    private suspend fun applyStates(incoming: List<Stamped<MemberState>>, group: GroupInfo, counter: ApplyCounter) {
        for (stamped in incoming) {
            val member = stamped.value.member
            val current = db.memberStateDao().get(group.key, member.value)?.seq ?: 0L
            when {
                stamped.value.seq <= current -> counter.duplicates++
                member == group.me -> counter.conflicts++
                else -> {
                    db.memberStateDao().upsert(stamped.toEntity(group.key))
                    counter.newStates++
                }
            }
        }
    }

    /**
     * Правило «ровно следующий номер» с проверками на конфликт, ветки идут в том же порядке, что в `InMemoryMeshStore`.
     * [storedId] читает id записи, которая уже стоит у автора под этим номером. Повтор id ищется только в группе
     * [group]: в прошлом маршруте та же запись лежит законно, если человек повторно вступил в ту же группу.
     */
    private suspend fun admit(
        group: GroupInfo,
        author: MemberId,
        seq: Long,
        id: EventId,
        maxSeq: Long,
        storedId: suspend () -> String?,
    ): Admission = when {
        seq < 1 -> Admission.OUT_OF_ORDER
        seq <= maxSeq -> if (storedId() == id.value) Admission.DUPLICATE else Admission.CONFLICT
        author == group.me -> Admission.CONFLICT
        seq != maxSeq + 1 -> Admission.OUT_OF_ORDER
        db.controlEventDao().exists(group.key, id.value) || db.locationDao().exists(group.key, id.value) -> Admission.CONFLICT
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
