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
 * Изменяемое состояние (соответствие endpointId и member_id, счётчики повторов, ждущие отправки) живёт под
 * [lock]: обратные вызовы идут с главного потока, а [send] — с потока движка.
 *
 * Задача: docs/team/olga-transport.md
 */
@SuppressLint("MissingPermission") // Разрешения запрашивает экран разрешений до запуска службы.
@Singleton
class NearbyPeerTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val radioCheck: RadioCheck,
    /** Область для отложенных запросов соединения, повторов и окон discovery, живёт дольше одного запуска транспорта. */
    @ApplicationScope private val scope: CoroutineScope,
) : PeerTransport {

    private val connections = Nearby.getConnectionsClient(context)

    private val eventsChannel = Channel<TransportEvent>(Channel.UNLIMITED)
    override val events: Flow<TransportEvent> = eventsChannel.receiveAsFlow()

    private val peers = MutableStateFlow<Set<MemberId>>(emptySet())
    override val connectedPeers: StateFlow<Set<MemberId>> = peers.asStateFlow()

    /** Замок на изменяемое состояние ниже: обратные вызовы приходят с главного потока, [send] — с движка. */
    private val lock = Any()

    @Volatile
    private var config: TransportConfig? = null

    /** true, когда advertising и цикл discovery подняты и работают. */
    @Volatile
    private var running = false

    /** Поднят ли сейчас discovery; им управляет цикл по [ConnectionPolicy]. */
    @Volatile
    private var discovering = false

    /** Установленные соединения: endpointId <-> member_id. */
    private val endpointToMember = HashMap<String, MemberId>()
    private val memberToEndpoint = HashMap<MemberId, String>()

    /** Устанавливаемые соединения: endpointId -> member_id (приняты или запрошены, ещё без результата). */
    private val pendingMembers = HashMap<String, MemberId>()

    /** endpointId, которым сами звонили requestConnection: за повторы после неудачи отвечаем только мы. */
    private val requestedByUs = HashSet<String>()
    private val failedAttempts = HashMap<String, Int>()
    private val requestJobs = HashMap<String, Job>()

    /** Отправки, ждущие итог в onPayloadTransferUpdate: payloadId -> результат. */
    private val pendingSends = HashMap<Long, CompletableDeferred<Boolean>>()

    private var discoveryJob: Job? = null
    private var blockedJob: Job? = null
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
            Log.w(TAG, "Google Play Services недоступны, Nearby не стартует")
            return
        }
        // Nearby сам Bluetooth не включает: человек включает его по баннеру на экране, а мы подхватываем.
        // Приёмник нужен и при включённом Bluetooth: если радио выключат и включат заново,
        // Nearby надо перезапустить, иначе после STATE_ON оно останется мёртвым.
        registerBluetoothReceiver()
        if (radioCheck.isBluetoothEnabled()) {
            beginNearby(config)
        }
    }

    override fun stop() {
        discoveryJob?.cancel()
        discoveryJob = null
        blockedJob?.cancel()
        blockedJob = null
        unregisterBluetoothReceiver()
        connections.stopAdvertising()
        connections.stopDiscovery()
        connections.stopAllEndpoints()
        synchronized(lock) {
            running = false
            discovering = false
            config = null
            val members = memberToEndpoint.keys.toList()
            endpointToMember.clear()
            memberToEndpoint.clear()
            pendingMembers.clear()
            requestedByUs.clear()
            failedAttempts.clear()
            requestJobs.values.forEach { it.cancel() }
            requestJobs.clear()
            pendingSends.values.forEach { it.complete(false) }
            pendingSends.clear()
            members
        }.forEach { member ->
            eventsChannel.trySend(TransportEvent.Disconnected(member))
        }
        peers.value = emptySet()
    }

    override suspend fun send(peer: MemberId, bytes: ByteArray): Boolean {
        val endpointId = synchronized(lock) { memberToEndpoint[peer] } ?: return false
        val payload = Payload.fromBytes(bytes)
        val result = CompletableDeferred<Boolean>()
        synchronized(lock) { pendingSends[payload.id] = result }
        connections.sendPayload(endpointId, payload)
            .addOnFailureListener {
                synchronized(lock) { pendingSends.remove(payload.id) }
                result.complete(false)
            }
        val delivered = withTimeoutOrNull(SEND_TIMEOUT_MS) { result.await() }
        if (delivered == null) synchronized(lock) { pendingSends.remove(payload.id) }
        return delivered == true
    }

    override fun disconnect(peer: MemberId) {
        val endpointId = synchronized(lock) { memberToEndpoint[peer] } ?: return
        // На своей стороне Nearby onDisconnected после disconnectFromEndpoint не вызывает,
        // поэтому dropEndpoint сам шлёт событие Disconnected в events.
        dropEndpoint(endpointId, notifyNearby = true)
    }

    /** Поднимает advertising и цикл discovery. Вызывается при старте и при включении Bluetooth. */
    private fun beginNearby(config: TransportConfig) {
        synchronized(lock) {
            if (running) return
            running = true
        }
        connections.startAdvertising(config.me.value, config.serviceId, connectionLifecycle, advertisingOptions)
            .addOnFailureListener { e -> Log.w(TAG, "advertising не поднялся: ${e.message}") }
        discoveryJob = scope.launch { discoveryLoop() }
    }

    /**
     * Управляет discovery по политике: постоянно, пока соединений меньше трёх, а при трёх — окнами
     * [ConnectionPolicy.DISCOVERY_WINDOW_MS] раз в [ConnectionPolicy.DISCOVERY_PERIOD_MS], чтобы замечать
     * телефоны, которых не слышит никто из моей части сети.
     */
    private suspend fun discoveryLoop() {
        while (currentCoroutineContext().isActive) {
            val connected = synchronized(lock) { memberToEndpoint.size }
            if (connected < ConnectionPolicy.MAX_REGULAR_CONNECTIONS) {
                startDiscoveryIfNeeded()
                delay(5_000)
            } else {
                stopDiscoveryIfRunning()
                delay(ConnectionPolicy.DISCOVERY_PERIOD_MS - ConnectionPolicy.DISCOVERY_WINDOW_MS)
                startDiscoveryIfNeeded()
                delay(ConnectionPolicy.DISCOVERY_WINDOW_MS)
            }
        }
    }

    private fun startDiscoveryIfNeeded() {
        val cfg = config ?: return
        synchronized(lock) {
            if (!running || discovering) return
            discovering = true
        }
        connections.startDiscovery(cfg.serviceId, discoveryCallback, discoveryOptions)
            .addOnFailureListener { e ->
                // Причина (нет разрешений, выключено радио) видна только в логе.
                Log.w(TAG, "discovery не поднялся: ${e.message}")
                synchronized(lock) { discovering = false }
            }
    }

    private fun stopDiscoveryIfRunning() {
        synchronized(lock) {
            if (!discovering) return
            discovering = false
        }
        connections.stopDiscovery()
    }

    /**
     * Перезапускает discovery после разрыва соединения: onEndpointFound приходит один раз за сеанс,
     * и без перезапуска ушедший и вернувшийся телефон снова не найдётся. При трёх соединениях
     * discovery и так идёт окнами, отдельно его дёргать не нужно.
     */
    private fun restartDiscovery() {
        val connected = synchronized(lock) { memberToEndpoint.size }
        if (connected >= ConnectionPolicy.MAX_REGULAR_CONNECTIONS) return
        stopDiscoveryIfRunning()
        startDiscoveryIfNeeded()
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val cfg = config ?: return
            val member = MemberId(info.endpointName)
            if (!mayConnect(member)) return
            // Первым стучится меньший member_id, больший ждёт: так реже бывают встречные запросы,
            // на которых Nearby отклоняет одно из двух соединений.
            scheduleRequest(endpointId, member, ConnectionPolicy.requestDelayMs(cfg.me, member))
        }

        override fun onEndpointLost(endpointId: String) {
            synchronized(lock) { requestJobs.remove(endpointId)?.cancel() }
        }
    }

    /** Планирует запрос соединения через [delayMs]; за это время сосед может подключиться сам, тогда пропускаем. */
    private fun scheduleRequest(endpointId: String, member: MemberId, delayMs: Long) {
        val job = scope.launch {
            delay(delayMs)
            if (mayConnect(member)) request(endpointId, member)
        }
        synchronized(lock) {
            requestJobs[endpointId]?.cancel()
            requestJobs[endpointId] = job
        }
    }

    private fun request(endpointId: String, member: MemberId) {
        val cfg = config ?: return
        synchronized(lock) { requestedByUs += endpointId }
        connections.requestConnection(cfg.me.value, endpointId, connectionLifecycle, connectionOptions)
            .addOnFailureListener { e ->
                Log.w(TAG, "requestConnection к ${member.value} не удался: ${e.message}")
                onRequestFailure(endpointId, member, e)
            }
    }

    private fun onRequestFailure(endpointId: String, member: MemberId, error: Exception) {
        if ((error as? ApiException)?.statusCode == ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR) {
            // Совет разработчиков Nearby при этой ошибке: остановить advertising и discovery и поднять заново.
            restartRadio()
        }
        val attempt = synchronized(lock) { (failedAttempts[endpointId] ?: 0) + 1 }
        synchronized(lock) { failedAttempts[endpointId] = attempt }
        scheduleRetry(endpointId, member, attempt)
    }

    private fun scheduleRetry(endpointId: String, member: MemberId, attempt: Int) {
        val job = scope.launch {
            delay(ConnectionPolicy.retryDelayMs(attempt))
            // За паузу сосед мог подключиться сам или правила могли поменяться: тогда повтор не нужен.
            if (mayConnect(member)) request(endpointId, member)
        }
        synchronized(lock) { requestJobs[endpointId] = job }
    }

    private val connectionLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val member = MemberId(info.endpointName)
            // Принимаем без сверки кода аутентификации Nearby, если соединение разрешает политика.
            if (mayConnect(member)) {
                synchronized(lock) { pendingMembers[endpointId] = member }
                connections.acceptConnection(endpointId, payloadCallback)
            } else {
                connections.rejectConnection(endpointId)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val member = synchronized(lock) { pendingMembers[endpointId] } ?: return
            if (result.status.isSuccess) {
                synchronized(lock) {
                    pendingMembers.remove(endpointId)
                    requestedByUs.remove(endpointId)
                    failedAttempts.remove(endpointId)
                    endpointToMember[endpointId] = member
                    memberToEndpoint[member] = endpointId
                }
                peers.update { it + member }
                eventsChannel.trySend(TransportEvent.Connected(member))
                restartDiscovery()
            } else {
                // За повтор отвечает только инициатор: принявшая сторона просто ждёт следующего запроса.
                val ours = synchronized(lock) {
                    pendingMembers.remove(endpointId)
                    requestedByUs.remove(endpointId)
                }
                if (ours) onRequestFailure(endpointId, member, ApiException(result.status))
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
        if (notifyNearby) connections.disconnectFromEndpoint(endpointId)
        val member = synchronized(lock) {
            pendingMembers.remove(endpointId)
            requestedByUs.remove(endpointId)
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
        return ConnectionPolicy.mayConnect(
            peer = member,
            connected = peers.value,
            blocked = cfg.blocked.value,
            isOutsider = cfg.isOutsider(member),
        )
    }

    /** Совет разработчиков Nearby при STATUS_ENDPOINT_IO_ERROR: стоп и повторный запуск радио. */
    private fun restartRadio() {
        val cfg = config ?: return
        connections.stopAdvertising()
        stopDiscoveryIfRunning()
        connections.startAdvertising(cfg.me.value, cfg.serviceId, connectionLifecycle, advertisingOptions)
            .addOnFailureListener { e -> Log.w(TAG, "advertising после ошибки радио не поднялся: ${e.message}") }
        startDiscoveryIfNeeded()
    }

    /** Ждём включения Bluetooth человеком: Nearby сам его не включает. */
    private fun registerBluetoothReceiver() {
        if (bluetoothReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON) {
                    // Приёмник живёт до stop(): после включения радио Nearby либо поднимаем,
                    // либо перезапускаем, если оно работало и умерло вместе с Bluetooth.
                    val cfg = config ?: return
                    if (!running) beginNearby(cfg) else restartRadio()
                }
            }
        }
        try {
            context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            bluetoothReceiver = receiver
        } catch (e: RuntimeException) {
            Log.w(TAG, "Не удалось подписаться на состояние Bluetooth: ${e.message}")
        }
    }

    private fun unregisterBluetoothReceiver() {
        bluetoothReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        bluetoothReceiver = null
    }

    private companion object {
        const val TAG = "NearbyPeerTransport"

        /** Сколько send ждёт итог передачи. Движку результат нужен лишь для счётчиков. */
        const val SEND_TIMEOUT_MS = 15_000L
    }
}
