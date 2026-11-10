package ru.pereklichka.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import ru.pereklichka.data.db.AppDatabase
import ru.pereklichka.domain.model.ControlEvent
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
        // TODO(Василиса): сводка: максимальный номер события и точки по каждому автору и номер каждого состояния.
        //  Запросы maxSeqByAuthor уже есть в DAO.
        //  docs/team/vasilisa-service-storage.md
        throw NotImplementedError("TODO(Василиса): сводка: максимальный номер события и точки по каждому автору и номер каждого состояния.")
    }

    override suspend fun controlEvents(author: MemberId, afterSeq: Long, limit: Int): List<Stamped<ControlEvent>> {
        // TODO(Василиса): события автора с номерами больше afterSeq по возрастанию, не больше limit.
        //  docs/team/vasilisa-service-storage.md
        throw NotImplementedError("TODO(Василиса): события автора с номерами больше afterSeq по возрастанию, не больше limit.")
    }

    override suspend fun locations(author: MemberId, afterSeq: Long, limit: Int): List<LocationPoint> {
        // TODO(Василиса): точки автора с номерами больше afterSeq по возрастанию, не больше limit.
        //  Точки с пометкой rejected тоже отдаются: соседям они нужны ради префикса.
        //  docs/team/vasilisa-service-storage.md
        throw NotImplementedError("TODO(Василиса): точки автора с номерами больше afterSeq по возрастанию, не больше limit.")
    }

    override suspend fun statesNewerThan(known: Map<MemberId, Long>): List<Stamped<MemberState>> {
        // TODO(Василиса): состояния, номер которых больше, чем в known; участника не из known отдавать всегда.
        //  docs/team/vasilisa-service-storage.md
        throw NotImplementedError("TODO(Василиса): состояния, номер которых больше, чем в known; участника не из known отдавать всегда.")
    }

    override suspend fun apply(delta: IncomingDelta): ApplyResult {
        // TODO(Василиса): применить Delta одной транзакцией по правилам из 03, раздел «Применение Delta».
        //  Правила в docs/03-sync-protocol.md. Максимумы читать внутри транзакции, порядок проверок как в admit
        //  у InMemoryMeshStore.
        //  docs/team/vasilisa-service-storage.md
        throw NotImplementedError("TODO(Василиса): применить Delta одной транзакцией по правилам из 03, раздел «Применение Delta».")
    }

    override suspend fun appendOwnEvent(draft: ControlEventDraft): ControlEvent {
        // TODO(Василиса): своё событие с номером MAX(seq) + 1 в той же транзакции, затем OWN_EVENT в changesFlow.
        //  Готовое событие даёт draft.toEvent, отметку свежести Stamped.own.
        //  docs/team/vasilisa-service-storage.md
        throw NotImplementedError("TODO(Василиса): своё событие с номером MAX(seq) + 1 в той же транзакции, затем OWN_EVENT в changesFlow.")
    }

    override suspend fun appendOwnLocation(fix: Fix, batteryPercent: Int?): LocationPoint {
        // TODO(Василиса): своя точка с номером MAX(seq) + 1 в той же транзакции, затем OWN_LOCATION в changesFlow.
        //  Готовую точку даёт fix.toLocationPoint.
        //  docs/team/vasilisa-service-storage.md
        throw NotImplementedError("TODO(Василиса): своя точка с номером MAX(seq) + 1 в той же транзакции, затем OWN_LOCATION в changesFlow.")
    }

    override suspend fun putOwnState(draft: MemberStateDraft): MemberState {
        // TODO(Василиса): своё состояние со следующим номером состояния, затем OWN_STATE в changesFlow.
        //  Готовое состояние даёт draft.toState(me, seq, clock.elapsedMs()).
        //  docs/team/vasilisa-service-storage.md
        throw NotImplementedError("TODO(Василиса): своё состояние со следующим номером состояния, затем OWN_STATE в changesFlow.")
    }

    override fun snapshot(): Flow<GroupSnapshot> {
        // TODO(Василиса): группа, все события и состояния; новый снимок после каждого изменения событий или состояний.
        //  Удобно соединить groupDao().observe(), controlEventDao().observeAll() и memberStateDao().observeAll().
        //  docs/team/vasilisa-service-storage.md
        throw NotImplementedError("TODO(Василиса): группа, все события и состояния; новый снимок после каждого изменения событий или состояний.")
    }
}
