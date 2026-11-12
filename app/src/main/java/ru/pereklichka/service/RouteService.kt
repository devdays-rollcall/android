package ru.pereklichka.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.pereklichka.actions.GroupActions
import ru.pereklichka.data.AppSettings
import ru.pereklichka.data.GroupRepository
import ru.pereklichka.data.GroupToken
import ru.pereklichka.di.FakeGroupData
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.presence.PresenceTracker
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.domain.rules.finishNotConfirmedBy
import ru.pereklichka.domain.sync.PeerTransport
import ru.pereklichka.domain.sync.SyncConfig
import ru.pereklichka.domain.sync.SyncEngine
import ru.pereklichka.domain.sync.TransportConfig
import ru.pereklichka.domain.time.Clock
import ru.pereklichka.permissions.RequiredPermissions
import ru.pereklichka.status.GroupViewProvider
import ru.pereklichka.status.SosRelay
import ru.pereklichka.status.StatusMonitor
import ru.pereklichka.upload.UploadScheduler
import javax.inject.Inject

/**
 * Foreground service маршрута. Пока телефон лежит в кармане с выключенным экраном, в ней живут обмен
 * с соседями, GPS и уведомления, а в шторке висит её постоянное уведомление ([ServiceNotification]).
 *
 * Запускает её [start] при создании группы, вступлении и каждом возврате приложения на экран, пока маршрут
 * не завершён. Делать это
 * нужно, пока приложение на экране. Из фона Android 12 и новее foreground service не запускает, а тип location
 * на Android 14 из фона поднимается только с разрешением «Разрешить всегда». Типы службы (location,
 * connectedDevice) выбираются по выданным разрешениям.
 *
 * Для активной группы служба поднимает [RouteSession] с движком, транспортом, своим состоянием, монитором
 * уведомлений, треком, wake lock и отправкой на сервер. В «Сборе» телефон уже обменивается данными
 * и прогревает GPS. После ROUTE_FINISHED GPS выключается, а обмен идёт, пока завершение не подтвердят все участники
 * (`finishNotConfirmedBy`), но не дольше [SyncConfig.exchangeAfterFinishMs]. Без других участников служба выключается
 * сразу. Работает ли обмен и когда он закончится, служба пишет в [ExchangeStatus] для экранов. Перед созданием
 * новой группы и вступлением в другую [leave] останавливает службу и переводит активную группу в прошлые маршруты,
 * а отменённый сбор без участников [discard] удаляет совсем. «Выйти из группы» ([quit]) сначала пишет событие LEFT
 * и ждёт, пока его подтвердит хотя бы один сосед, но не дольше [QUIT_CONFIRM_MS], а потом переводит группу
 * в прошлые маршруты так же, как [leave].
 *
 * Если система убила процесс посреди маршрута, она возвращает службу сама (START_STICKY), а после перезагрузки
 * её поднимает [BootReceiver]. Оба пути идут через [restartRoute].
 */
@AndroidEntryPoint
class RouteService : Service() {

    @Inject lateinit var groupRepository: GroupRepository
    @Inject lateinit var viewProvider: GroupViewProvider
    @Inject lateinit var engine: SyncEngine
    @Inject lateinit var transport: PeerTransport
    @Inject lateinit var selfStateProducer: SelfStateProducer
    @Inject lateinit var statusMonitor: StatusMonitor
    @Inject lateinit var sosRelay: SosRelay
    @Inject lateinit var trackRecorder: TrackRecorder
    @Inject lateinit var powerTools: PowerTools
    @Inject lateinit var uploadScheduler: UploadScheduler
    @Inject lateinit var settings: AppSettings
    @Inject lateinit var config: SyncConfig
    @Inject lateinit var presence: PresenceTracker
    @Inject lateinit var exchangeStatus: ExchangeStatus
    @Inject lateinit var clock: Clock
    @Inject lateinit var actions: GroupActions

    @JvmField
    @Inject
    @FakeGroupData
    var useFakeGroupData: Boolean = false

    /** Область службы на главном потоке. Запуск и остановка частей идут в ней, поэтому полям ниже синхронизация не нужна. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var route: RouteSession? = null
    private var trackingJob: kotlinx.coroutines.Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var leaving: kotlinx.coroutines.Job? = null

    /** Пока закрывалась прежняя группа, пришёл запуск для новой: после закрытия служба поднимет её сеанс. */
    private var startAfterLeaving = false
    private var quitting: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /** Пустой интент приходит, когда система возвращает службу после убийства процесса (START_STICKY). */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            null, ACTION_RESTART -> restartRoute()
            ACTION_START -> startRoute()
            ACTION_STOP -> stopRoute()
            ACTION_LEAVE -> leaveGroup(discard = false)
            ACTION_DISCARD -> leaveGroup(discard = true)
            ACTION_QUIT -> quitGroup()
        }
        return if (useFakeGroupData) START_NOT_STICKY else START_STICKY
    }

    override fun onDestroy() {
        if (exchangeStatus.exchange.value is Exchange.On) exchangeStatus.set(Exchange.Off())
        stopComponents()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Запуск с экрана. Без разрешений служба сразу останавливается: [start] проверяет их заранее, и сюда это попадает,
     * только если их отозвали за мгновение до запуска. Тип location снимает напоминание «Маршрут не завершён», потому
     * что трек снова пишется. Пока закрывается прежняя группа, новый сеанс не поднимается, иначе он прочитал бы её
     * из базы, и его поднимет само закрытие. Интерфейс запускает службу при каждом возврате на экран, поэтому та же
     * группа ничего не перезапускает.
     */
    private fun startRoute() {
        val types = foregroundServiceTypes(this)
        if (types == 0) {
            Log.w(TAG, "Нет разрешений ни на обмен, ни на геолокацию, служба не запускается")
            stopSelf()
            return
        }
        ServiceCompat.startForeground(this, ServiceNotification.ID, ServiceNotification.build(this), types)
        if (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0) RouteNotFinishedNotification.cancel(this)
        if (leaving?.isActive == true) {
            startAfterLeaving = true
            return
        }
        if (useFakeGroupData) {
            if (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION == 0 || trackingJob?.isActive == true) return
            registerNetworkCallback()
            uploadScheduler.schedulePeriodic()
            uploadScheduler.uploadNow()
            trackingJob = serviceScope.launch { trackRecorder.run(MutableStateFlow(RouteState.ACTIVE), uploadScheduler::uploadNow) }
            return
        }

        registerNetworkCallback()
        serviceScope.launch {
            val group = groupRepository.activeGroup() ?: return@launch stopRoute()
            if (leaving?.isActive == true) return@launch
            if (route?.group == group) return@launch
            route?.stop()
            route = RouteSession(group).also { it.start() }
        }
    }

    /**
     * Поднимает маршрут без человека у экрана: после убийства процесса и после перезагрузки ([restart]).
     * `startForeground` вызывается сразу, до чтения базы: службу, запущенную через `startForegroundService`,
     * система убивает, если та не вышла на передний план за несколько секунд. Из фона тип location доступен
     * только с «Разрешить всегда», без него обмен идёт, а трек стоит, пока человек не откроет приложение.
     * Об этом на маршруте говорит [RouteNotFinishedNotification]; в «Сборе» трек не пишется, и напоминание не нужно.
     * Пока читалась база, человек мог открыть приложение, и тогда сеанс уже поднял [startRoute].
     */
    private fun restartRoute() {
        if (useFakeGroupData || route != null) return
        val types = foregroundServiceTypes(this, fromBackground = true)
        val inForeground = enterForeground(types)
        serviceScope.launch {
            val group = groupRepository.activeGroup()
            val state = viewProvider.awaitRoute()
            if (group == null || state == RouteState.FINISHED) return@launch stopRoute()
            if (route != null || leaving?.isActive == true) return@launch
            val tracking = inForeground && types and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0
            if (state == RouteState.ACTIVE && !tracking) RouteNotFinishedNotification.show(this@RouteService)
            if (!inForeground) return@launch stopRoute()
            registerNetworkCallback()
            route = RouteSession(group).also { it.start() }
        }
    }

    /**
     * false, если на передний план выйти нельзя: нет разрешений ни на один тип или Android 12 и новее
     * запретил запуск из фона (`ForegroundServiceStartNotAllowedException`, бывает при автоматическом перезапуске).
     * Это исключение есть только с Android 12 и наследует `IllegalStateException`, поэтому ловится по нему.
     */
    private fun enterForeground(types: Int): Boolean {
        if (types == 0) return false
        return try {
            ServiceCompat.startForeground(this, ServiceNotification.ID, ServiceNotification.build(this), types)
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Служба не вышла на передний план после перезапуска", e)
            false
        }
    }

    private fun stopRoute(finishConfirmedByAll: Boolean = false) {
        Log.i(TAG, "служба останавливается${if (finishConfirmedByAll) ": завершение получили все" else ""}")
        exchangeStatus.set(Exchange.Off(finishConfirmedByAll))
        stopComponents()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * «Выйти из группы» у участника. Сосед, подтвердивший LEFT сводкой, разошлёт его дальше сам, поэтому ждать
     * всю группу не нужно. Если за [QUIT_CONFIRM_MS] никто не подтвердил (рядом никого нет), группа всё равно
     * уходит в прошлые маршруты, и остальные увидят «Не слышно».
     */
    private fun quitGroup() {
        if (quitting?.isActive == true || leaving?.isActive == true) return
        quitting = serviceScope.launch {
            val left = runCatching { actions.announceLeaving() }
                .onFailure { Log.w(TAG, "LEFT не записан", it) }
                .getOrNull()
            if (left != null && route != null) {
                val confirmed = withTimeoutOrNull(QUIT_CONFIRM_MS) {
                    engine.reportedBySeq.first { reported -> reported.values.any { (it[left.author] ?: 0L) >= left.seq } }
                } != null
                Log.i(TAG, if (confirmed) "Выход подтвердил сосед" else "Выход никто не подтвердил за $QUIT_CONFIRM_MS мс")
            }
            leaveGroup(discard = false)
        }
    }

    /**
     * Закрывает активную группу: сеанс останавливается здесь же, на главном потоке, и только после того, как все его
     * корутины закончились, группа уходит в прошлые маршруты или, с [discard], удаляется. Иначе движок прежней группы
     * успел бы записать Delta соседей в новую группу, созданную сразу после этого.
     *
     * Вызов отменяет ожидание подтверждения выхода («Выйти сразу»). Если вызов пришёл из самого ожидания, отмена
     * ничего не прерывает: до закрытия группы точек приостановки нет. Неотправленный трек прошлого маршрута уходит
     * с его токеном, поэтому отправка снимается, только когда трека не осталось. Если новую группу записали, пока
     * шло закрытие, её запуск ждал здесь и поднимается сразу: иначе служба остановилась бы, а сеанс новой группы
     * поднялся бы только при следующем возврате приложения на экран.
     */
    private fun leaveGroup(discard: Boolean) {
        if (leaving?.isActive == true) return
        startAfterLeaving = false
        quitting?.cancel()
        val session = route
        val tracking = trackingJob
        exchangeStatus.set(Exchange.Off())
        stopComponents()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        leaving = serviceScope.launch {
            session?.join()
            tracking?.join()
            if (discard) groupRepository.deleteActiveGroup() else groupRepository.archiveActiveGroup()
            if (groupRepository.hasUnsentTrack()) uploadScheduler.uploadNow() else uploadScheduler.cancelPeriodic()
            leaving = null
            if (startAfterLeaving) {
                startAfterLeaving = false
                startRoute()
            } else {
                stopSelf()
            }
        }
    }

    private fun stopComponents() {
        trackingJob?.cancel()
        trackingJob = null
        route?.stop()
        route = null
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        networkCallback = null
    }

    /** Подписывается на появление сети с интернетом: тогда точки сразу уходят на сервер через `UploadScheduler.restartUpload`. */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                serviceScope.launch { uploadScheduler.restartUpload() }
            }
        }
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    /**
     * Части службы, работающие для одной группы. Новая группа получает новый сеанс с чистого листа.
     * Запуск и остановка идут на главном потоке, сами части работают в своей области на `Dispatchers.Default`.
     */
    private inner class RouteSession(val group: GroupInfo) {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Поднимает части сеанса. Кого слышали в прошлой группе, забывается. Движок запускается до транспорта, но его
         * подписка всё равно может начаться позже, и первые события транспорта сохраняет буфер `Channel.UNLIMITED`,
         * хотя контракт `PeerTransport` этого не обещает.
         */
        fun start() {
            Log.i(TAG, "сеанс группы ${group.tag} запущен")
            presence.clear()
            engine.start(scope, group)
            transport.start(
                TransportConfig(
                    serviceId = GroupToken.serviceIdOf(group.tag),
                    me = group.me,
                    blocked = settings.blockedMembers,
                    isOutsider = ::isOutsider,
                ),
            )
            powerTools.acquireWakeLock()
            uploadScheduler.schedulePeriodic()
            exchangeStatus.set(Exchange.On())

            val routeState = viewProvider.view
                .filterNotNull()
                .filter { it.group == group }
                .map { it.route }
                .stateIn(scope, SharingStarted.Eagerly, RouteState.LOBBY)
            scope.launch { selfStateProducer.run() }
            scope.launch { statusMonitor.run(group) }
            scope.launch { sosRelay.run(group) }
            scope.launch { trackRecorder.run(routeState, uploadScheduler::uploadNow) }
            scope.launch {
                viewProvider.view
                    .filterNotNull()
                    .filter { it.group == group }
                    .map(ServiceNotification::statusText)
                    .distinctUntilChanged()
                    .collect { ServiceNotification.update(this@RouteService, it) }
            }
            scope.launch {
                routeState.first { it == RouteState.FINISHED }
                uploadScheduler.uploadNow()
                Log.i(TAG, "маршрут завершён, обмен до подтверждения всеми, не дольше ${config.exchangeAfterFinishMs} мс")
                exchangeStatus.set(Exchange.On(stopsAtElapsedMs = clock.elapsedMs() + config.exchangeAfterFinishMs))
                val confirmedByAll = withTimeoutOrNull(config.exchangeAfterFinishMs) { awaitFinishConfirmedByAll() } != null
                withContext(Dispatchers.Main) { stopRoute(confirmedByAll) }
            }
        }

        fun stop() {
            scope.cancel()
            engine.stop()
            transport.stop()
            powerTools.releaseWakeLock()
        }

        /**
         * Ждёт, пока завершение маршрута подтвердят сводками все участники. Подтвердить могут только соседи на связи,
         * поэтому участник, которого мой телефон не слышит напрямую, держит обмен до конца срока.
         */
        private suspend fun awaitFinishConfirmedByAll() {
            val view = viewProvider.view.filterNotNull().filter { it.group == group }
            combine(view, engine.reportedBySeq) { current, reported -> current.finishNotConfirmedBy(reported) }
                .first { it?.isEmpty() == true }
        }

        /** Ждёт, пока после [stop] закончатся корутины сеанса, в том числе запись в базу. */
        suspend fun join() = scope.coroutineContext.job.join()

        /** true для outsider, см. `TransportConfig.isOutsider`. */
        private fun isOutsider(member: MemberId): Boolean {
            val lastContact = viewProvider.view.value?.members?.firstOrNull { it.id == member }?.lastContact
            return lastContact == null || lastContact.ms > PresenceTracker.HEARD_NOW_MS
        }
    }

    companion object {
        private const val TAG = "RouteService"
        private const val ACTION_START = "ru.pereklichka.action.START_ROUTE"
        private const val ACTION_STOP = "ru.pereklichka.action.STOP_ROUTE"
        private const val ACTION_RESTART = "ru.pereklichka.action.RESTART_ROUTE"
        private const val ACTION_LEAVE = "ru.pereklichka.action.LEAVE_GROUP"
        private const val ACTION_QUIT = "ru.pereklichka.action.QUIT_GROUP"
        private const val ACTION_DISCARD = "ru.pereklichka.action.DISCARD_GROUP"

        /** Сколько миллисекунд «Выйти из группы» ждёт, пока сосед подтвердит LEFT: сводки приходят раз в 10 с. */
        const val QUIT_CONFIRM_MS = 30_000L

        /**
         * Запускает службу для активной группы. Вызывать, только пока приложение на экране. Повторный вызов
         * для той же группы ничего не перезапускает. Возвращает false и службу не запускает, если нет разрешений
         * ни на обмен, ни на геолокацию: служба без `startForeground` уронила бы приложение.
         */
        fun start(context: Context): Boolean {
            if (foregroundServiceTypes(context) == 0) return false
            ContextCompat.startForegroundService(context, Intent(context, RouteService::class.java).setAction(ACTION_START))
            return true
        }

        /**
         * Поднимает службу из фона после перезагрузки, только с типом connectedDevice (и location при «Разрешить
         * всегда»). Если нет разрешений на обмен, службу не запускает и показывает «Маршрут не завершён».
         */
        fun restart(context: Context) {
            if (foregroundServiceTypes(context, fromBackground = true) == 0) {
                RouteNotFinishedNotification.show(context)
                return
            }
            ContextCompat.startForegroundService(context, Intent(context, RouteService::class.java).setAction(ACTION_RESTART))
        }

        /** Останавливает службу: кнопка «Выключить» после завершения маршрута. */
        fun stop(context: Context) {
            context.startService(Intent(context, RouteService::class.java).setAction(ACTION_STOP))
        }

        /**
         * «Выйти из группы» у участника: LEFT, ожидание подтверждения и переход группы в прошлые маршруты. Вызывать,
         * пока приложение на экране.
         */
        fun quit(context: Context) {
            context.startService(Intent(context, RouteService::class.java).setAction(ACTION_QUIT))
        }

        /**
         * Останавливает службу и переводит активную группу в прошлые маршруты: перед созданием новой группы,
         * вступлением в другую и по «Выйти сразу». Смена группы всегда идёт через службу, даже если она не работает,
         * чтобы не обогнать остановку сеанса. Вызывать, пока приложение на экране.
         */
        fun leave(context: Context) {
            context.startService(Intent(context, RouteService::class.java).setAction(ACTION_LEAVE))
        }

        /**
         * Останавливает службу и удаляет активную группу со всеми записями: руководитель отменил сбор, в который
         * никто не вступил, и хранить такую группу незачем. Вызывать, пока приложение на экране.
         */
        fun discard(context: Context) {
            context.startService(Intent(context, RouteService::class.java).setAction(ACTION_DISCARD))
        }

        /**
         * Типы службы по выданным разрешениям, 0 если нельзя ни один: без разрешения Android 14 роняет запуск
         * службы с этим типом. До Android 10 `ServiceCompat.startForeground` типы не передаёт, поэтому константы
         * новых версий здесь безопасны. [fromBackground] нужен запуску без экрана: тогда тип location требует
         * ещё и «Разрешить всегда». Тип location берётся только с точной геолокацией: по приблизительной трек
         * не записать.
         */
        @SuppressLint("InlinedApi")
        private fun foregroundServiceTypes(context: Context, fromBackground: Boolean = false): Int {
            var types = 0
            if (RequiredPermissions.allGranted(context, RequiredPermissions.exchange())) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (fine && (!fromBackground || RequiredPermissions.allGranted(context, RequiredPermissions.backgroundLocation()))) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            return types
        }
    }
}
