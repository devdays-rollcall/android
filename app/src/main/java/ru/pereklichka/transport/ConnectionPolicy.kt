package ru.pereklichka.transport

import ru.pereklichka.domain.model.MemberId

/**
 * Политика соединений из `03-sync-protocol.md`, раздел «Транспорт поверх Nearby Connections». Чистые функции
 * без Android, их проверяет `ConnectionPolicyTest`; вызывает их `NearbyPeerTransport`.
 *
 * Обычных соединений до [MAX_REGULAR_CONNECTIONS], четвёртое только для телефона из другой части сети. Без этого
 * резерва телефоны, набрав по четыре соединения, не пускают вернувшегося отставшего.
 */
object ConnectionPolicy {

    const val MAX_REGULAR_CONNECTIONS = 3

    /** Сколько ждёт телефон с большим `member_id`, прежде чем постучаться сам. */
    const val DEFERRED_REQUEST_MS = 10_000L

    /** При трёх соединениях поиск включается на [DISCOVERY_WINDOW_MS] раз в [DISCOVERY_PERIOD_MS]. */
    const val DISCOVERY_WINDOW_MS = 15_000L
    const val DISCOVERY_PERIOD_MS = 60_000L

    private const val FIRST_RETRY_MS = 2_000L
    private const val MAX_RETRY_MS = 30_000L

    /**
     * Можно ли соединиться с [peer], входящим или исходящим соединением, когда уже есть [connected].
     * [isOutsider] берётся из `TransportConfig.isOutsider`.
     */
    fun mayConnect(peer: MemberId, connected: Set<MemberId>, blocked: Set<MemberId>, isOutsider: Boolean): Boolean = when {
        peer in blocked || peer in connected -> false
        connected.size < MAX_REGULAR_CONNECTIONS -> true
        connected.size == MAX_REGULAR_CONNECTIONS -> isOutsider
        else -> false
    }

    /**
     * Через сколько запрашивать соединение с найденным телефоном. Стучится тот, у кого `member_id` меньше:
     * так реже бывают встречные запросы, на которых Nearby отклоняет одно из соединений.
     */
    fun requestDelayMs(me: MemberId, peer: MemberId): Long = if (me.value < peer.value) 0 else DEFERRED_REQUEST_MS

    /** Пауза перед повтором после [failedAttempts] неудачных запросов подряд: 2, 4, 8, 16 с, дальше 30 с. */
    fun retryDelayMs(failedAttempts: Int): Long {
        require(failedAttempts >= 1) { "Пауза нужна только после неудачи" }
        val doublings = (failedAttempts - 1).coerceAtMost(4)
        return (FIRST_RETRY_MS shl doublings).coerceAtMost(MAX_RETRY_MS)
    }

    /** Поиск включён постоянно, пока соединений меньше трёх; при трёх он идёт окнами. */
    fun isDiscoveryAlwaysOn(connectedCount: Int): Boolean = connectedCount < MAX_REGULAR_CONNECTIONS
}
