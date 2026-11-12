package ru.pereklichka.domain.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import ru.pereklichka.domain.model.MemberId

/**
 * Связь с соседями: находит телефоны своей группы, держит с ними соединения и передаёт байты.
 *
 * Реализации: `NearbyPeerTransport` на телефоне и [ru.pereklichka.domain.testing.InMemoryTransport] в тестах.
 * Содержимое сообщений транспорт не разбирает, их разбирает [SyncEngine]. Наружу выходят только [MemberId],
 * а соответствие с `endpointId` Nearby хранится внутри. Запускает и останавливает транспорт `RouteService`,
 * события читает движок.
 */
interface PeerTransport {

    /** Подключения, отключения и входящие сообщения в порядке прихода. Читает их один подписчик, движок. */
    val events: Flow<TransportEvent>

    /** Соседи, с которыми сейчас есть соединение. Показывает экран «Для разработчиков». */
    val connectedPeers: StateFlow<Set<MemberId>>

    /** Включает advertising и discovery. На соединения прошлого запуска не рассчитывает: после смерти процесса Nearby их уже закрыл. */
    fun start(config: TransportConfig)

    /** Рвёт все соединения и выключает advertising и discovery. */
    fun stop()

    /**
     * true, если транспорт передал байты (у Nearby это статус SUCCESS); false при ошибке или таймауте. Принял ли
     * сообщение сосед, отсюда не узнать, поэтому движок берёт результат только для счётчиков, а недошедшее
     * досылается после следующей сводки.
     */
    suspend fun send(peer: MemberId, bytes: ByteArray): Boolean

    /** Рвёт соединение с соседом, например молчащим дольше `SyncConfig.silentDisconnectMs`. Discovery потом найдёт его заново. */
    fun disconnect(peer: MemberId)
}

/** Событие транспорта для движка. */
sealed interface TransportEvent {
    data class Connected(val peer: MemberId) : TransportEvent
    data class Disconnected(val peer: MemberId) : TransportEvent

    /** Обычный класс без `data`: у массива байтов нет осмысленного equals. */
    class Received(val peer: MemberId, val bytes: ByteArray) : TransportEvent
}

/** С какими параметрами запустить транспорт. Собирает `RouteService` из активной группы. */
data class TransportConfig(
    /** `ru.pereklichka.g.<tag>`: телефоны чужих групп друг друга не находят. */
    val serviceId: String,
    /** Мой member_id, он же имя устройства в Nearby. */
    val me: MemberId,
    /** Список блокировки с экрана «Для разработчиков»: к этим участникам не подключаться, их сообщения отбрасывать. */
    val blocked: StateFlow<Set<MemberId>>,
    /**
     * true для outsider: участника, которого группа не слышала больше 30 с или ещё не знает (вернувшийся отставший,
     * половина группы, шедшая отдельно). Только такому телефону транспорт отдаёт четвёртое соединение.
     */
    val isOutsider: (MemberId) -> Boolean,
)
