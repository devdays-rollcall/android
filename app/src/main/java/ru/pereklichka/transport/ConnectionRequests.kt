package ru.pereklichka.transport

import com.google.android.gms.nearby.connection.ConnectionsStatusCodes

/**
 * Учёт своих запросов соединения по endpointId для [NearbyPeerTransport]: какие запросы ждут результата, какие
 * телефоны Nearby потерял и сколько неудач было подряд. По нему транспорт решает, звонить ли соседу и повторять ли
 * запрос после неудачи. Android здесь нет, проверяет `ConnectionRequestsTest`. Синхронизации тоже нет, поэтому
 * транспорт вызывает его под своим замком.
 */
internal class ConnectionRequests {

    /** endpointId, которым мы позвонили requestConnection и ещё не знаем результата. */
    private val inFlight = HashSet<String>()

    /** endpointId, о которых пришёл onEndpointLost и после него не было onEndpointFound. */
    private val lost = HashSet<String>()

    private val failedAttempts = HashMap<String, Int>()

    /** Телефон нашёлся. false, если наш запрос к нему уже ждёт результата и второй планировать не нужно. */
    fun onFound(endpointId: String): Boolean {
        lost -= endpointId
        return endpointId !in inFlight
    }

    fun onLost(endpointId: String) {
        lost += endpointId
    }

    /** Отмечает запрос перед вызовом requestConnection. false, если запрос к [endpointId] уже ждёт результата. */
    fun tryStart(endpointId: String): Boolean = inFlight.add(endpointId)

    /** Соединение установлено, счётчик неудач обнуляется. */
    fun onConnected(endpointId: String) {
        inFlight -= endpointId
        failedAttempts -= endpointId
    }

    /**
     * Запрос к [endpointId] не удался со статусом [statusCode] (null, если статуса нет). Возвращает паузу до повтора
     * в миллисекундах или null, если повторять не надо:
     * - запрос был не наш: повторяет инициатор, принявшая сторона ждёт его следующего звонка;
     * - Nearby потерял телефон: звонить некуда, следующий запрос запланирует onEndpointFound;
     * - сосед отклонил соединение (`STATUS_CONNECTION_REJECTED`): его политика нас не пускает, и повтор
     *   до следующего onEndpointFound получит тот же отказ.
     */
    fun onFailure(endpointId: String, statusCode: Int?): Long? {
        if (!inFlight.remove(endpointId)) return null
        if (endpointId in lost || statusCode == ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED) {
            failedAttempts -= endpointId
            return null
        }
        val attempt = (failedAttempts[endpointId] ?: 0) + 1
        failedAttempts[endpointId] = attempt
        return ConnectionPolicy.retryDelayMs(attempt)
    }

    /** Забывает запрос к [endpointId] без повтора: соединение разорвано или мы сами его отклонили. */
    fun forget(endpointId: String) {
        inFlight -= endpointId
    }

    fun clear() {
        inFlight.clear()
        lost.clear()
        failedAttempts.clear()
    }
}
