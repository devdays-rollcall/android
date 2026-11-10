package ru.pereklichka.actions

import ru.pereklichka.data.GroupRepository
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
 * Действия человека. Каждое превращается в событие через `MeshStore.appendOwnEvent`, движок сразу рассылает его
 * соседям. Вызывает их `GroupViewModel`; запуск службы после создания группы и вступления тоже на нём.
 *
 * Действия руководителя проверяют роль: интерфейс не показывает их участнику, поэтому вызов от участника
 * считается ошибкой и бросает [IllegalStateException].
 */
@Singleton
class GroupActions @Inject constructor(
    private val repository: GroupRepository,
    private val store: MeshStore,
    private val viewProvider: GroupViewProvider,
) {
    /** Новая группа, где я руководитель, и моё событие JOINED. */
    suspend fun createGroup(groupName: String, myName: String): GroupInfo {
        val group = repository.createGroup(groupName)
        store.appendOwnEvent(ControlEventDraft(EventType.JOINED, name = myName.trim()))
        return group
    }

    /** Вступление по QR. Повторное вступление в ту же группу не пишет второй JOINED. */
    suspend fun joinGroup(qr: QrPayload, myName: String) {
        val group = repository.join(qr)
        // JOINED проверяется по хранилищу, а не по тому, была ли группа: так он допишется и после сбоя на середине.
        if (store.summary().control[group.me] == null) {
            store.appendOwnEvent(ControlEventDraft(EventType.JOINED, name = myName.trim()))
        }
    }

    suspend fun startRoute() = appendAsLeader(ControlEventDraft(EventType.ROUTE_STARTED))

    suspend fun finishRoute() = appendAsLeader(ControlEventDraft(EventType.ROUTE_FINISHED))

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

    suspend fun raiseAlarm(target: MemberId) {
        store.appendOwnEvent(ControlEventDraft(EventType.ALARM, target = target))
    }

    suspend fun sos() {
        store.appendOwnEvent(ControlEventDraft(EventType.SOS))
    }

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
