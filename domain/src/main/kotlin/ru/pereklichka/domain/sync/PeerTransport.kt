package ru.pereklichka.domain.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import ru.pereklichka.domain.model.MemberId

/**
 * Связь с соседями: находит телефоны своей группы, держит с ними соединения и передаёт байты.
 *
 * На телефоне это `NearbyPeerTransport`, в тестах [ru.pereklichka.domain.testing.InMemoryTransport]. Содержимое
 * сообщений транспорт не разбирает, это работа [SyncEngine]. Наружу выходят только [MemberId], соответствие
 * с `endpointId` Nearby хранится внутри. Запускает и останавливает транспорт `RouteService`, события читает
 * движок.
 */
interface PeerTransport {

    /** Подключения, отключения и входящие сообщения в порядке прихода. Читает их один подписчик, движок. */
    val events: Flow<TransportEvent>

    /** Соседи, с которыми сейчас есть соединение. Показывает диагностика. */
    val connectedPeers: StateFlow<Set<MemberId>>

    /** Включает advertising и discovery. На соединения прошлого запуска не рассчитывает: после смерти процесса Nearby их уже закрыл. */
    fun start(config: TransportConfig)

    /** Рвёт все соединения и выключает advertising и discovery. */
    fun stop()

    /**
     * true, если Nearby сообщил SUCCESS для этого сообщения; false при ошибке или таймауте.
     * Движок использует результат только для диагностики: доставку обеспечивает сверка сводок.
     */
    suspend fun send(peer: MemberId, bytes: ByteArray): Boolean

    /** Рвёт соединение с соседом, например молчащим 45 секунд. Discovery потом найдёт его заново. */
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
    /** Список блокировки из диагностики: к этим участникам не подключаться, их сообщения отбрасывать. */
    val blocked: StateFlow<Set<MemberId>>,
    /**
     * true для outsider, то есть телефона из другой части сети: последний контакт с ним больше 30 с или группа
     * его ещё не знает. Только такому телефону можно отдать четвёртое соединение.
     */
    val isOutsider: (MemberId) -> Boolean,
)
