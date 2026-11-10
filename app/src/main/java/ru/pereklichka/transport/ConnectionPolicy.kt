package ru.pereklichka.transport

import ru.pereklichka.domain.model.MemberId

/**
 * Политика соединений из `docs/03-sync-protocol.md`, раздел «Транспорт поверх Nearby Connections»: с кем
 * соединяться, кто запрашивает первым, когда повторять и когда включать discovery. Чистые функции без Android,
 * их вызывает [NearbyPeerTransport] и проверяет `ConnectionPolicyTest`.
 *
 * Обычных соединений до [MAX_REGULAR_CONNECTIONS], четвёртое только для outsider, то есть телефона из другой
 * части сети. Если бы любой телефон мог занять четвёртое соединение,
 * группа быстро заполнила бы все слоты, и вернувшемуся отставшему не досталось бы ни одного.
 */
object ConnectionPolicy {

    const val MAX_REGULAR_CONNECTIONS = 3

    /** Сколько миллисекунд ждёт телефон с большим `member_id`, прежде чем запросить соединение сам. */
    const val DEFERRED_REQUEST_MS = 10_000L

    /** При трёх соединениях discovery включается на [DISCOVERY_WINDOW_MS] раз в [DISCOVERY_PERIOD_MS], мс. */
    const val DISCOVERY_WINDOW_MS = 15_000L
    const val DISCOVERY_PERIOD_MS = 60_000L

    private const val FIRST_RETRY_MS = 2_000L
    private const val MAX_RETRY_MS = 30_000L

    /**
     * true, если с [peer] можно соединиться (входящим или исходящим соединением), когда уже есть [connected].
     * [isOutsider] берётся из `TransportConfig.isOutsider`.
     */
    fun mayConnect(peer: MemberId, connected: Set<MemberId>, blocked: Set<MemberId>, isOutsider: Boolean): Boolean = when {
        peer in blocked || peer in connected -> false
        connected.size < MAX_REGULAR_CONNECTIONS -> true
        connected.size == MAX_REGULAR_CONNECTIONS -> isOutsider
        else -> false
    }

    /**
     * Через сколько миллисекунд запрашивать соединение с найденным телефоном. Первым запрашивает тот, у кого
     * `member_id` меньше: так реже бывают встречные запросы, на которых Nearby отклоняет одно из соединений.
     */
    fun requestDelayMs(me: MemberId, peer: MemberId): Long = if (me.value < peer.value) 0 else DEFERRED_REQUEST_MS

    /** Пауза в миллисекундах перед повтором после [failedAttempts] неудачных запросов подряд: 2, 4, 8, 16 с, дальше 30 с. */
    fun retryDelayMs(failedAttempts: Int): Long {
        require(failedAttempts >= 1) { "Пауза нужна только после неудачи" }
        val doublings = (failedAttempts - 1).coerceAtMost(4)
        return (FIRST_RETRY_MS shl doublings).coerceAtMost(MAX_RETRY_MS)
    }

    /** true, пока соединений меньше трёх: тогда discovery включён постоянно, а при трёх идёт окнами. */
    fun isDiscoveryAlwaysOn(connectedCount: Int): Boolean = connectedCount < MAX_REGULAR_CONNECTIONS
}
