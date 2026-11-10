package ru.pereklichka.domain.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import ru.pereklichka.domain.model.MemberId

/**
 * Связь с соседями. На телефоне это `NearbyPeerTransport`, в тестах `InMemoryTransport`.
 *
 * Транспорт находит телефоны своей группы, держит с ними соединения и передаёт байты. Содержимое
 * сообщений он не разбирает: это работа [SyncEngine]. Наружу выходят только [MemberId], соответствие
 * с `endpointId` Nearby хранится внутри.
 *
 * Запускает и останавливает транспорт `RouteService`, события читает движок.
 */
interface PeerTransport {

    /** Подключения, отключения и входящие сообщения по порядку. Читает один подписчик, движок. */
    val events: Flow<TransportEvent>

    /** Соседи, с которыми сейчас есть соединение. Показывает диагностика. */
    val connectedPeers: StateFlow<Set<MemberId>>

    /** Начинает рекламу и поиск. После перезапуска процесса поднимает всё с нуля. */
    fun start(config: TransportConfig)

    /** Рвёт все соединения и выключает радио. */
    fun stop()

    /**
     * true, если Nearby сообщил SUCCESS для этого сообщения; false при ошибке или таймауте.
     * Движок использует результат только для диагностики: доставку обеспечивает сверка сводок.
     */
    suspend fun send(peer: MemberId, bytes: ByteArray): Boolean

    /** Рвёт соединение с соседом, например молчащим 45 секунд. Обнаружение потом найдёт его заново. */
    fun disconnect(peer: MemberId)
}

/** Событие транспорта для движка. */
sealed interface TransportEvent {
    data class Connected(val peer: MemberId) : TransportEvent
    data class Disconnected(val peer: MemberId) : TransportEvent

    /** Обычный класс, а не data: у массива байтов нет осмысленного equals. */
    class Received(val peer: MemberId, val bytes: ByteArray) : TransportEvent
}

/** С какими параметрами запустить транспорт. Собирает `RouteService` из активной группы. */
data class TransportConfig(
    /** `ru.pereklichka.g.<tag>`: телефоны чужих групп друг друга не находят. */
    val serviceId: String,
    /** Мой member_id, он же имя устройства в Nearby. */
    val me: MemberId,
    /** Список блокировки из диагностики: к ним не подключаться, их сообщения отбрасывать. */
    val blocked: StateFlow<Set<MemberId>>,
    /** Последний контакт с участником больше 30 с или участник неизвестен: ему можно отдать четвёртый слот. */
    val isOutsider: (MemberId) -> Boolean,
)
