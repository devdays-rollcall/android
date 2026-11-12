package ru.pereklichka.actions

import androidx.room.withTransaction
import ru.pereklichka.data.GroupRepository
import ru.pereklichka.data.db.AppDatabase
import ru.pereklichka.domain.model.ControlEvent
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.EventType
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.QrPayload
import ru.pereklichka.domain.model.ResolveKind
import ru.pereklichka.domain.sync.ControlEventDraft
import ru.pereklichka.domain.sync.MeshStore
import ru.pereklichka.status.GroupViewProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Превращает действия человека (вступить, «Выходим», тревога, ответ на перекличку) в события группы.
 *
 * Каждое действие пишет своё событие через [MeshStore.appendOwnEvent], и `SyncEngine` сразу рассылает его соседям.
 * Вызывает действия `GroupViewModel`, он же после создания группы и вступления запускает службу.
 *
 * Действия руководителя проверяют роль: интерфейс не показывает их участнику, поэтому вызов от участника
 * считается ошибкой и бросает [IllegalStateException].
 */
@Singleton
class GroupActions @Inject constructor(
    private val repository: GroupRepository,
    private val store: MeshStore,
    private val viewProvider: GroupViewProvider,
    private val db: AppDatabase,
) {
    /**
     * Создаёт новую группу, где я руководитель, и пишет моё событие JOINED. Прежняя группа становится прошлым
     * маршрутом. Группа и JOINED пишутся одной транзакцией: если процесс умрёт посередине, не останется группы,
     * в которой у руководителя нет имени. Транзакция `RoomMeshStore` при этом вкладывается в общую.
     */
    suspend fun createGroup(groupName: String, myName: String): GroupInfo {
        require(myName.isNotBlank()) { "Пустое имя" }
        return db.withTransaction {
            repository.createGroup(groupName).also {
                store.appendOwnEvent(ControlEventDraft(EventType.JOINED, name = myName.trim()))
            }
        }
    }

    /**
     * Вступает в группу по QR и пишет JOINED. Повторное вступление в ту же группу второй JOINED не пишет. JOINED
     * ищется в хранилище, поэтому если прошлое вступление прервалось после записи группы, он допишется сейчас.
     */
    suspend fun joinGroup(qr: QrPayload, myName: String) {
        require(myName.isNotBlank()) { "Пустое имя" }
        val group = repository.join(qr)
        if (store.summary().control[group.me] == null) {
            store.appendOwnEvent(ControlEventDraft(EventType.JOINED, name = myName.trim()))
        }
    }

    /** «Выходим»: маршрут начат (ROUTE_STARTED). Только руководитель. */
    suspend fun startRoute() = appendAsLeader(ControlEventDraft(EventType.ROUTE_STARTED))

    /** Маршрут завершён (ROUTE_FINISHED). Только руководитель. */
    suspend fun finishRoute() = appendAsLeader(ControlEventDraft(EventType.ROUTE_FINISHED))

    /**
     * Участник выходит из группы (LEFT): у остальных он пропадёт из списка вместе с тревогами о нём. Возвращает
     * событие, чтобы служба дождалась его подтверждения соседями. Руководитель не выходит, он завершает маршрут.
     */
    suspend fun announceLeaving(): ControlEvent {
        val group = checkNotNull(repository.activeGroup()) { "Нет активной группы" }
        check(group.me != group.leaderId) { "Руководитель не выходит из группы, он завершает маршрут" }
        return store.appendOwnEvent(ControlEventDraft(EventType.LEFT))
    }

    /** Новая перекличка (ROLLCALL) с окном 3 минуты. Только руководитель. */
    suspend fun startRollcall() = appendAsLeader(ControlEventDraft(EventType.ROLLCALL))

    /** «Я здесь» на перекличку [rollcallId]. */
    suspend fun answerRollcall(rollcallId: EventId) {
        store.appendOwnEvent(ControlEventDraft(EventType.ROLLCALL_ANSWER, refs = listOf(rollcallId)))
    }

    /** Отлучка на [minutes] минут; до её конца «Не слышно» у меня не появится. */
    suspend fun announceAway(minutes: Int) {
        require(minutes > 0) { "Отлучка на $minutes мин" }
        store.appendOwnEvent(ControlEventDraft(EventType.AWAY, durationMs = minutes * 60_000L))
    }

    /** Вернулся раньше: отлучка с длительностью 0 отменяет прежнюю. */
    suspend fun cancelAway() {
        store.appendOwnEvent(ControlEventDraft(EventType.AWAY, durationMs = 0))
    }

    /** Тревога о другом участнике [target]. О себе вместо неё отправляют [sos]. */
    suspend fun raiseAlarm(target: MemberId) {
        store.appendOwnEvent(ControlEventDraft(EventType.ALARM, target = target))
    }

    /** SOS: тревога о себе, автор события и есть тот, о ком тревога. Возвращает событие для отправки на сервер. */
    suspend fun sos(): ControlEvent = store.appendOwnEvent(ControlEventDraft(EventType.SOS))

    /**
     * Снимает тревоги о [target], которые сейчас видит человек: ссылки берутся из текущего `GroupView`.
     * Тревога, которая ещё не дошла до телефона, остаётся активной, и снять её придётся отдельно.
     * Если активных тревог о [target] нет, событие не пишется.
     */
    suspend fun resolve(target: MemberId, kind: ResolveKind) {
        val refs = viewProvider.view.value?.alarms.orEmpty().filter { it.target == target }.map { it.id }
        if (refs.isEmpty()) return
        store.appendOwnEvent(ControlEventDraft(EventType.RESOLVE, target = target, refs = refs, resolveKind = kind))
    }

    private suspend fun appendAsLeader(draft: ControlEventDraft) {
        val group = checkNotNull(repository.activeGroup()) { "Нет активной группы" }
        check(group.me == group.leaderId) { "${draft.type} может создать только руководитель" }
        store.appendOwnEvent(draft)
    }
}
