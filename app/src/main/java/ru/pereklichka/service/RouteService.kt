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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.pereklichka.data.AppSettings
import ru.pereklichka.data.GroupRepository
import ru.pereklichka.data.GroupToken
import ru.pereklichka.di.FakeGroupData
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.presence.PresenceTracker
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.domain.sync.PeerTransport
import ru.pereklichka.domain.sync.SyncConfig
import ru.pereklichka.domain.sync.SyncEngine
import ru.pereklichka.domain.sync.TransportConfig
import ru.pereklichka.permissions.RequiredPermissions
import ru.pereklichka.status.GroupViewProvider
import ru.pereklichka.status.StatusMonitor
import ru.pereklichka.upload.UploadScheduler
import javax.inject.Inject

/**
 * Foreground service маршрута: держит обмен с соседями, GPS и уведомления, пока телефон лежит в кармане
 * с выключенным экраном. Пока она работает, система показывает её постоянное уведомление ([ServiceNotification]).
 *
 * Интерфейс запускает её через [start] при создании группы, вступлении и каждом возврате на экран
 * (`GroupViewModel.ensureRouteServiceRunning`). Запускать её нужно, пока приложение на экране: из фона
 * Android 12 и новее foreground service запускать не даёт, а тип location на Android 14 из фона не поднять
 * и без «Разрешить всегда». Типы службы (location, connectedDevice)
 * выбираются по выданным разрешениям. Для активной группы служба запускает [RouteSession]: `SyncEngine`,
 * транспорт, [SelfStateProducer], `StatusMonitor`, [TrackRecorder], wake lock и отправку на сервер.
 *
 * В «Сборе» телефон уже обменивается данными и прогревает GPS. После ROUTE_FINISHED GPS выключается, а обмен
 * идёт ещё [SyncConfig.exchangeAfterFinishMs], чтобы завершение дошло до всех. Данные после остановки
 * остаются в базе. В фейковом режиме (`DataModeModule`) служба показывает уведомление и больше ничего
 * не запускает.
 *
 * Задача: docs/team/vasilisa-service-storage.md (перезапуск после убийства процесса)
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

    @JvmField
    @Inject
    @FakeGroupData
    var useFakeGroupData: Boolean = false

    // Запуск и остановка частей идут на главном потоке, поэтому полям ниже синхронизация не нужна.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var route: RouteSession? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // TODO(Василиса): безопасный перезапуск после убийства процесса.
            //  startForeground только с типом connectedDevice (location, если выдано «Разрешить всегда»),
            //  перехватить ForegroundServiceStartNotAllowedException и показать «Маршрут не завершён».
            //  docs/team/vasilisa-service-storage.md
            throw NotImplementedError("TODO(Василиса): безопасный перезапуск после убийства процесса.")
        }
        when (intent.action) {
            ACTION_START -> startRoute()
            ACTION_STOP -> stopRoute()
        }
        // В фейковом режиме перезапускать нечего. Без фейкового режима система вернёт службу с пустым интентом
        // после убийства процесса, и ветка перезапуска выше должна быть готова.
        return if (useFakeGroupData) START_NOT_STICKY else START_STICKY
    }

    override fun onDestroy() {
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
        if (useFakeGroupData) return

        registerNetworkCallback()
        serviceScope.launch {
            val group = groupRepository.activeGroup() ?: return@launch stopRoute()
            // Интерфейс запускает службу при каждом возврате на экран, поэтому та же группа ничего не перезапускает.
            if (route?.group == group) return@launch
            route?.stop()
            route = RouteSession(group).also { it.start() }
        }
    }

    private fun stopRoute() {
        stopComponents()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopComponents() {
        route?.stop()
        route = null
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        networkCallback = null
    }

    /** Подписывается на появление сети с интернетом: тогда точки сразу уходят на сервер через `UploadScheduler.uploadNow`. */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = uploadScheduler.uploadNow()
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
            // Кого слышали в прошлой группе, в состоянии новой группы не нужно.
            presence.clear()
            // Движок запускается до транспорта: PeerTransport не обещает хранить события, пока их никто не читает.
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

            val routeState = viewProvider.view
                .filterNotNull()
                .filter { it.group.tag == group.tag }
                .map { it.route }
                .stateIn(scope, SharingStarted.Eagerly, RouteState.LOBBY)
            scope.launch { selfStateProducer.run() }
            scope.launch { statusMonitor.run(group) }
            scope.launch { trackRecorder.run(routeState) }
            scope.launch {
                routeState.first { it == RouteState.FINISHED }
                uploadScheduler.uploadNow()
                delay(config.exchangeAfterFinishMs)
                withContext(Dispatchers.Main) { stopRoute() }
            }
        }

        fun stop() {
            scope.cancel()
            engine.stop()
            transport.stop()
            powerTools.releaseWakeLock()
        }

        /** true для outsider (телефона из другой части сети): последний контакт больше 30 с или группа его ещё не знает. */
        private fun isOutsider(member: MemberId): Boolean {
            val lastContact = viewProvider.view.value?.members?.firstOrNull { it.id == member }?.lastContact
            return lastContact == null || lastContact.ms > PresenceTracker.HEARD_NOW_MS
        }
    }

    companion object {
        private const val TAG = "RouteService"
        private const val ACTION_START = "ru.pereklichka.action.START_ROUTE"
        private const val ACTION_STOP = "ru.pereklichka.action.STOP_ROUTE"

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

        /** Останавливает службу: кнопка «Выключить» после завершения маршрута. */
        fun stop(context: Context) {
            context.startService(Intent(context, RouteService::class.java).setAction(ACTION_STOP))
        }

        /**
         * Типы службы по выданным разрешениям, 0 если нельзя ни один: без разрешения Android 14 роняет запуск
         * службы с этим типом. До Android 10 `ServiceCompat.startForeground` типы не передаёт, поэтому константы
         * новых версий здесь безопасны.
         */
        @SuppressLint("InlinedApi")
        private fun foregroundServiceTypes(context: Context): Int {
            var types = 0
            if (RequiredPermissions.allGranted(context, RequiredPermissions.exchange())) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            // Тип location берётся только с точной геолокацией: по приблизительной трек не записать.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            return types
        }
    }
}
