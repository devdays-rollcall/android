package ru.pereklichka.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.pereklichka.actions.GroupActions
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
import javax.inject.Inject

/**
 * Состояние и действия для всех экранов группы. Экраны рисуют [view] и вызывают методы действий.
 *
 * В фейковом режиме (`DataModeModule`) [view] берётся из [FakeGroupViews], а кнопки ничего не пишут, потому что
 * `RoomMeshStore` ещё не готов. Создание группы и вступление при этом настоящие: они пишут группу в базу
 * и запускают службу, только событие JOINED пропускают.
 */
@HiltViewModel
class GroupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val actions: GroupActions,
    private val repository: GroupRepository,
    viewProvider: GroupViewProvider,
    @FakeGroupData private val useFakeGroupData: Boolean,
) : ViewModel() {

    /** null, пока группы нет или первый расчёт ещё не готов. */
    val view: StateFlow<GroupView?> =
        if (useFakeGroupData) MutableStateFlow(FakeGroupViews.leader) else viewProvider.view

    /**
     * Была ли активная группа при запуске: от этого зависит первый экран. Читается один раз, чтобы создание группы
     * не меняло стартовый экран у уже построенной навигации. null, пока база не ответила.
     */
    val hadGroupAtStart: StateFlow<Boolean?> = flow { emit(repository.activeGroup() != null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null)

    /**
     * Поднимает службу, когда приложение на экране: после убийства процесса, принудительной остановки или выдачи
     * разрешений. Для той же группы служба ничего не перезапускает; после завершения маршрута не поднимается.
     */
    fun ensureRouteServiceRunning() {
        viewModelScope.launch {
            if (repository.activeGroup() == null || view.value?.route == RouteState.FINISHED) return@launch
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

    fun sos() = act { actions.sos() }

    fun resolve(target: MemberId, kind: ResolveKind) = act { actions.resolve(target, kind) }

    private fun act(action: suspend () -> Unit) {
        if (useFakeGroupData) return
        viewModelScope.launch { action() }
    }
}
