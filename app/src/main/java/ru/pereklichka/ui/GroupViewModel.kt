package ru.pereklichka.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.pereklichka.actions.GroupActions
import ru.pereklichka.data.AppSettings
import ru.pereklichka.data.GroupRepository
import ru.pereklichka.di.FakeGroupData
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.QrPayload
import ru.pereklichka.domain.model.ResolveKind
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.service.RouteService
import ru.pereklichka.status.GroupViewProvider
import ru.pereklichka.upload.UploadScheduler
import javax.inject.Inject

/**
 * Состояние и действия для всех экранов группы: экраны рисуют [view] и вызывают методы действий, а методы
 * передают их в [GroupActions]. Один экземпляр на всю навигацию создаёт `AppNavHost`.
 *
 * В фейковом режиме (`DataModeModule`) [view] берётся из [FakeGroupViews].
 * Создание группы и вступление при этом настоящие: они пишут группу в базу и запускают службу, только событие
 * JOINED пропускают.
 *
 * Задача: docs/team/valentin-ui.md (экраны вызывают эти методы)
 */
@HiltViewModel
class GroupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val actions: GroupActions,
    private val repository: GroupRepository,
    private val settings: AppSettings,
    private val uploadScheduler: UploadScheduler,
    viewProvider: GroupViewProvider,
    @FakeGroupData private val useFakeGroupData: Boolean,
) : ViewModel() {

    /** Вид группы для экранов; null, пока группы нет или первый расчёт ещё не готов. */
    val view: StateFlow<GroupView?> =
        if (useFakeGroupData) {
            repository.activeGroup.map { group ->
                group?.let { FakeGroupViews.leader.copy(group = it, me = it.me, iAmLeader = it.me == it.leaderId) }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null)
        } else {
            viewProvider.view
        }

    /** Адрес сервера: его вместе с токеном отправляет «Передать токен» на экране QR. */
    val serverUrl: StateFlow<String> = settings.serverUrl

    /**
     * Была ли активная группа при запуске: от этого зависит первый экран. Читается один раз, чтобы создание группы
     * не меняло стартовый экран у уже построенной навигации. null, пока база не ответила.
     */
    val hadGroupAtStart: StateFlow<Boolean?> = flow { emit(repository.activeGroup() != null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null)

    /**
     * Поднимает службу, когда приложение на экране: после убийства процесса, принудительной остановки или выдачи
     * разрешений. Для той же группы служба ничего не перезапускает. После завершения маршрута служба не поднимается:
     * она сама работает 15 минут после ROUTE_FINISHED или её останавливает «Выключить». Вид ждём из потока, потому что
     * сразу после открытия приложения он ещё null.
     */
    fun ensureRouteServiceRunning() {
        viewModelScope.launch {
            if (repository.activeGroup() == null) return@launch
            if (view.filterNotNull().first().route == RouteState.FINISHED) return@launch
            RouteService.start(context)
        }
    }

    fun createGroup(groupName: String, myName: String, onCreated: () -> Unit) {
        viewModelScope.launch {
            if (useFakeGroupData) repository.createGroup(groupName) else actions.createGroup(groupName, myName)
            RouteService.start(context)
            onCreated()
        }
    }

    fun joinGroup(qr: QrPayload, myName: String, onJoined: () -> Unit) {
        viewModelScope.launch {
            if (useFakeGroupData) repository.join(qr) else actions.joinGroup(qr, myName)
            RouteService.start(context)
            onJoined()
        }
    }

    fun startRoute() = act { actions.startRoute() }

    fun finishRoute() = act { actions.finishRoute() }

    /** «Выключить» после завершения маршрута, не дожидаясь 15 минут. */
    fun stopService() = RouteService.stop(context)

    fun startRollcall() = act { actions.startRollcall() }

    fun answerRollcall(rollcallId: EventId) = act { actions.answerRollcall(rollcallId) }

    fun announceAway(minutes: Int) = act { actions.announceAway(minutes) }

    fun cancelAway() = act { actions.cancelAway() }

    fun raiseAlarm(target: MemberId) = act { actions.raiseAlarm(target) }

    /** SOS уходит и в mesh к телефонам группы, и на сервер мониторинга, когда появится сеть. */
    fun sos() {
        act { actions.sos() }
        viewModelScope.launch {
            val group = repository.activeGroup() ?: return@launch
            uploadScheduler.sendSos(group, settings.serverUrl.value)
        }
    }

    /** Снятие тревоги о себе отменяет и SOS, который ещё ждёт сети. */
    fun resolve(target: MemberId, kind: ResolveKind) = act {
        actions.resolve(target, kind)
        repository.activeGroup()?.takeIf { it.me == target }?.let { uploadScheduler.cancelSos(it.tag) }
    }

    private fun act(action: suspend () -> Unit) {
        if (useFakeGroupData) return
        viewModelScope.launch { action() }
    }
}
