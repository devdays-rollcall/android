package ru.pereklichka.domain.sync

import kotlinx.coroutines.flow.Flow
import ru.pereklichka.domain.model.ControlEvent
import ru.pereklichka.domain.model.Fix
import ru.pereklichka.domain.model.GroupSnapshot
import ru.pereklichka.domain.model.LocationPoint
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.MemberState
import ru.pereklichka.domain.time.Stamped

/**
 * Хранилище всего, что телефон знает о группе. На телефоне это `RoomMeshStore`, в тестах `InMemoryMeshStore`;
 * оба проходят один контрактный тест `MeshStoreContractTest`.
 *
 * Главный инвариант: по каждому автору хранится непрерывный префикс его событий и его точек.
 * Если есть запись с номером n, есть и все с 1 по n−1. На нём держится сводка [summary].
 * Правила приёма описаны в `03-sync-protocol.md`, раздел «Применение Delta».
 *
 * Все операции записи выполняются по одной: две [apply] от разных соседей не перемешиваются.
 */
interface MeshStore {

    /** Сводка для Hello. */
    suspend fun summary(): SyncSummary

    /** События автора с номерами больше [afterSeq], по возрастанию номера, не больше [limit]. */
    suspend fun controlEvents(author: MemberId, afterSeq: Long, limit: Int): List<Stamped<ControlEvent>>

    /** Точки автора с номерами больше [afterSeq], по возрастанию номера, не больше [limit]. */
    suspend fun locations(author: MemberId, afterSeq: Long, limit: Int): List<LocationPoint>

    /** Состояния, номер которых больше, чем в [known]; участника, которого нет в [known], отдаёт всегда. */
    suspend fun statesNewerThan(known: Map<MemberId, Long>): List<Stamped<MemberState>>

    /**
     * Применяет записи от соседа одной транзакцией. Текущие максимумы читаются внутри неё.
     * Запись принимается, только если встаёт ровно следующей в префикс автора; записи с плохим
     * содержимым сохраняются с пометкой, чтобы не разорвать префикс.
     */
    suspend fun apply(delta: IncomingDelta): ApplyResult

    /** Записывает своё событие. Номер выдаётся внутри той же транзакции, что и запись. */
    suspend fun appendOwnEvent(draft: ControlEventDraft): ControlEvent

    /** Записывает свою точку трека. Номер выдаётся внутри той же транзакции, что и запись. */
    suspend fun appendOwnLocation(fix: Fix, batteryPercent: Int?): LocationPoint

    /** Записывает своё состояние со следующим номером состояния. */
    suspend fun putOwnState(draft: MemberStateDraft): MemberState

    /**
     * Что изменилось. Срабатывает после фиксации транзакции, но до возврата из метода записи,
     * и только если что-то действительно добавилось. Подписаны движок (рассылка своего нового)
     * и `GroupViewProvider` (пересчёт статусов).
     */
    val changes: Flow<StoreChange>

    /**
     * Группа, события и состояния. Сразу отдаёт текущий снимок и новый после каждого изменения
     * событий или состояний. Пока группы нет, ничего не отдаёт.
     */
    fun snapshot(): Flow<GroupSnapshot>
}

/** Вид изменения в хранилище: своё или пришедшее от соседа, событие, состояние или точка. */
enum class StoreChange { OWN_EVENT, OWN_STATE, OWN_LOCATION, REMOTE_EVENTS, REMOTE_STATES, REMOTE_LOCATIONS }
