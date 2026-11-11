package ru.pereklichka.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionOptions
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionType
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.pereklichka.di.ApplicationScope
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.sync.PeerTransport
import ru.pereklichka.domain.sync.TransportConfig
import ru.pereklichka.domain.sync.TransportEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PeerTransport] на Nearby Connections: находит телефоны своей группы через advertising и discovery, держит
 * с ними соединения и передаёт байты движку. Запускает и останавливает его `RouteService`.
 *
 * Стратегия `P2P_CLUSTER`, serviceId с тегом группы, имя устройства равно `member_id`. `NON_DISRUPTIVE`
 * не даёт Nearby переключать Wi-Fi, и телефон не теряет интернет для отправки на сервер. Кого подключать,
 * решает [ConnectionPolicy], радио проверяет [RadioCheck].
 *
 * Обратные вызовы Nearby приходят в главный поток, поэтому их нужно сразу перекладывать в [eventsChannel].
 * Исключение одно: статус отправки из `onPayloadTransferUpdate` надо отдавать ждущему `send` прямо из
 * обратного вызова, потому что через [eventsChannel] движок прочитает его только после возврата из `send`.
 * SUCCESS там значит, что Nearby передал байты, а приём соседнее приложение не подтверждает.
 *
 * Изменяемое состояние (соответствие endpointId и member_id, учёт запросов [ConnectionRequests], ждущие отправки)
 * живёт под [lock]: обратные вызовы идут с главного потока, а [send] — с потока движка.
 *
 * `RouteService` запускается и с одной геолокацией, без разрешений Bluetooth. Тогда задачи Nearby завершаются
 * ошибкой, SecurityException из вызовов Nearby ловит [nearby], и в обоих случаях причина попадает в лог. Цикл
 * [radioLoop] снова поднимает advertising и discovery и так подхватывает разрешения, выданные позже.
 *
 * Задача: docs/team/olga-transport.md
 */
@SuppressLint("MissingPermission") // Без разрешений вызовы Nearby пишут отказ в лог, см. выше.
@Singleton
class NearbyPeerTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val radioCheck: RadioCheck,
    /** Область для цикла радио, отложенных запросов соединения и повторов, живёт дольше одного запуска транспорта. */
    @ApplicationScope private val scope: CoroutineScope,
) : PeerTransport {

    private val connections = Nearby.getConnectionsClient(context)

    /** Канал событий текущего запуска. [stop] заменяет его новым, и события одного запуска в следующий не попадают. */
    @Volatile
    private var eventsChannel = Channel<TransportEvent>(Channel.UNLIMITED)

    /** Подписка читает канал, текущий в момент подписки: движок подписывается на каждый запуск заново, после [stop] прошлого. */
    override val events: Flow<TransportEvent> = flow { emitAll(eventsChannel.receiveAsFlow()) }

    private val peers = MutableStateFlow<Set<MemberId>>(emptySet())
    override val connectedPeers: StateFlow<Set<MemberId>> = peers.asStateFlow()

    /** Замок на изменяемое состояние ниже: обратные вызовы приходят с главного потока, [send] — с движка. */
    private val lock = Any()

    @Volatile
    private var config: TransportConfig? = null

    /**
     * true от [beginNearby] до [stop]: Google Play Services есть, Bluetooth включён, и цикл [radioLoop] держит
     * advertising и discovery. Работают ли они сейчас, хранят [advertising] и [discovering].
     */
    @Volatile
    private var running = false

    /** Поднят ли advertising или ждёт ответа startAdvertising. Отказ сбрасывает флаг, и [radioLoop] пробует снова. */
    @Volatile
    private var advertising = false

    /** Поднят ли сейчас discovery; им управляет цикл по [ConnectionPolicy]. */
    @Volatile
    private var discovering = false

    /** Установленные соединения: endpointId <-> member_id. */
    private val endpointToMember = HashMap<String, MemberId>()
    private val memberToEndpoint = HashMap<MemberId, String>()

    /** Устанавливаемые соединения: endpointId -> member_id (приняты или запрошены, ещё без результата). */
    private val pendingMembers = HashMap<String, MemberId>()

    /** Свои запросы соединения: какие ждут результата и нужен ли повтор после неудачи. */
    private val requests = ConnectionRequests()
    private val requestJobs = HashMap<String, Job>()

    /** Отправки, ждущие итог в onPayloadTransferUpdate: payloadId -> результат. */
    private val pendingSends = HashMap<Long, CompletableDeferred<Boolean>>()

    private var radioJob: Job? = null
    private var blockedJob: Job? = null

    /** Приёмник состояния Bluetooth, под [lock]: регистрирует его цикл, а снимает [stop] с главного потока. */
    private var bluetoothReceiver: BroadcastReceiver? = null

    private val advertisingOptions = AdvertisingOptions.Builder()
        .setStrategy(Strategy.P2P_CLUSTER)
        .setConnectionType(ConnectionType.NON_DISRUPTIVE)
        .build()

    // У DiscoveryOptions параметра connectionType нет: искать соседей можно без трогания Wi-Fi и так.
    private val discoveryOptions = DiscoveryOptions.Builder()
        .setStrategy(Strategy.P2P_CLUSTER)
        .build()

    private val connectionOptions = ConnectionOptions.Builder()
        .setConnectionType(ConnectionType.NON_DISRUPTIVE)
        .build()

    override fun start(config: TransportConfig) {
        this.config = config
        // Список блокировки из диагностики: попавший в него при живом соединении отключается,
        // а его сообщения отбрасывает onPayloadReceived.
        blockedJob?.cancel()
        blockedJob = scope.launch {
            config.blocked.collect { blocked ->
                val toDrop = synchronized(lock) { memberToEndpoint.keys.filter { it in blocked } }
                toDrop.forEach { disconnect(it) }
            }
        }
        if (!radioCheck.isPlayServicesAvailable()) {
            Log.w(TAG, "Google Play Services недоступны, Nearby поднимется, когда они появятся")
        }
        radioJob?.cancel()
        radioJob = scope.launch { radioLoop(config) }
    }

    override fun stop() {
        radioJob?.cancel()
        radioJob = null
        blockedJob?.cancel()
        blockedJob = null
        // Сначала состояние, потом вызовы Nearby: шаг цикла, который шёл одновременно со stop, после своего
        // startAdvertising или startDiscovery увидит running = false и остановит их сам.
        synchronized(lock) {
            running = false
            advertising = false
            discovering = false
            config = null
            endpointToMember.clear()
            memberToEndpoint.clear()
            pendingMembers.clear()
            requests.clear()
            requestJobs.values.forEach { it.cancel() }
            requestJobs.clear()
            pendingSends.values.forEach { it.complete(false) }
            pendingSends.clear()
        }
        unregisterBluetoothReceiver()
        nearby("stopAdvertising") { connections.stopAdvertising() }
        nearby("stopDiscovery") { connections.stopDiscovery() }
        nearby("stopAllEndpoints") { connections.stopAllEndpoints() }
        // Движок этого запуска уже остановлен. Disconnected об оборванных соединениях и поздние события
        // следующему движку не нужны, он начинает с чистого листа.
        val finished = eventsChannel
        eventsChannel = Channel(Channel.UNLIMITED)
        finished.close()
        peers.value = emptySet()
    }

    override suspend fun send(peer: MemberId, bytes: ByteArray): Boolean {
        val endpointId = synchronized(lock) { memberToEndpoint[peer] } ?: return false
        val payload = Payload.fromBytes(bytes)
        val result = CompletableDeferred<Boolean>()
        synchronized(lock) { pendingSends[payload.id] = result }
        try {
            val task = nearby("sendPayload") { connections.sendPayload(endpointId, payload) } ?: return false
            task.addOnFailureListener { result.complete(false) }
            return withTimeoutOrNull(SEND_TIMEOUT_MS) { result.await() } == true
        } finally {
            synchronized(lock) { pendingSends.remove(payload.id) }
        }
    }

    override fun disconnect(peer: MemberId) {
        val endpointId = synchronized(lock) { memberToEndpoint[peer] } ?: return
        // На своей стороне Nearby onDisconnected после disconnectFromEndpoint не вызывает,
        // поэтому dropEndpoint сам шлёт событие Disconnected в events.
        dropEndpoint(endpointId, notifyNearby = true)
    }

    /** Поднимает advertising и discovery для запуска [cfg]. Вызывают цикл [radioLoop] и приёмник Bluetooth. */
    private fun beginNearby(cfg: TransportConfig) {
        synchronized(lock) {
            if (running || config !== cfg) return
            running = true
        }
        startAdvertisingIfNeeded()
        startDiscoveryIfNeeded()
    }

    /**
     * Цикл одного запуска. Поднимает Nearby, когда есть Google Play Services и включён Bluetooth, и снова
     * поднимает advertising после отказа. Discovery ведёт по политике: постоянно, пока соединений меньше трёх,
     * а при трёх — окнами [ConnectionPolicy.DISCOVERY_WINDOW_MS] раз в [ConnectionPolicy.DISCOVERY_PERIOD_MS],
     * чтобы замечать телефоны, которых не слышит никто из моей части сети.
     */
    private suspend fun radioLoop(cfg: TransportConfig) {
        var playServices = false
        while (currentCoroutineContext().isActive) {
            if (!playServices && radioCheck.isPlayServicesAvailable()) {
                playServices = true
                // Приёмник нужен и при включённом Bluetooth: если радио выключат и включат заново,
                // Nearby надо перезапустить, иначе после STATE_ON оно останется мёртвым.
                registerBluetoothReceiver(cfg)
            }
            // Nearby сам Bluetooth не включает: человек включает его по баннеру на экране, а мы подхватываем.
            if (playServices && !running && radioCheck.isBluetoothEnabled()) beginNearby(cfg)
            startAdvertisingIfNeeded()
            val connected = synchronized(lock) { memberToEndpoint.size }
            if (ConnectionPolicy.isDiscoveryAlwaysOn(connected)) {
                startDiscoveryIfNeeded()
                delay(LOOP_STEP_MS)
            } else {
                stopDiscoveryIfRunning()
                delay(ConnectionPolicy.DISCOVERY_PERIOD_MS - ConnectionPolicy.DISCOVERY_WINDOW_MS)
                startDiscoveryIfNeeded()
                delay(ConnectionPolicy.DISCOVERY_WINDOW_MS)
            }
        }
    }

    private fun startAdvertisingIfNeeded() {
        val cfg = config ?: return
        synchronized(lock) {
            if (!running || advertising) return
            advertising = true
        }
        val task = nearby("startAdvertising") {
            connections.startAdvertising(cfg.me.value, cfg.serviceId, connectionLifecycle, advertisingOptions)
        }
        task?.addOnFailureListener { e ->
            if (e.statusCode() == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) return@addOnFailureListener
            // Причина (нет разрешений, выключено радио) видна только в логе.
            Log.w(TAG, "advertising не поднялся: ${e.message}")
            synchronized(lock) { if (config === cfg) advertising = false }
        }
        if (task == null || isStale(cfg)) {
            if (task != null) nearby("stopAdvertising") { connections.stopAdvertising() }
            synchronized(lock) { advertising = false }
        }
    }

    private fun startDiscoveryIfNeeded() {
        val cfg = config ?: return
        synchronized(lock) {
            if (!running || discovering) return
            discovering = true
        }
        val task = nearby("startDiscovery") {
            connections.startDiscovery(cfg.serviceId, discoveryCallback, discoveryOptions)
        }
        task?.addOnFailureListener { e ->
            if (e.statusCode() == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) return@addOnFailureListener
            Log.w(TAG, "discovery не поднялся: ${e.message}")
            synchronized(lock) { if (config === cfg) discovering = false }
        }
        if (task == null || isStale(cfg)) {
            if (task != null) nearby("stopDiscovery") { connections.stopDiscovery() }
            synchronized(lock) { discovering = false }
        }
    }

    /**
     * true, если запуск [cfg] остановили, пока шёл вызов Nearby вне замка. Тогда stopAdvertising или stopDiscovery
     * из [stop] могли выполниться раньше нашего старта, и радио приходится останавливать самим. Если уже начался
     * новый запуск, сброшенный флаг заставит [radioLoop] поднять радио для него заново.
     */
    private fun isStale(cfg: TransportConfig): Boolean = synchronized(lock) { !running || config !== cfg }

    private fun stopDiscoveryIfRunning() {
        synchronized(lock) {
            if (!discovering) return
            discovering = false
        }
        nearby("stopDiscovery") { connections.stopDiscovery() }
    }

    /**
     * Перезапускает discovery после разрыва соединения: onEndpointFound приходит один раз за сеанс,
     * и без перезапуска ушедший и вернувшийся телефон снова не найдётся. При трёх соединениях
     * discovery и так идёт окнами, отдельно его дёргать не нужно.
     */
    private fun restartDiscovery() {
        val connected = synchronized(lock) { memberToEndpoint.size }
        if (!ConnectionPolicy.isDiscoveryAlwaysOn(connected)) return
        stopDiscoveryIfRunning()
        startDiscoveryIfNeeded()
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val cfg = config ?: return
            val member = MemberId(info.endpointName)
            // Наш запрос к нему ещё ждёт результата. Если он не удастся, повтор запланирует onRequestFailure.
            if (!synchronized(lock) { requests.onFound(endpointId) }) return
            if (!mayConnect(member)) return
            // Первым стучится меньший member_id, больший ждёт: так реже бывают встречные запросы,
            // на которых Nearby отклоняет одно из двух соединений.
            scheduleRequest(endpointId, member, ConnectionPolicy.requestDelayMs(cfg.me, member))
        }

        override fun onEndpointLost(endpointId: String) {
            synchronized(lock) {
                requests.onLost(endpointId)
                requestJobs.remove(endpointId)?.cancel()
            }
        }
    }

    /**
     * Планирует запрос соединения через [delayMs], первый или повтор после неудачи. За паузу сосед может
     * подключиться сам или могут поменяться правила, тогда запрос не нужен.
     */
    private fun scheduleRequest(endpointId: String, member: MemberId, delayMs: Long) {
        val job = scope.launch {
            delay(delayMs)
            if (mayConnect(member)) request(endpointId, member)
        }
        synchronized(lock) { requestJobs.put(endpointId, job)?.cancel() }
    }

    private fun request(endpointId: String, member: MemberId) {
        val cfg = config ?: return
        if (!synchronized(lock) { requests.tryStart(endpointId) }) return
        val task = nearby("requestConnection") {
            connections.requestConnection(cfg.me.value, endpointId, connectionLifecycle, connectionOptions)
        } ?: return onRequestFailure(endpointId, member, statusCode = null)
        task.addOnFailureListener { e ->
            Log.w(TAG, "requestConnection к ${member.value} не удался: ${e.message}")
            onRequestFailure(endpointId, member, e.statusCode())
        }
    }

    /** Планирует повтор запроса, если его требует [ConnectionRequests.onFailure]. */
    private fun onRequestFailure(endpointId: String, member: MemberId, statusCode: Int?) {
        val retryDelayMs = synchronized(lock) { requests.onFailure(endpointId, statusCode) } ?: return
        if (statusCode == ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR) {
            // Совет разработчиков Nearby при этой ошибке: остановить advertising и discovery и поднять заново.
            restartRadio()
        }
        scheduleRequest(endpointId, member, retryDelayMs)
    }

    private val connectionLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val member = MemberId(info.endpointName)
            // Принимаем без сверки кода аутентификации Nearby, если соединение разрешает политика.
            if (mayConnect(member)) {
                synchronized(lock) { pendingMembers[endpointId] = member }
                nearby("acceptConnection") { connections.acceptConnection(endpointId, payloadCallback) }
            } else {
                nearby("rejectConnection") { connections.rejectConnection(endpointId) }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val member = synchronized(lock) { pendingMembers[endpointId] }
            if (member == null) {
                // Соединение отклонили мы сами в onConnectionInitiated, повторять запрос не нужно.
                synchronized(lock) { requests.forget(endpointId) }
                return
            }
            if (result.status.isSuccess) {
                synchronized(lock) {
                    pendingMembers.remove(endpointId)
                    requests.onConnected(endpointId)
                    endpointToMember[endpointId] = member
                    memberToEndpoint[member] = endpointId
                }
                peers.update { it + member }
                eventsChannel.trySend(TransportEvent.Connected(member))
                restartDiscovery()
            } else {
                // За повтор отвечает только инициатор, это решает ConnectionRequests.onFailure.
                synchronized(lock) { pendingMembers.remove(endpointId) }
                onRequestFailure(endpointId, member, result.status.statusCode)
            }
        }

        override fun onDisconnected(endpointId: String) {
            dropEndpoint(endpointId, notifyNearby = false)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val member = synchronized(lock) { endpointToMember[endpointId] } ?: return
            if (config?.blocked?.value?.contains(member) == true) return
            val bytes = payload.asBytes() ?: return
            eventsChannel.trySend(TransportEvent.Received(member, bytes))
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val ok = when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> true
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> false
                else -> return // передача ещё идёт
            }
            // Результат отдаём ждущему send прямо здесь: через eventsChannel движок прочитал бы его
            // только после возврата из send, а send ждёт именно его.
            synchronized(lock) { pendingSends.remove(update.payloadId) }?.complete(ok)
        }
    }

    /** Разрывает соединение с endpointId, шлёт событие и перезапускает discovery, чтобы найти его заново. */
    private fun dropEndpoint(endpointId: String, notifyNearby: Boolean) {
        if (notifyNearby) nearby("disconnectFromEndpoint") { connections.disconnectFromEndpoint(endpointId) }
        val member = synchronized(lock) {
            pendingMembers.remove(endpointId)
            requests.forget(endpointId)
            requestJobs.remove(endpointId)?.cancel()
            endpointToMember.remove(endpointId)?.also { memberToEndpoint.remove(it) }
        }
        if (member != null) {
            peers.update { it - member }
            eventsChannel.trySend(TransportEvent.Disconnected(member))
            restartDiscovery()
        }
    }

    /** true, если с [member] можно соединиться по [ConnectionPolicy] и текущему списку блокировки. */
    private fun mayConnect(member: MemberId): Boolean {
        val cfg = config ?: return false
        // Устанавливаемые соединения занимают слот: без них несколько одновременных
        // запросов проходили бы мимо лимита и упирались бы в отказ Nearby.
        val connecting = synchronized(lock) { pendingMembers.values.toSet() }
        return ConnectionPolicy.mayConnect(
            peer = member,
            connected = peers.value + connecting,
            blocked = cfg.blocked.value,
            isOutsider = cfg.isOutsider(member),
        )
    }

    /** Останавливает и снова поднимает радио: после STATUS_ENDPOINT_IO_ERROR и после повторного включения Bluetooth. */
    private fun restartRadio() {
        if (!running) return
        nearby("stopAdvertising") { connections.stopAdvertising() }
        // Флаг сбрасывается после остановки: иначе цикл мог бы поднять advertising между сбросом и stopAdvertising.
        synchronized(lock) { advertising = false }
        stopDiscoveryIfRunning()
        startAdvertisingIfNeeded()
        startDiscoveryIfNeeded()
    }

    /** Ждём включения Bluetooth человеком: Nearby сам его не включает. */
    private fun registerBluetoothReceiver(cfg: TransportConfig) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON) {
                    // Приёмник живёт до stop(): после включения радио Nearby либо поднимаем,
                    // либо перезапускаем, если оно работало и умерло вместе с Bluetooth.
                    val current = config ?: return
                    if (!running) beginNearby(current) else restartRadio()
                }
            }
        }
        synchronized(lock) {
            // Приёмник, зарегистрированный после stop() этого запуска, уже никто бы не снял.
            if (bluetoothReceiver != null || config !== cfg) return
            try {
                context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
                bluetoothReceiver = receiver
            } catch (e: RuntimeException) {
                Log.w(TAG, "Не удалось подписаться на состояние Bluetooth: ${e.message}")
            }
        }
    }

    private fun unregisterBluetoothReceiver() {
        val receiver = synchronized(lock) { bluetoothReceiver.also { bluetoothReceiver = null } }
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
    }

    /** Выполняет вызов Nearby [block]. Вместо SecurityException возвращает null и пишет в лог имя вызова [call]. */
    private inline fun <T> nearby(call: String, block: () -> T): T? = try {
        block()
    } catch (e: SecurityException) {
        Log.w(TAG, "$call: нет разрешения (${e.message})")
        null
    }

    private fun Exception.statusCode(): Int? = (this as? ApiException)?.statusCode

    private companion object {
        const val TAG = "NearbyPeerTransport"

        /** Шаг цикла [radioLoop], пока discovery включён постоянно. */
        const val LOOP_STEP_MS = 5_000L

        /** Сколько send ждёт итог передачи. Движку результат нужен лишь для счётчиков. */
        const val SEND_TIMEOUT_MS = 15_000L
    }
}
