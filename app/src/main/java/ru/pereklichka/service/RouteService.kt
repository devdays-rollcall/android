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
 * сразу. Работает ли обмен и когда он закончится, служба пишет в [ExchangeStatus] для экранов. «Новая группа»
 * ([leave]) останавливает службу и стирает группу. «Выйти из группы» ([quit]) сначала пишет событие LEFT и ждёт,
 * пока его подтвердит хотя бы один сосед, но не дольше [QUIT_CONFIRM_MS], а потом стирает группу так же.
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

    // Запуск и остановка частей идут на главном потоке, поэтому полям ниже синхронизация не нужна.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var route: RouteSession? = null
    private var trackingJob: kotlinx.coroutines.Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var leaving: kotlinx.coroutines.Job? = null
    private var quitting: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // Пустой интент приходит, когда система возвращает службу после убийства процесса (START_STICKY).
            null, ACTION_RESTART -> restartRoute()
            ACTION_START -> startRoute()
            ACTION_STOP -> stopRoute()
            ACTION_LEAVE -> leaveGroup()
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

    private fun startRoute() {
        val types = foregroundServiceTypes(this)
        if (types == 0) {
            // start() проверяет разрешения заранее, так что сюда попадаем, только если их отозвали за мгновение до запуска.
            Log.w(TAG, "Нет разрешений ни на обмен, ни на геолокацию, служба не запускается")
            stopSelf()
            return
        }
        ServiceCompat.startForeground(this, ServiceNotification.ID, ServiceNotification.build(this), types)
        // Человек открыл приложение, и служба снова пишет трек: напоминание после перезапуска больше не нужно.
        if (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0) RouteNotFinishedNotification.cancel(this)
        // Пока стирается старая группа, новый сеанс поднимать нельзя: он прочитал бы её из базы.
        if (leaving?.isActive == true) return
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
            // «Новая группа» могла начаться, пока читалась база.
            if (leaving?.isActive == true) return@launch
            // Интерфейс запускает службу при каждом возврате на экран, поэтому та же группа ничего не перезапускает.
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
     */
    private fun restartRoute() {
        if (useFakeGroupData || route != null) return
        val types = foregroundServiceTypes(this, fromBackground = true)
        val inForeground = enterForeground(types)
        serviceScope.launch {
            val group = groupRepository.activeGroup()
            val state = viewProvider.awaitRoute()
            if (group == null || state == RouteState.FINISHED) return@launch stopRoute()
            // Пока читалась база, человек мог открыть приложение, и startRoute уже поднял сеанс.
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
     */
    private fun enterForeground(types: Int): Boolean {
        if (types == 0) return false
        return try {
            ServiceCompat.startForeground(this, ServiceNotification.ID, ServiceNotification.build(this), types)
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException наследует IllegalStateException и есть только с Android 12.
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
     * всю группу не нужно. Если за [QUIT_CONFIRM_MS] никто не подтвердил (рядом никого нет), группа стирается
     * всё равно, и остальные увидят «Не слышно».
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
            leaveGroup()
        }
    }

    /**
     * «Новая группа»: сеанс останавливается здесь же, на главном потоке, и только после того, как все его корутины
     * закончились, таблицы стираются. Иначе движок старой группы успел бы записать Delta соседей в очищенную базу.
     */
    private fun leaveGroup() {
        if (leaving?.isActive == true) return
        // «Выйти сразу» во время ожидания подтверждения: ждать больше не нужно. Если вызов пришёл из самого ожидания,
        // отмена ничего не прерывает: дальше до запуска стирания нет точек приостановки.
        quitting?.cancel()
        val session = route
        val tracking = trackingJob
        exchangeStatus.set(Exchange.Off())
        stopComponents()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        leaving = serviceScope.launch {
            session?.join()
            tracking?.join()
            groupRepository.leaveGroup()
            // Неотправленный трек стёртой группы ждёт в очереди, и отправку снимать рано.
            if (groupRepository.hasQueuedTrack()) uploadScheduler.uploadNow() else uploadScheduler.cancelPeriodic()
            stopSelf()
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

        fun start() {
            Log.i(TAG, "сеанс группы ${group.tag} запущен")
            // Кого слышали в прошлой группе, в состоянии новой группы не нужно.
            presence.clear()
            // Движок запускается до транспорта. Его подписка всё равно может начаться позже, и первые события
            // транспорта сохраняет буфер Channel.UNLIMITED, хотя контракт PeerTransport этого не обещает.
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
                .filter { it.group.tag == group.tag }
                .map { it.route }
                .stateIn(scope, SharingStarted.Eagerly, RouteState.LOBBY)
            scope.launch { selfStateProducer.run() }
            scope.launch { statusMonitor.run(group) }
            scope.launch { trackRecorder.run(routeState, uploadScheduler::uploadNow) }
            scope.launch {
                viewProvider.view
                    .filterNotNull()
                    .filter { it.group.tag == group.tag }
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
            val view = viewProvider.view.filterNotNull().filter { it.group.tag == group.tag }
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

        /** «Выйти из группы» у участника: LEFT, ожидание подтверждения и стирание группы. Вызывать, пока приложение на экране. */
        fun quit(context: Context) {
            context.startService(Intent(context, RouteService::class.java).setAction(ACTION_QUIT))
        }

        /**
         * Останавливает службу и стирает группу со всеми данными: кнопка «Новая группа» после завершения маршрута.
         * Стирание всегда идёт через службу, даже если она не работает, чтобы оно не обогнало остановку сеанса.
         * Вызывать, пока приложение на экране.
         */
        fun leave(context: Context) {
            context.startService(Intent(context, RouteService::class.java).setAction(ACTION_LEAVE))
        }

        /**
         * Типы службы по выданным разрешениям, 0 если нельзя ни один: без разрешения Android 14 роняет запуск
         * службы с этим типом. До Android 10 `ServiceCompat.startForeground` типы не передаёт, поэтому константы
         * новых версий здесь безопасны. [fromBackground] нужен запуску без экрана: тогда тип location требует
         * ещё и «Разрешить всегда».
         */
        @SuppressLint("InlinedApi")
        private fun foregroundServiceTypes(context: Context, fromBackground: Boolean = false): Int {
            var types = 0
            if (RequiredPermissions.allGranted(context, RequiredPermissions.exchange())) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            // Тип location берётся только с точной геолокацией: по приблизительной трек не записать.
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (fine && (!fromBackground || RequiredPermissions.allGranted(context, RequiredPermissions.backgroundLocation()))) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            return types
        }
    }
}
