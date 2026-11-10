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
 * Хранит всё, что телефон знает о группе, и решает, какие записи от соседа можно принять.
 *
 * На телефоне это `RoomMeshStore`, в тестах [ru.pereklichka.domain.testing.InMemoryMeshStore]; обе реализации
 * проходят один контрактный тест `MeshStoreContractTest`. Своё в хранилище пишут `GroupActions` (события),
 * `TrackRecorder` (точки) и `SelfStateProducer` (состояние). [SyncEngine] берёт отсюда сводку и записи для
 * соседей и применяет их Delta, а `GroupViewProvider` строит из [snapshot] вид группы.
 *
 * Главный инвариант: по каждому автору хранится непрерывный префикс его потока событий и его потока точек.
 * Если есть запись с номером n, есть и все с 1 по n−1, и на этом держится сводка [summary]. Правила приёма
 * описаны в `docs/03-sync-protocol.md`, раздел «Применение Delta». Все операции записи выполняются по одной:
 * две [apply] от разных соседей не перемешиваются.
 */
interface MeshStore {

    /** Сводка для [Hello]: последний номер в префиксе каждого автора и номер каждого состояния. */
    suspend fun summary(): SyncSummary

    /** События автора с номерами больше [afterSeq], по возрастанию номера, не больше [limit]. */
    suspend fun controlEvents(author: MemberId, afterSeq: Long, limit: Int): List<Stamped<ControlEvent>>

    /** Точки автора с номерами больше [afterSeq], по возрастанию номера, не больше [limit]. */
    suspend fun locations(author: MemberId, afterSeq: Long, limit: Int): List<LocationPoint>

    /** Состояния, номер которых больше, чем в [known]; участника, которого нет в [known], отдаёт всегда. */
    suspend fun statesNewerThan(known: Map<MemberId, Long>): List<Stamped<MemberState>>

    /**
     * Применяет записи от соседа одной транзакцией и читает текущие максимумы внутри неё. Запись принимается,
     * только если встаёт ровно следующей в префикс автора. Записи с плохим содержимым сохраняются с пометкой,
     * чтобы не разорвать префикс.
     */
    suspend fun apply(delta: IncomingDelta): ApplyResult

    /** Записывает своё событие и возвращает его с выданным id и номером. Номер выдаётся в той же транзакции. */
    suspend fun appendOwnEvent(draft: ControlEventDraft): ControlEvent

    /**
     * Записывает свою точку трека и возвращает её. Номер выдаётся в той же транзакции.
     * @param batteryPercent заряд в процентах, 0..100, или null, если неизвестен.
     */
    suspend fun appendOwnLocation(fix: Fix, batteryPercent: Int?): LocationPoint

    /** Записывает своё состояние со следующим номером состояния. */
    suspend fun putOwnState(draft: MemberStateDraft): MemberState

    /**
     * Что изменилось. Срабатывает после фиксации транзакции, но до возврата из метода записи, и только если
     * что-то действительно добавилось. Подписан движок: по нему он рассылает своё новое соседям.
     */
    val changes: Flow<StoreChange>

    /**
     * Группа, события и состояния. Сразу отдаёт текущий снимок и новый после каждого изменения
     * событий или состояний. Пока группы нет, ничего не отдаёт.
     */
    fun snapshot(): Flow<GroupSnapshot>
}

/** Что изменилось в хранилище: своё (`OWN_*`) или пришедшее от соседа (`REMOTE_*`) событие, состояние или точка. */
enum class StoreChange { OWN_EVENT, OWN_STATE, OWN_LOCATION, REMOTE_EVENTS, REMOTE_STATES, REMOTE_LOCATIONS }
