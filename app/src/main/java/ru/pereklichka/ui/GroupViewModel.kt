package ru.pereklichka.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.pereklichka.actions.GroupActions
import ru.pereklichka.data.AppSettings
import ru.pereklichka.data.GroupRepository
import ru.pereklichka.data.db.LocationDao
import ru.pereklichka.data.db.key
import ru.pereklichka.di.FakeGroupData
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.QrPayload
import ru.pereklichka.domain.model.ResolveKind
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.domain.sync.SyncEngine
import ru.pereklichka.service.Exchange
import ru.pereklichka.service.ExchangeStatus
import ru.pereklichka.service.RouteService
import ru.pereklichka.status.GroupViewProvider
import ru.pereklichka.ui.components.TrackStatus
import ru.pereklichka.upload.UploadScheduler
import javax.inject.Inject

/**
 * Состояние и действия для всех экранов группы: экраны рисуют [view] и вызывают методы действий, а методы
 * передают их в [GroupActions]. Один экземпляр на всю навигацию создаёт `AppNavHost`.
 *
 * В фейковом режиме (`DataModeModule`) [view] берётся из [FakeGroupViews], а действия и отправка SOS на сервер
 * ничего не делают. Создание группы, вступление и выход при этом настоящие: они пишут группу в базу, закрывают её
 * и управляют службой, только событие JOINED пропускают.
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
    exchangeStatus: ExchangeStatus,
    locationDao: LocationDao,
    engine: SyncEngine,
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

    /**
     * Что подтвердили соседи на связи: по нему экраны переклички и SOS пишут, кто уже получил событие
     * (см. `receivedBy`). Подтверждают только соседи, которых мой телефон слышит напрямую.
     */
    val reportedBySeq: StateFlow<Map<MemberId, Map<MemberId, Long>>> = engine.reportedBySeq

    /** Работает ли обмен с группой и когда он закончится после завершения маршрута, по словам самой службы. */
    val exchange: StateFlow<Exchange> = exchangeStatus.exchange

    /** Отправка трека активной группы для стартовой карточки и экрана завершения; null, пока база не ответила. */
    val trackStatus: StateFlow<TrackStatus?> = combine(
        locationDao.observeActiveCount(),
        locationDao.observeActiveUnsentCount(),
        settings.serverUrl,
    ) { points, unsent, serverUrl -> TrackStatus(points, unsent, hasServer = serverUrl.isNotBlank()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialValue = null)

    /** Адрес сервера: его вместе с токеном отправляет «Передать токен» на экране QR. */
    val serverUrl: StateFlow<String> = settings.serverUrl

    /**
     * Была ли активная группа при запуске: от этого зависит первый экран. Читается один раз, чтобы создание группы
     * не меняло стартовый экран у уже построенной навигации. null, пока база не ответила.
     */
    val hadGroupAtStart: StateFlow<Boolean?> = flow { emit(repository.activeGroup() != null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null)

    /**
     * Есть ли активная группа сейчас; null, пока база не ответила. По нему навигация уходит с экранов создания
     * и вступления, когда группа появилась, и на старт, когда служба закрыла её после выхода или отмены сбора.
     */
    val hasGroup: StateFlow<Boolean?> = repository.activeGroup.map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null)

    /**
     * Ключ активной группы (`GroupInfo.key`); null, пока группы нет или база не ответила. При смене группы [hasGroup]
     * проходит true, false и снова true за несколько миллисекунд, и экран может застать только последнее значение,
     * поэтому навигация следит ещё и за ключом.
     */
    val activeGroupKey: StateFlow<String?> = repository.activeGroup.map { it?.key }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null)

    private val serviceStartedState = MutableStateFlow<Boolean?>(null)

    /**
     * Запущена ли служба по последнему вызову [RouteService.start]. false, если запуск не удался из-за разрешений
     * или службу остановили «Выключить» и выход из группы; null, пока службу в этом процессе не запускали. По нему
     * экраны предупреждают о разрешениях, а работает ли служба на самом деле, показывает [exchange].
     */
    val serviceStarted: StateFlow<Boolean?> = serviceStartedState.asStateFlow()

    private val settingUpState = MutableStateFlow(false)

    private val quittingState = MutableStateFlow(false)

    /**
     * Участник нажал «Выйти из группы», и служба сообщает группе о выходе (до `RouteService.QUIT_CONFIRM_MS`).
     * Снимается, когда группа ушла в прошлые маршруты.
     */
    val quitting: StateFlow<Boolean> = quittingState.asStateFlow()

    init {
        viewModelScope.launch { hasGroup.collect { if (it == false) quittingState.value = false } }
    }

    /**
     * Группа создаётся или идёт вступление: кнопка экрана неактивна, пока навигация не уведёт дальше. После успеха
     * флаг остаётся true, потому что навигация уходит с экрана позже, когда Room сообщит о новой группе, а повторное
     * нажатие в этот промежуток закрыло бы только что созданную группу. Снимают флаг ошибка записи, выход из группы
     * и [resetSettingUp], когда со старта снова открывают создание или сканер.
     */
    val settingUp: StateFlow<Boolean> = settingUpState.asStateFlow()

    /** Снимает [settingUp], оставшийся от прошлого создания или вступления: со старта снова открывают создание или сканер. */
    fun resetSettingUp() {
        settingUpState.value = false
    }

    /**
     * Поднимает службу, когда приложение на экране: после убийства процесса, принудительной остановки или выдачи
     * разрешений. Для той же группы служба ничего не перезапускает. После завершения маршрута служба не поднимается:
     * она сама выключается, когда завершение дошло до всех, или её останавливает «Выключить». Вид ждём из потока,
     * потому что сразу после открытия приложения он ещё null.
     */
    fun ensureRouteServiceRunning() {
        viewModelScope.launch {
            if (repository.activeGroup() == null) return@launch
            if (view.filterNotNull().first().route == RouteState.FINISHED) return@launch
            serviceStartedState.value = RouteService.start(context)
        }
    }

    /** Пишет новую группу и запускает службу. На экран QR переводит навигация, когда [hasGroup] станет true. */
    fun createGroup(groupName: String, myName: String) = setUpGroup {
        if (useFakeGroupData) repository.createGroup(groupName) else actions.createGroup(groupName, myName)
    }

    /** Вступает по QR и запускает службу. К списку группы переводит навигация, когда [hasGroup] станет true. */
    fun joinGroup(qr: QrPayload, myName: String) = setUpGroup {
        if (useFakeGroupData) repository.join(qr) else actions.joinGroup(qr, myName)
    }

    /**
     * Если на телефоне есть активная группа, сначала закрывает её через службу и ждёт, пока она уйдёт в прошлые
     * маршруты: иначе движок прежней группы успел бы записать Delta соседей в новую. Так прежняя группа перестаёт
     * быть активной только тогда, когда человек правда создаёт новую или вступает в другую.
     */
    private fun setUpGroup(write: suspend () -> Unit) {
        if (settingUpState.value) return
        settingUpState.value = true
        serviceStartedState.value = null
        viewModelScope.launch {
            if (repository.activeGroup() != null) {
                RouteService.leave(context)
                repository.activeGroup.first { it == null }
            }
            try {
                write()
            } catch (e: Exception) {
                settingUpState.value = false
                throw e
            }
            serviceStartedState.value = RouteService.start(context)
        }
    }

    fun startRoute() = act { actions.startRoute() }

    fun finishRoute() = act { actions.finishRoute() }

    /**
     * «Отменить сбор» у руководителя. Если никто не вступил, группа удаляется: хранить в прошлых маршрутах нечего.
     * Иначе сбор завершается у всех событием ROUTE_FINISHED, и вступившие увидят «Сбор отменён», а группа остаётся
     * завершённой.
     */
    fun cancelGathering() {
        val others = view.value?.members?.any { !it.isMe } ?: return
        if (others) finishRoute() else discardGroup()
    }

    /**
     * «Выйти из группы» у участника: служба сообщает группе о выходе и переводит группу в прошлые маршруты,
     * навигация уйдёт на старт.
     */
    fun quitGroup() {
        if (useFakeGroupData) return leaveGroup()
        quittingState.value = true
        serviceStartedState.value = false
        settingUpState.value = false
        RouteService.quit(context)
    }

    /** «Выключить» после завершения маршрута, не дожидаясь 15 минут. Группа остаётся на телефоне. */
    fun stopService() {
        serviceStartedState.value = false
        RouteService.stop(context)
    }

    /**
     * «Выйти сразу», пока служба ждёт подтверждения выхода: служба останавливается и переводит группу в прошлые
     * маршруты, а навигация по [hasGroup] уходит на старт.
     */
    fun leaveGroup() {
        serviceStartedState.value = false
        settingUpState.value = false
        RouteService.leave(context)
    }

    /** Отмена сбора, в который никто не вступил: служба останавливается и удаляет группу. */
    private fun discardGroup() {
        serviceStartedState.value = false
        settingUpState.value = false
        RouteService.discard(context)
    }

    fun startRollcall() = act { actions.startRollcall() }

    fun answerRollcall(rollcallId: EventId) = act { actions.answerRollcall(rollcallId) }

    fun announceAway(minutes: Int) = act { actions.announceAway(minutes) }

    fun cancelAway() = act { actions.cancelAway() }

    fun raiseAlarm(target: MemberId) = act { actions.raiseAlarm(target) }

    /**
     * SOS уходит и в mesh к телефонам группы, и на сервер мониторинга, когда появится сеть. На сервер его поставит
     * и служба (`SosRelay`), но отсюда он встаёт сразу, даже если служба не запущена. Задача уникальна по id SOS,
     * поэтому второй раз он не уйдёт.
     */
    fun sos() = act {
        val sos = actions.sos()
        val group = repository.activeGroup() ?: return@act
        uploadScheduler.sendSos(group, sos.id, sos.author, settings.serverUrl.value)
    }

    /** «Отправить на сервер ещё раз», если прошлая отправка моего SOS на сервер не удалась. В mesh SOS уже ушёл. */
    fun resendSosToServer() = act {
        val group = repository.activeGroup() ?: return@act
        view.value?.alarms.orEmpty().filter { it.isSos && it.target == group.me }.forEach {
            uploadScheduler.sendSos(group, it.id, it.target, settings.serverUrl.value, replace = true)
        }
    }

    /** Снятие SOS отменяет и его отправку на сервер, если она ещё ждёт сети. */
    fun resolve(target: MemberId, kind: ResolveKind) = act {
        val resolved = view.value?.alarms.orEmpty().filter { it.isSos && it.target == target }
        actions.resolve(target, kind)
        val group = repository.activeGroup() ?: return@act
        resolved.forEach { uploadScheduler.cancelSos(group.tag, it.id) }
    }

    private fun act(action: suspend () -> Unit) {
        if (useFakeGroupData) return
        viewModelScope.launch { action() }
    }

    private companion object {
        /** Сколько миллисекунд держать подписку на базу без экрана, чтобы поворот экрана её не перезапускал. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
