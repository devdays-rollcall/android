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
 * На телефоне это `RoomMeshStore`, в тестах `InMemoryMeshStore` из `testFixtures`; обе реализации
 * проходят один контрактный тест `MeshStoreContractTest`. Свои записи пишут сюда `GroupActions` (события),
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
     * Своё новое событие или состояние. Срабатывает после фиксации транзакции в [appendOwnEvent] и [putOwnState],
     * но до возврата из метода. Если вызывающий сам обернул запись в транзакцию, как `GroupActions.createGroup`,
     * поток срабатывает до фиксации внешней транзакции, поэтому так делают, только пока движок не запущен. Подписан движок: по нему он сразу рассылает новое соседям. Точки и записи соседей
     * движок отдаёт в ответ на сводку, поэтому о них поток не сообщает.
     */
    val changes: Flow<StoreChange>

    /**
     * Группа, события и состояния. Сразу отдаёт текущий снимок и новый после каждого изменения
     * событий или состояний. Пока группы нет, ничего не отдаёт.
     */
    fun snapshot(): Flow<GroupSnapshot>
}

/** Что изменилось в хранилище: своё событие или своё состояние. */
enum class StoreChange { OWN_EVENT, OWN_STATE }
